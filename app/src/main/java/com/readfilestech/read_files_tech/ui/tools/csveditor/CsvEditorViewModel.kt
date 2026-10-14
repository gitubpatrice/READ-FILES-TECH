package com.readfilestech.read_files_tech.ui.tools.csveditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.editor.CellChange
import com.readfilestech.read_files_tech.data.editor.CsvEdits
import com.readfilestech.read_files_tech.data.editor.CsvSaving
import com.readfilestech.read_files_tech.data.editor.FileChangedException
import com.readfilestech.read_files_tech.data.editor.FileStamp
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.tools.readFailure
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The CSV editor's screen. [edits] is changed in place: [version] says when to read it again.
 *
 * @param saveFailure why the last save wrote nothing — said in the leave dialog, which stays (D3).
 * @param left saved from the leave dialog: the screen goes.
 */
data class CsvEditorState(
    val name: String = "",
    val loading: Boolean = false,
    val edits: CsvEdits? = null,
    val version: Int = 0,
    val modified: Boolean = false,
    val saving: Boolean = false,
    val failure: UiText? = null,
    val saveFailure: UiText? = null,
    val left: Boolean = false,
    val versions: List<HistoryCopy>? = null,
)

/**
 * `CsvEditorScreen` of the 2.15.3: a CSV chosen in the app's picker, or handed by the CSV viewer; its
 * cells, rows and columns changed in memory ([CsvEdits]), saved over the file ([CsvSaving]) — the version
 * replaced kept in the editor history (docs D-021), which can load one back.
 *
 * The 2.15.3's "Save" of the leave dialog left the screen whatever the save gave (D3,
 * `csv_editor_screen.dart:214-218`): here the screen goes only once the file is written.
 */
@HiltViewModel
class CsvEditorViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val history: EditorHistory,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CsvEditorState())
    val state: StateFlow<CsvEditorState> = mutableState.asStateFlow()

    private var file: File? = null
    private var stamp: FileStamp? = null

    /** A version of the history loaded: there is something to save, though no cell was typed. */
    private var restored = false

    init {
        (handle.get<String>(KEY_PATH) ?: handle.get<String>(Routes.ARG_PATH)?.takeIf(String::isNotEmpty))?.let(::open)
    }

    fun open(path: String) {
        handle[KEY_PATH] = path
        val chosen = File(path)
        file = chosen
        restored = false
        mutableState.value = CsvEditorState(name = chosen.name, loading = true)
        viewModelScope.launch {
            val result = loadOrFailure { withContext(io) { read(chosen) } }
            mutableState.update { current ->
                result.fold(
                    { current.copy(loading = false, edits = it, version = current.version + 1, modified = false) },
                    { current.copy(loading = false, failure = readFailure(chosen.name, it)) },
                )
            }
        }
    }

    /** The stamp first: a change while the file is read is a change since it was opened. */
    private suspend fun read(chosen: File): CsvEdits {
        stamp = FileStamp.of(chosen)
        return CsvEdits.read(StorageFileSource(chosen))
    }

    /**
     * A change to the edits — a cell typed, a row or a column inserted or deleted — and what it gave (the
     * cell's [CellChange], for its dialog to close or to say why not); null while the file is written.
     */
    fun <T> change(block: (CsvEdits) -> T): T? {
        val current = mutableState.value
        val edits = current.edits?.takeUnless { current.saving } ?: return null
        val result = block(edits)
        mutableState.value = current.copy(version = current.version + 1, modified = restored || edits.modified)
        return result
    }

    /** Writes the file; [thenLeave] from the leave dialog, which the screen leaves on success only. */
    fun save(thenLeave: Boolean = false) {
        val current = mutableState.value
        val edits = current.edits?.takeUnless { current.saving }
        val target = file
        val opened = stamp
        if (edits == null || target == null || opened == null) return
        mutableState.value = current.copy(saving = true, saveFailure = null)
        viewModelScope.launch {
            val error = loadOrFailure { withContext(io) { CsvSaving.save(target, edits, opened, history) } }.exceptionOrNull()
            if (error == null) saved(target, thenLeave) else failed(error, thenLeave)
        }
    }

    private suspend fun saved(target: File, thenLeave: Boolean) {
        restored = false
        messages.info(uiText(R.string.csv_editor_saved))
        // Read again what was written: the next save starts from the file as it is now.
        val reread = loadOrFailure { withContext(io) { read(target) } }
        mutableState.update { state ->
            val done = state.copy(saving = false, modified = false, left = thenLeave)
            reread.fold(
                { done.copy(edits = it, version = state.version + 1) },
                { done.copy(edits = null, failure = readFailure(target.name, it)) },
            )
        }
    }

    /** Nothing was written: said in the leave dialog when it asked, which stays (D3); otherwise below. */
    private fun failed(error: Throwable, thenLeave: Boolean) {
        val why = if (error is FileChangedException) uiText(R.string.csv_editor_changed) else IoErrors.reasonOf(error)
        val reason = uiText(R.string.csv_editor_save_failed, why)
        mutableState.update { it.copy(saving = false, saveFailure = reason) }
        if (!thenLeave) messages.error(reason)
    }

    /** The saved versions of the file, newest first, for the history dialog. */
    fun showHistory() {
        val target = file ?: return
        viewModelScope.launch {
            val versions = withContext(io) { history.versions(target.absolutePath) }
            mutableState.update { it.copy(versions = versions) }
        }
    }

    fun hideHistory() = mutableState.update { it.copy(versions = null) }

    /** Loads [copy] in the editor (D-021): nothing is written until the user saves. */
    fun restore(copy: HistoryCopy) {
        if (mutableState.value.saving) return
        mutableState.update { it.copy(versions = null) }
        viewModelScope.launch {
            val result = loadOrFailure { withContext(io) { CsvEdits.read(StorageFileSource(copy.file)) } }
            result.fold(
                { edits ->
                    restored = true
                    mutableState.update { it.copy(edits = edits, version = it.version + 1, modified = true) }
                    messages.info(uiText(R.string.csv_editor_restored))
                },
                { messages.error(readFailure(mutableState.value.name, it)) },
            )
        }
    }

    private companion object {
        const val KEY_PATH = "csv_editor_path"
    }
}
