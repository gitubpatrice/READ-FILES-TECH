package com.readfilestech.read_files_tech.ui.viewer

import androidx.lifecycle.SavedStateHandle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.files.MEGA
import com.readfilestech.read_files_tech.data.images.ImageBounds
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.VaultFileSource
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.vault.VaultCipher
import com.readfilestech.read_files_tech.vault.VaultDirs
import com.readfilestech.read_files_tech.vault.VaultFiles
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
import javax.inject.Inject

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
 * The source of the viewer at a route's arguments: a file of the storage ([Routes.ARG_PATH]), or a file of
 * the vault ([Routes.ARG_VAULT], docs D-028) — here and nowhere else. A vault route naming no file of the
 * vault has no source: the viewer says it cannot read it.
 */
class ViewerSources @Inject constructor(private val files: VaultFiles, private val dirs: VaultDirs, private val cipher: VaultCipher) {
    fun from(handle: SavedStateHandle): ViewerSource? {
        val inVault = handle.get<String>(Routes.ARG_VAULT)
        if (inVault != null) return dirs.fileNamed(inVault)?.let { VaultFileSource(files, cipher, it) }
        return handle.get<String>(Routes.ARG_PATH)?.takeIf(String::isNotEmpty)?.let { StorageFileSource(File(it)) }
    }

    companion object {
        /** Whether the route at [handle] is a vault file's: its viewer is secured, and closes when the vault locks. */
        fun isVault(handle: SavedStateHandle): Boolean = handle.get<String>(Routes.ARG_VAULT) != null
    }
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
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The file shown when the app may change it behind the viewer — its "Edit" opens an editor on it: read again
     * by [reloadIfChanged] once its size or time changed. The 2.15.3's CSV viewer showed the table of before.
     */
    private val watched: File? = null,
    private val load: suspend ViewerResources.() -> T,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<ViewerState<T>>(ViewerState.Loading)
    val state: StateFlow<ViewerState<T>> = mutableState.asStateFlow()
    private var resources = ViewerResources()

    /** The [watched] file's size and time when it was read. */
    private var stamp: Pair<Long, Long>? = null
    private var job: Job = start()

    private fun start(): Job {
        val opened = resources
        return scope.launch {
            stamp = watched?.let { withContext(io) { stampOf(it) } }
            mutableState.value = loadOrFailure { withContext(io) { opened.load() } }
                .fold({ ViewerState.Ready(it) }, { ViewerState.Failed(ViewerErrors.failureOf(it)) })
        }
    }

    /**
     * Reads the [watched] file again if its size or time changed since it was read; nothing while it is read. The
     * check runs off the main thread: its job, which a test waits for; null when nothing is watched.
     */
    fun reloadIfChanged(): Job? {
        val file = watched ?: return null
        return scope.launch {
            val now = withContext(io) { stampOf(file) }
            if (job.isCompleted && stamp != null && now != stamp) reload()
        }
    }

    private fun reload() {
        val previous = job
        val opened = resources
        previous.cancel()
        previous.invokeOnCompletion { opened.close() }
        resources = ViewerResources()
        mutableState.value = ViewerState.Loading
        job = start()
    }

    override fun close() {
        val last = job
        val opened = resources
        last.cancel()
        // At once when the job is over; else when the blocking call it is in returns.
        last.invokeOnCompletion { opened.close() }
    }

    private fun stampOf(file: File): Pair<Long, Long> = file.length() to file.lastModified()
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
        // The dimensions, and the limits they break: a photo stitched too wide, or a decompression bomb.
        is ImageTooLargeException -> ViewerFailure.Reason(
            uiText(R.string.viewer_image_too_large, error.width, error.height, ImageBounds.MAX_SIDE_PX, FileCaps.IMAGE_PIXELS / MEGA),
        )
        is TooManyItemsException -> ViewerFailure.Reason(uiText(R.string.viewer_too_many_items, error.limit))
        is IOException -> ViewerFailure.Reason(uiText(R.string.viewer_cannot_open, IoErrors.reasonOf(error)))
        // A parser's own exception on a hostile file: an index out of bounds, a number that is not one.
        else -> ViewerFailure.Reason(uiText(R.string.viewer_unreadable))
    }
}
