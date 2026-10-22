package com.readfilestech.read_files_tech.ui.tools.texttools

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.convert.TextPdf
import com.readfilestech.read_files_tech.data.editor.NotEditableException
import com.readfilestech.read_files_tech.data.editor.UnencodableTextException
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.files.MIB
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.outputs.create
import com.readfilestech.read_files_tech.data.tools.NoMatchException
import com.readfilestech.read_files_tech.data.tools.ResultTooLargeException
import com.readfilestech.read_files_tech.data.tools.TextBytes
import com.readfilestech.read_files_tech.data.tools.TextStats
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.tools.editor.CodeEditorSession
import com.readfilestech.read_files_tech.ui.tools.outputFailure
import com.readfilestech.read_files_tech.ui.tools.readFailure
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** What the text tools are writing: a copy with the replacements, or a PDF. */
enum class TxtWork { REPLACE, PDF }

/** The text tools' screen: the file chosen, its statistics, the search's count, the output being made and the last one. */
data class TxtToolsState(
    val file: FileEntry? = null,
    val loading: Boolean = false,
    val failure: UiText? = null,
    val stats: TextStats? = null,
    /** The part of the text counted, while its statistics are. */
    val counting: Float? = null,
    /** How many times the search is in the text; null with no search, or while it is counted. */
    val matches: Long? = null,
    val searching: Boolean = false,
    val working: TxtWork? = null,
    /** The part done of [working], from 0 to 1. */
    val progress: Float = 0f,
    val result: String? = null,
    /** The replacements in [result]; null when it is a PDF. */
    val replaced: Long? = null,
    /** The output to share at once ("share automatically after creation"), once. */
    val share: String? = null,
)

/**
 * `TxtToolsScreen` of the 2.15.3: a text chosen in the app's picker, read off the main thread (the 2.15.3 read it
 * on the UI thread, as strict UTF-8), its statistics and the search's count computed by pieces, stopped when the
 * file or the search changes. "Replace all" writes a COPY with the replacements into `Conversions/` — the 2.15.3
 * overwrote the file without asking, with no way back (D12) — and "Convert to PDF" lays the text out on as many
 * pages as it needs, in a monospace face — the 2.15.3 drew it on one page, the rest cut (F7). Both stop when
 * asked or when the screen is left, writing nothing. The file chosen and the last output survive a process death.
 */
