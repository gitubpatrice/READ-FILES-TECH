package com.readfilestech.read_files_tech.ui.tools.csvtools

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.convert.CsvTablePdf
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.outputs.create
import com.readfilestech.read_files_tech.data.tools.CsvMerge
import com.readfilestech.read_files_tech.data.tools.CsvMerged
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.tools.readFailure
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** What the CSV tools are writing: a PDF of the table, or the merged CSV. */
enum class CsvWork { PDF, MERGE }

/** What the picker was opened for. */
enum class CsvPick { OPEN, MERGE }

/** A CSV as the tools show it: its rows under the header, its columns, and the first [SHOWN_HEADERS] names of them. */
data class CsvSummary(val rows: Int, val columns: Int, val rowsCut: Boolean, val columnsCut: Boolean, val headers: List<String>) {
    companion object {
        /** Column names shown: a CSV may have 16 384 columns, the 2.15.3 laid a chip out for each. */
        const val SHOWN_HEADERS = 100
    }
}

/** The CSV tools' screen: the file chosen and what it holds, the output being made and the last one. */
data class CsvToolsState(
    val file: FileEntry? = null,
    val loading: Boolean = false,
    val failure: UiText? = null,
    val summary: CsvSummary? = null,
    val working: CsvWork? = null,
    /** The part done of [working], from 0 to 1. */
    val progress: Float = 0f,
    val result: String? = null,
    /** What the merge wrote; null when [result] is a PDF. */
    val merged: CsvMerged? = null,
    /** The output to share at once ("share automatically after creation"), once. */
    val share: String? = null,
)

/**
 * `CsvToolsScreen` of the 2.15.3: a CSV chosen in the app's picker, read off the main thread as the CSV viewer reads
 * it ([CsvTable]) — the 2.15.3 read it on the UI thread with no `try`, and copied its rows at each access of its
 * statistics (F8) —; its table exported to PDF on as many pages and bands of columns as it needs ([CsvTablePdf]);
 * several CSV merged into one ([CsvMerge]) — the file loaded FIRST, then those chosen: the 2.15.3 merged only those
 * chosen, the file on screen left out (F8). Both outputs go to `Conversions/`, stop when asked or when the screen is
 * left, writing nothing. The file chosen and the last output survive a process death.
 */
@HiltViewModel
class CsvToolsViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val outputs: OutputStorage,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        CsvToolsState(
            result = handle[KEY_RESULT],
            merged = handle.get<Long>(KEY_MERGED_ROWS)?.let { CsvMerged(it, handle[KEY_MERGED_FILES] ?: 0) },
        ),
    )
    val state: StateFlow<CsvToolsState> = mutableState.asStateFlow()
    private var table: CsvTable? = null
    private var loading: Job? = null
    private var working: Job? = null

    init {
        handle.get<String>(KEY_PATH)?.let(::choose)
    }

    /** The picker is opened for [pick]: its answer comes to [picked]. */
    fun pickFor(pick: CsvPick) {
        handle[KEY_PENDING] = pick.name
    }

    fun picked(paths: List<String>) {
        val pick = handle.get<String>(KEY_PENDING)?.let { name -> CsvPick.entries.find { it.name == name } } ?: return
        handle.remove<String>(KEY_PENDING)
        if (paths.isEmpty()) return
        when (pick) {
            CsvPick.OPEN -> choose(paths.first())
            CsvPick.MERGE -> merge(paths)
        }
    }

    fun choose(path: String) {
        if (working?.isCompleted == false) return
        handle[KEY_PATH] = path
        loading?.cancel()
        table = null
        loading = viewModelScope.launch {
            val file = File(path)
            val entry = withContext(io) { DirectoryListing.entryOf(file) }
            mutableState.update { it.copy(file = entry, loading = true, failure = null, summary = null) }
            val read = loadOrFailure {
                withContext(io) {
                    val csv = CsvTable.read(StorageFileSource(file))
                    val shown = minOf(csv.columns, CsvSummary.SHOWN_HEADERS)
                    csv to CsvSummary(csv.rows, csv.columns, csv.rowsCut, csv.columnsCut, csv.header(0, shown, CsvTable.SHOWN_CHARS))
                }
            }
            read.fold(
                { (csv, summary) ->
                    table = csv
                    mutableState.update { it.copy(loading = false, summary = summary) }
                },
                { error -> mutableState.update { it.copy(loading = false, failure = readFailure(entry.name, error)) } },
            )
        }
    }

    /** The table as a PDF in `Conversions/`. */
    fun exportPdf() {
        val csv = table ?: return
        val entry = mutableState.value.file ?: return
        start(CsvWork.PDF) { progress ->
            val pdf = outputs.create(OutputCategory.CONVERSIONS, entry.file.nameWithoutExtension, PDF) { out ->
                CsvTablePdf.write(csv, out, progress)
            }
            pdf to null
        }
    }

    /** The file loaded, if any, then [paths], merged into one CSV in `Conversions/`; a file chosen twice is merged once. */
    fun merge(paths: List<String>) {
        val loaded = mutableState.value.file?.path?.takeIf { table != null }
        val files = (listOfNotNull(loaded) + paths).distinct().map(::File)
        start(CsvWork.MERGE) { progress ->
            var merged = CsvMerged(0, 0)
            val output = outputs.create(OutputCategory.CONVERSIONS, MERGE_NAME, CSV) { out ->
                merged = CsvMerge.write(files, out, progress)
            }
            output to merged
        }
    }

    /** Stops the output being made: nothing is written. */
    fun stop() {
        working?.cancel()
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    private fun start(work: CsvWork, make: suspend (progress: (Float) -> Unit) -> Pair<File, CsvMerged?>) {
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
                        val (file, merged) = made
                        handle[KEY_RESULT] = file.path
                        handle[KEY_MERGED_ROWS] = merged?.rows
                        handle[KEY_MERGED_FILES] = merged?.files
                        mutableState.update { it.copy(result = file.path, merged = merged, share = file.path.takeIf { share }) }
                    },
                    { error -> messages.error(csvToolsFailure(work, mutableState.value.file?.name ?: MERGE_NAME, error)) },
                )
            } finally {
                mutableState.update { it.copy(working = null, progress = 0f) }
            }
        }
    }

    private companion object {
        const val KEY_PATH = "csv_tools_path"
        const val KEY_PENDING = "csv_tools_pending"
        const val KEY_RESULT = "csv_tools_result"
        const val KEY_MERGED_ROWS = "csv_tools_merged_rows"
        const val KEY_MERGED_FILES = "csv_tools_merged_files"

        /** The 2.15.3's name of the merged file (`csv_tools_screen.dart:187`). */
        const val MERGE_NAME = "fusion_csv"
        const val CSV = "csv"
        const val PDF = "pdf"
    }
}
