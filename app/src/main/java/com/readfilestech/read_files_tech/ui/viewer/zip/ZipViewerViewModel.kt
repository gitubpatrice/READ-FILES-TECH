package com.readfilestech.read_files_tech.ui.viewer.zip

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.zip.ExtractionStop
import com.readfilestech.read_files_tech.data.viewer.zip.NotExtracted
import com.readfilestech.read_files_tech.data.viewer.zip.ZipArchive
import com.readfilestech.read_files_tech.data.viewer.zip.ZipEntryException
import com.readfilestech.read_files_tech.data.viewer.zip.ZipExtraction
import com.readfilestech.read_files_tech.data.viewer.zip.ZipItem
import com.readfilestech.read_files_tech.data.viewer.zip.ZipShares
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Where "Extract all" is. */
sealed interface Extraction {
    data object Idle : Extraction

    /** [done] entries of [total], [bytes] written. */
    data class Running(val done: Int, val total: Int, val bytes: Long) : Extraction

    data class Done(val result: ExtractionResult) : Extraction
}

/** How an extraction ended, as its dialog says it. */
sealed interface ExtractionEnd {
    data object Finished : ExtractionEnd

    data object Cancelled : ExtractionEnd

    data object TotalReached : ExtractionEnd

    data class Failed(val reason: UiText) : ExtractionEnd

    /** No folder of the storage could take it: nothing was read. */
    data object NoLocation : ExtractionEnd
}

/**
 * What an extraction did: [folder] is null when nothing was kept — no file written, the empty folder
 * removed (the 2.15.3 also announced "Extracted into …" for an archive it could not read, D17).
 */
data class ExtractionResult(
    val folder: File?,
    val written: Int,
    val refused: List<Pair<ZipItem, NotExtracted>>,
    val end: ExtractionEnd,
    /** Whether the archive holds any file at all, folders aside. */
    val holdsFiles: Boolean,
)

