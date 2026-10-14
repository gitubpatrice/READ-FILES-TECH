package com.readfilestech.read_files_tech.ui.viewer.pdf

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfConnection
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfDocument
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfOpening
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusal
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusalException
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfUnlocked
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.ViewerResources
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Where the PDF viewer is, once its file is loaded. */
sealed interface PdfPhase {
    /** Encrypted: the viewer asks for the password; [wrong] after a password that was not it. */
    data class Locked(val wrong: Boolean = false, val busy: Boolean = false) : PdfPhase

    data class Open(val pages: PdfPageImages) : PdfPhase

    /**
     * No page to show, in words: a [refusal] of this viewer's own, or a failure after the load (an unlock,
     * [refusal] null).
     */
    data class Failed(val failure: ViewerFailure, val refusal: PdfRefusal? = null) : PdfPhase
}

/**
 * A PDF the viewer shows (port of `PdfViewerScreen` of the 2.15.3): its phase, and its connection to the
 * `:pdf` process — when that process ends, the screen says the file stopped the reader, whatever the phase.
 */
class PdfViewer internal constructor(
    val connection: PdfConnection,
    internal val opening: PdfOpening,
    /** The load's: what an unlock opens later is closed with the viewer too. */
    internal val resources: ViewerResources,
    first: PdfPhase,
) {
    internal val mutablePhase = MutableStateFlow(first)
    val phase: StateFlow<PdfPhase> = mutablePhase.asStateFlow()

    companion object {
        /** What the screen loads, and the corpus too (CorpusCheck): the same calls, the `:pdf` process's. */
        suspend fun load(context: Context, source: ViewerSource, resources: ViewerResources, io: CoroutineDispatcher): PdfViewer {
            val connection = resources.keep(PdfConnection(context))
            val file = resources.keep(source.acquireFile(FileCaps.PDF_VIEWER))
            val opening = PdfOpening(connection, file.file, PdfUnlocked.under(context.cacheDir)) { resources.keep(it) }
            val first = try {
                opened(opening.start(), resources, io)
            } catch (e: PdfRefusalException) {
                phaseOf(e)
            }
            return PdfViewer(connection, opening, resources, first)
        }

        internal fun opened(document: PdfDocument, resources: ViewerResources, io: CoroutineDispatcher) =
            PdfPhase.Open(resources.keep(PdfPageImages(document, io)))

        internal fun phaseOf(refusal: PdfRefusalException): PdfPhase = when (refusal.refusal) {
            PdfRefusal.PASSWORD_REQUIRED -> PdfPhase.Locked()
            else -> PdfPhase.Failed(ViewerFailure.Reason(textOf(refusal.refusal)), refusal.refusal)
        }

        fun textOf(refusal: PdfRefusal): UiText = when (refusal) {
            PdfRefusal.PASSWORD_REQUIRED -> uiText(R.string.pdf_password_required)
            PdfRefusal.CERTIFICATE -> uiText(R.string.pdf_certificate_encrypted)
            PdfRefusal.READER_STOPPED -> uiText(R.string.pdf_reader_stopped)
        }
    }
}

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val source: ViewerSource? = ViewerSources.from(handle)

    val content = ViewerContent(viewModelScope, io) {
        PdfViewer.load(context, requireNotNull(source) { "no file in the route" }, this, io).also { outlineOf(it.phase.value) }
    }.also(::addCloseable)

    /**
     * [password] tried on [viewer]'s file: the document decrypted, or the question asked again. The password
     * goes to the `:pdf` process and is kept nowhere — not in this view model, not in a saved state.
     */
    fun unlock(viewer: PdfViewer, password: String) {
        if ((viewer.phase.value as? PdfPhase.Locked)?.busy != false) return
        viewer.mutablePhase.value = PdfPhase.Locked(busy = true)
        viewModelScope.launch {
            val next = loadOrFailure { withContext(io) { viewer.opening.unlock(password) } }.fold(
                { document -> document?.let { PdfViewer.opened(it, viewer.resources, io) } ?: PdfPhase.Locked(wrong = true) },
                { error -> if (error is PdfRefusalException) PdfViewer.phaseOf(error) else PdfPhase.Failed(ViewerErrors.failureOf(error)) },
            )
            viewer.mutablePhase.value = next
            outlineOf(next)
        }
    }

    /** The outline of an open document, read once in the background: "Contents" appears when it has entries. */
    private fun outlineOf(phase: PdfPhase) {
        val pages = (phase as? PdfPhase.Open)?.pages ?: return
        // Without "Contents" rather than without the document: an outline that fails is none.
        viewModelScope.launch { loadOrFailure { withContext(io) { pages.readOutline() } } }
    }
}