@HiltViewModel
class TxtToolsViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val outputs: OutputStorage,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TxtToolsState(result = handle[KEY_RESULT], replaced = handle[KEY_REPLACED]))
    val state: StateFlow<TxtToolsState> = mutableState.asStateFlow()
    private var text: TextBytes? = null
    private var query = ""
    private var loading: Job? = null
    private var searching: Job? = null
    private var working: Job? = null

    init {
        handle.get<String>(KEY_PATH)?.let(::choose)
    }

    fun choose(path: String) {
        if (working?.isCompleted == false) return
        // Another file: the result of the previous one leaves the screen — seen on the S9, the card of a PDF
        // made of another file stayed, and pushed the export button out of sight. The same file, read again
        // after a process death, keeps its own.
        if (handle.get<String>(KEY_PATH) != path) {
            handle.remove<String>(KEY_RESULT)
            handle.remove<Long>(KEY_REPLACED)
            mutableState.update { it.copy(result = null, replaced = null, share = null) }
        }
        handle[KEY_PATH] = path
        loading?.cancel()
        searching?.cancel()
        text = null
        loading = viewModelScope.launch {
            val file = File(path)
            val entry = withContext(io) { DirectoryListing.entryOf(file) }
            mutableState.update {
                it.copy(file = entry, loading = true, failure = null, stats = null, counting = null, matches = null, searching = false)
            }
            val loaded = loadOrFailure { withContext(io) { TextBytes.read(StorageFileSource(file)) } }
            val bytes = loaded.getOrElse { error ->
                mutableState.update { it.copy(loading = false, failure = readFailure(entry.name, error)) }
                return@launch
            }
            text = bytes
            mutableState.update { it.copy(loading = false, counting = 0f) }
            search(query)
            val stats = loadOrFailure {
                withContext(io) {
                    val job = currentCoroutineContext()
                    bytes.stats { done ->
                        job.ensureActive()
                        mutableState.update { it.copy(counting = done) }
                    }
                }
            }
            mutableState.update { current ->
                stats.fold(
                    { current.copy(stats = it, counting = null) },
                    { current.copy(counting = null, failure = readFailure(entry.name, it)) },
                )
            }
        }
    }

    /** [value] counted in the text, a moment after the last key typed: a new search stops the last one. */
    fun search(value: String) {
        query = value
        searching?.cancel()
        val bytes = text
        if (value.isEmpty() || bytes == null) {
            mutableState.update { it.copy(matches = null, searching = false) }
            return
        }
        mutableState.update { it.copy(matches = null, searching = true) }
        searching = viewModelScope.launch {
            delay(SEARCH_DELAY_MS)
            val found = loadOrFailure {
                withContext(io) {
                    val job = currentCoroutineContext()
                    bytes.count(value) { job.ensureActive() }
                }
            }
            mutableState.update { it.copy(matches = found.getOrNull(), searching = false) }
        }
    }

    /** Each [search] replaced by [replacement], into a copy of the file in `Conversions/`. */
    fun replaceAll(search: String, replacement: String) {
        val bytes = text
        val entry = mutableState.value.file
        if (bytes == null || entry == null || search.isEmpty()) return
        start(TxtWork.REPLACE, entry) { progress ->
            var count = 0L
            val name = entry.file.nameWithoutExtension
            val copy = outputs.create(OutputCategory.CONVERSIONS, name, entry.extension.ifEmpty { TXT }) { out ->
                count = bytes.replaceAll(search, replacement, out, FileCaps.TEXT_VIEWER, progress)
            }
            copy to count
        }
    }

    /** The text as a PDF in `Conversions/`, within the bounds of Convert's Text → PDF. */
    fun exportPdf() {
        val bytes = text ?: return
        val entry = mutableState.value.file ?: return
        start(TxtWork.PDF, entry) { progress ->
            if (bytes.size > FileCaps.TEXT_TO_PDF) throw FileTooLargeException(FileCaps.TEXT_TO_PDF)
            val document = bytes.document()
            val pdf = outputs.create(OutputCategory.CONVERSIONS, entry.file.nameWithoutExtension, PDF) { out ->
                TextPdf.write(document, out, TextPdf.Face.MONOSPACE) { row -> progress(row.toFloat() / document.rowCount) }
            }
            pdf to null
        }
    }

    /** Stops the output being made: nothing is written. */
    fun stop() {
        working?.cancel()
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    /**
     * [work] run off the main thread, one at a time; [make] gets a progress that stops it when it is cancelled,
     * and gives the output made and, for a copy, its replacements.
     */
    private fun start(work: TxtWork, entry: FileEntry, make: suspend (progress: (Float) -> Unit) -> Pair<File, Long?>) {
        if (working?.isCompleted == false) return
        mutableState.update { it.copy(working = work, progress = 0f) }
        working = viewModelScope.launch {
            try {
                val outcome = loadOrFailure {
                    withContext(io) {
                        val job = currentCoroutineContext()
                        make { done ->
                            job.ensureActive()
                            mutableState.update { it.copy(progress = done) }
                        } to outputs.autoShare()
                    }
                }
                outcome.fold(
                    { (made, share) ->
                        val (file, replaced) = made
                        handle[KEY_RESULT] = file.path
                        handle[KEY_REPLACED] = replaced
                        mutableState.update { it.copy(result = file.path, replaced = replaced, share = file.path.takeIf { share }) }
                    },
                    { error -> messages.error(failure(work, entry.name, error)) },
                )
            } finally {
                mutableState.update { it.copy(working = null, progress = 0f) }
            }
        }
    }

    /** Why [work] on the file [name] wrote nothing, in words — the 2.15.3 showed "Erreur : " and the exception. */
    private fun failure(work: TxtWork, name: String, error: Throwable): UiText = outputFailure(name, error) ?: when (error) {
        is NotEditableException -> {
            val notText = error.reason == NotEditableException.Reason.NOT_TEXT
            uiText(if (notText) R.string.txt_replace_not_text else R.string.txt_replace_not_reversible, name)
        }
        is UnencodableTextException ->
            uiText(R.string.txt_replace_unencodable, CodeEditorSession.encodingName(error.encoding), error.character)
        is ResultTooLargeException -> uiText(R.string.txt_replace_too_large, error.capBytes / MIB, uiText(R.string.unit_megabytes))
        is NoMatchException -> uiText(R.string.txt_replace_no_match)
        is FileTooLargeException -> uiText(R.string.txt_pdf_too_large, error.capBytes / MIB, uiText(R.string.unit_megabytes))
        is TooManyItemsException -> UiText.Plural(R.plurals.convert_too_many_pages, error.limit)
        else -> uiText(if (work == TxtWork.REPLACE) R.string.txt_replace_failed else R.string.tools_pdf_failed, IoErrors.reasonOf(error))
    }

    private companion object {
        const val KEY_PATH = "txt_tools_path"
        const val KEY_RESULT = "txt_tools_result"
        const val KEY_REPLACED = "txt_tools_replaced"
        const val TXT = "txt"
        const val PDF = "pdf"

        /** The search waits this long after a key: each count reads the whole text. */
        const val SEARCH_DELAY_MS = 300L
    }
}
