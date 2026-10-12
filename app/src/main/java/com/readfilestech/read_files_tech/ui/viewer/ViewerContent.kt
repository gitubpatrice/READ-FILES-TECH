package com.readfilestech.read_files_tech.ui.viewer

import androidx.lifecycle.SavedStateHandle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Why a viewer shows no content. */
sealed interface ViewerFailure {
    /** Above the viewer's cap: the screen names the cap, in the user's units. */
    data class TooLarge(val capBytes: Long) : ViewerFailure

    data class Reason(val text: UiText) : ViewerFailure
}

/** A viewer's screen: loading, then its content or why there is none. */
sealed interface ViewerState<out T> {
    data object Loading : ViewerState<Nothing>

    data class Failed(val failure: ViewerFailure) : ViewerState<Nothing>

    data class Ready<T>(val content: T) : ViewerState<T>
}

/**
 * The source of the viewer at a route's arguments — a file of the storage today; the vault's branch
 * comes with phase 6 (D-028), here and nowhere else.
 */
object ViewerSources {
    fun from(
        handle: SavedStateHandle
    ): ViewerSource? = handle.get<String>(Routes.ARG_PATH)?.takeIf(String::isNotEmpty)?.let { StorageFileSource(File(it)) }
}

/**
 * [block]'s value, or what a hostile file made it throw — an OutOfMemoryError and a StackOverflowError (a
 * JSON or an XML nested a million deep) included: the allocation or the stack that failed is given back
 * at once, and the app lives on (inventory §6.9). A cancellation goes through, never swallowed. Every
 * viewer's work on a file goes through here: its load ([ViewerContent]) and what it does after (the text
 * viewer's colours, whose failure ended the app — Claude review R9).
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> loadOrFailure(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
} catch (e: OutOfMemoryError) {
    Result.failure(e)
} catch (e: StackOverflowError) {
    Result.failure(e)
}

/**
 * A viewer's content, loaded once off the main thread, from a view model's scope. Whatever the load
 * opens that must be closed is handed to [ViewerResources.keep] the moment it is opened: [close] (the
 * view model's, through addCloseable) closes it once the load has stopped — not before, while a parser
 * may still use it (a PdfRenderer refuses to close with a page open — R9) — even when the screen left
 * while the load ran.
 */
class ViewerContent<T : Any>(
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val load: suspend ViewerResources.() -> T,
) : AutoCloseable {
    private val resources = ViewerResources()
    private val mutableState = MutableStateFlow<ViewerState<T>>(ViewerState.Loading)
    val state: StateFlow<ViewerState<T>> = mutableState.asStateFlow()
    private val job: Job = scope.launch {
        mutableState.value = loadOrFailure { withContext(io) { resources.load() } }
            .fold({ ViewerState.Ready(it) }, { ViewerState.Failed(ViewerErrors.failureOf(it)) })
    }

    override fun close() {
        job.cancel()
        // At once when the job is over; else when the blocking call it is in returns.
        job.invokeOnCompletion { resources.close() }
    }
}

/** What a viewer's load opened, closed with the viewer. */
class ViewerResources internal constructor() : AutoCloseable {
    private val kept = mutableListOf<AutoCloseable>()
    private var closed = false

    /** Closes [resource] with the viewer — at once if the viewer is already gone. */
    fun <R : AutoCloseable> keep(resource: R): R {
        val late = synchronized(kept) {
            if (!closed) kept += resource
            closed
        }
        if (late) resource.close()
        return resource
    }

    override fun close() {
        val all = synchronized(kept) {
            closed = true
            kept.toList().also { kept.clear() }
        }
        all.asReversed().forEach { runCatching { it.close() } }
    }
}

/** A viewer's failure, in words — never the parser's exception, which the 2.15.x showed (inventory §1.C). */
object ViewerErrors {
    fun failureOf(error: Throwable): ViewerFailure = when (error) {
        is FileTooLargeException -> ViewerFailure.TooLarge(error.capBytes)
        is OutOfMemoryError -> ViewerFailure.Reason(uiText(R.string.viewer_out_of_memory))
        is UnsupportedFormatException -> ViewerFailure.Reason(uiText(R.string.viewer_unsupported))
        is UnreadableFileException -> ViewerFailure.Reason(uiText(R.string.viewer_unreadable))
        is IOException -> ViewerFailure.Reason(uiText(R.string.viewer_cannot_open, IoErrors.reasonOf(error)))
        // A parser's own exception on a hostile file: an index out of bounds, a number that is not one.
        else -> ViewerFailure.Reason(uiText(R.string.viewer_unreadable))
    }
}