@HiltViewModel
class ZipViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val outputs: OutputStorage,
    private val shares: ZipShares,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    sources: ViewerSources,
) : ViewModel() {
    val source: ViewerSource? = sources.from(handle)

    val content = ViewerContent(viewModelScope, io) {
        keep(ZipArchive.open(requireNotNull(source) { "no file in the route" }))
    }
        .also(::addCloseable)

    /**
     * Whether entries may leave the app — shared, or extracted to shared storage: never those of a file
     * that may not leave it itself, the vault's (docs D-028).
     */
    val canExport: Boolean = source?.shareable != null

    private val query = MutableStateFlow("")
    val search: StateFlow<String> = query.asStateFlow()

    /** The entries shown: all, by name, or those whose path holds [search], ignoring case (the 2.15.3's filter). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val shown: StateFlow<List<ZipItem>> = combine(content.state, query) { state, text ->
        val all = (state as? ViewerState.Ready)?.content?.byName.orEmpty()
        if (text.isEmpty()) all else all.filter { it.name.contains(text, ignoreCase = true) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val extracting = MutableStateFlow<Extraction>(Extraction.Idle)
    val extraction: StateFlow<Extraction> = extracting.asStateFlow()
    private var extractionJob: Job? = null
    private var cancelAsked = false

    private val preparing = MutableStateFlow<Int?>(null)

    /** The entry being extracted to be shared, by its index; null when none. */
    val sharing: StateFlow<Int?> = preparing.asStateFlow()

    private val prepared = MutableStateFlow<File?>(null)

    /** An entry extracted, ready for the share sheet: the screen shows it, then calls [shareShown]. */
    val toShare: StateFlow<File?> = prepared.asStateFlow()

    fun search(text: String) {
        query.value = text
    }

    /** "Extract all" into a new folder of `Files Tech/Extractions/` (docs D-022); one at a time. */
    fun extractAll() {
        val archive = readyArchive() ?: return
        if (!canExport || extracting.value is Extraction.Running) return
        cancelAsked = false
        extracting.value = Extraction.Running(0, archive.entries.size, 0)
        extractionJob = viewModelScope.launch { extracting.value = Extraction.Done(extract(archive)) }
    }

    fun cancelExtraction() {
        cancelAsked = true
        extractionJob?.cancel()
    }

    /** The result was read: the dialog closes. */
    fun extractionSeen() {
        if (extracting.value is Extraction.Done) extracting.value = Extraction.Idle
    }

    /**
     * Runs one extraction to its end, through [loadOrFailure] — the frame's guard of a viewer's work: an
     * archive that makes it throw anything but an I/O error (the extraction keeps those in its report)
     * ends it in words, never the app.
     */
    private suspend fun extract(archive: ZipArchive): ExtractionResult {
        val holdsFiles = archive.entries.any { !it.isDirectory }
        val folder = loadOrFailure { withContext(io) { outputs.reserveFolder(OutputCategory.EXTRACTIONS, baseName()) } }
            .getOrElse { return ExtractionResult(null, 0, emptyList(), ExtractionEnd.NoLocation, holdsFiles) }
        val extraction = ZipExtraction(archive, folder)
        val outcome = try {
            loadOrFailure {
                withContext(io) {
                    extraction.run { done, bytes -> extracting.value = Extraction.Running(done, archive.entries.size, bytes) }
                }
            }
        } catch (e: CancellationException) {
            val result = withContext(NonCancellable + io) { resultOf(extraction, ExtractionEnd.Cancelled, holdsFiles) }
            // Cancelled by leaving the screen, not by its button: said where the user now is.
            if (!cancelAsked && result.written > 0) {
                messages.info(UiText.Plural(R.plurals.zip_extract_left, result.written, listOf(result.written, labelOf(folder))))
            }
            extracting.value = Extraction.Done(result)
            throw e
        }
        val end = outcome.exceptionOrNull()?.let {
            ExtractionEnd.Failed(IoErrors.reasonOf(it))
        } ?: endOf(extraction.report.stop)
        return withContext(io) { resultOf(extraction, end, holdsFiles) }
    }

    /** The result; the folder removed when it holds nothing, the folders it made aside. */
    private fun resultOf(extraction: ZipExtraction, end: ExtractionEnd, holdsFiles: Boolean): ExtractionResult {
        val report = extraction.report
        val kept = !extraction.discardIfEmpty()
        return ExtractionResult(report.folder.takeIf { kept }, report.written, report.refused.toList(), end, holdsFiles)
    }

    /** Shares one entry: extracted to the cache first, then handed to the share sheet. */
    fun share(item: ZipItem) {
        val archive = readyArchive() ?: return
        if (!canExport || item.shareName == null || preparing.value != null) return
        preparing.value = item.index
        viewModelScope.launch {
            try {
                loadOrFailure { withContext(io) { shares.extract(archive, item) } }
                    .onSuccess { prepared.value = it }
                    .onFailure { messages.error(uiText(R.string.zip_share_failed, shareFailure(it))) }
            } finally {
                preparing.value = null
            }
        }
    }

    fun shareShown() {
        prepared.value = null
    }

    private fun readyArchive(): ZipArchive? = (content.state.value as? ViewerState.Ready)?.content

    /** The archive's name without its extension (`'\.\w+$'` removed by the 2.15.3). */
    private fun baseName(): String {
        val name = source?.name.orEmpty()
        return if (FileNames.extension(name).isEmpty()) name else name.substringBeforeLast('.')
    }

    companion object {
        /** `Extractions/<folder>`: where it is, in words short enough for a message. */
        fun labelOf(folder: File): String = listOfNotNull(folder.parentFile?.name, folder.name).joinToString("/")

        /** How an extraction that ran to its last entry, or stopped by itself, ended. */
        private fun endOf(stop: ExtractionStop?): ExtractionEnd = when (stop) {
            null -> ExtractionEnd.Finished
            ExtractionStop.TotalReached -> ExtractionEnd.TotalReached
            is ExtractionStop.Failed -> ExtractionEnd.Failed(IoErrors.reasonOf(stop.error))
        }

        /** An entry offered for sharing has no problem of its own: what goes wrong is its data, or the storage. */
        private fun shareFailure(error: Throwable): UiText =
            if (error is ZipEntryException) uiText(R.string.zip_problem_damaged) else IoErrors.reasonOf(error)
    }
}
