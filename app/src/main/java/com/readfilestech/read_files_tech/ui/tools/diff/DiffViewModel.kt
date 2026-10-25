package com.readfilestech.read_files_tech.ui.tools.diff

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.tools.LineComparison
import com.readfilestech.read_files_tech.data.tools.LineDiff
import com.readfilestech.read_files_tech.data.tools.TextLines
import com.readfilestech.read_files_tech.data.tools.ToolTexts
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.readFailure
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** The two files of a comparison. */
enum class DiffSlot { A, B }

/**
 * The compare tool's screen: the two files chosen, whether line endings count, and the comparison — the
 * files identical, or their lines compared — or why there is none.
 */
data class DiffState(
    val fileA: FileEntry? = null,
    val fileB: FileEntry? = null,
    val lineEndings: Boolean = false,
    val computing: Boolean = false,
    val identical: Boolean = false,
    val comparison: LineComparison? = null,
    val stopped: Boolean = false,
    val failure: UiText? = null,
)

/**
 * `DiffScreen` of the 2.15.3: two files chosen one after the other, compared once both are. The paths and
 * the option are in the saved state, so the comparison is made again after a process death. The files'
 * lines are kept while their paths do not change: counting line endings or not compares them again
 * without reading them again.
 */
@HiltViewModel
class DiffViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiffState(lineEndings = handle[KEY_LINE_ENDINGS] ?: false))
    val state: StateFlow<DiffState> = mutableState.asStateFlow()
    private var lines: Pair<TextLines, TextLines>? = null
    private var job: Job? = null

    init {
        compare()
    }

    /** The picker is opened for [slot]: what it hands back is that file. */
    fun picking(slot: DiffSlot) {
        handle[KEY_PICKING] = slot.name
    }

    fun picked(path: String) {
        val slot = handle.get<String>(KEY_PICKING)?.let(DiffSlot::valueOf) ?: return
        handle[if (slot == DiffSlot.A) KEY_A else KEY_B] = path
        lines = null
        compare()
    }

    fun lineEndings(count: Boolean) {
        handle[KEY_LINE_ENDINGS] = count
        mutableState.update { it.copy(lineEndings = count) }
        compare()
    }

    fun stop() {
        job?.cancel()
        mutableState.update { it.copy(computing = false, stopped = true) }
    }

    /** Reads what it must of the two files, then compares them; nothing until both are chosen. */
    fun compare() {
        job?.cancel()
        val pathA = handle.get<String>(KEY_A)
        val pathB = handle.get<String>(KEY_B)
        val lineEndings = state.value.lineEndings
        job = viewModelScope.launch {
            val entries = withContext(io) { listOf(pathA, pathB).map { path -> path?.let { DirectoryListing.entryOf(File(it)) } } }
            mutableState.update {
                DiffState(fileA = entries[0], fileB = entries[1], lineEndings = lineEndings, computing = pathA != null && pathB != null)
            }
            if (pathA == null || pathB == null) return@launch
            val kept = lines ?: read(File(pathA), File(pathB)) ?: return@launch
            lines = kept
            loadOrFailure { withContext(Dispatchers.Default) { LineDiff.compare(kept.first, kept.second, lineEndings) } }
                .onSuccess { comparison -> mutableState.update { it.copy(computing = false, comparison = comparison) } }
                .onFailure { mutableState.update { it.copy(computing = false, failure = uiText(R.string.error_unexpected)) } }
        }
    }

    /** Both files' lines, or null when they are identical or one could not be read (the state says which). */
    private suspend fun read(a: File, b: File): Pair<TextLines, TextLines>? {
        val bytes = named(a) { withContext(io) { ToolTexts.read(a) } }?.let { readA ->
            named(b) { withContext(io) { ToolTexts.read(b) } }?.let { readA to it }
        }
        val identical = bytes != null && LineDiff.sameBytes(bytes.first.first, bytes.first.second, bytes.second.first, bytes.second.second)
        if (identical) mutableState.update { it.copy(computing = false, identical = true) }
        if (bytes == null || identical) return null
        val (readA, readB) = bytes
        val linesA = named(a) { withContext(Dispatchers.Default) { TextLines.of(readA.first, readA.second) } }
        val linesB = linesA?.let { named(b) { withContext(Dispatchers.Default) { TextLines.of(readB.first, readB.second) } } }
        return if (linesA != null && linesB != null) linesA to linesB else null
    }

    /** [block]'s value, or null — the state saying why, with [file]'s name. */
    private suspend fun <T : Any> named(file: File, block: suspend () -> T): T? = loadOrFailure { block() }
        .onFailure { error -> mutableState.update { it.copy(computing = false, failure = readFailure(file.name, error)) } }
        .getOrNull()

    private companion object {
        const val KEY_A = "diff_a"
        const val KEY_B = "diff_b"
        const val KEY_PICKING = "diff_picking"
        const val KEY_LINE_ENDINGS = "diff_line_endings"
    }
}
