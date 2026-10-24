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
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.files.MIB
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
 * @param saving the file being written, or the cells not saved into the history before a restore: nothing
 *   changes meanwhile.
 * @param saveFailure why the last save wrote nothing — said in the leave dialog, which stays (D3).
 * @param left saved from the leave dialog: the screen goes.
 * @param askOverCap a save waiting for the user's answer: the file is over the history's cap — this many MB
 *   ([FileCaps.EDITOR_HISTORY_COPY]) —, and the version it replaces cannot be kept.
 * @param changedOnDisk a save found the file changed by another app: overwrite it? asked, nothing written yet —
 *   the 2.15.3 said "not saved" and left the edits stuck; [changedKept], its version on disk fits the history.
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
    val askOverCap: Int? = null,
    val changedOnDisk: Boolean = false,
    val changedKept: Boolean = true,
)

/**
 * `CsvEditorScreen` of the 2.15.3: a CSV chosen in the app's picker, or handed by the CSV viewer; its
 * cells, rows and columns changed in memory ([CsvEdits]), saved over the file ([CsvSaving]) — the version
 * replaced kept in the editor history (docs D-021), which can load one back.
 *
 * The 2.15.3's "Save" of the leave dialog left the screen whatever the save gave (D3,
 * `csv_editor_screen.dart:214-218`): here the screen goes only once the file is written.
 */
// One function per action of the editor's bar and dialogs: the screen's surface, not a class doing too much.
@Suppress("TooManyFunctions")
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

    /** The file as the edits shown were read from it: read with them, set with them, never apart (Claude review R20). */
    private var stamp: FileStamp? = null

    /** Each load — open, read again after a save, restore — takes the next number: only the last one lands (R20). */
    private var loads = 0

    /** A version of the history loaded: there is something to save, though no cell was typed. */
    private var restored = false

    /** The save [askOverCap] holds: from the leave dialog or not. */
    private var askedThenLeave = false

    init {
        (handle.get<String>(KEY_PATH) ?: handle.get<String>(Routes.ARG_PATH)?.takeIf(String::isNotEmpty))?.let(::open)
    }

    /** Opens [path] — never while a file is being written. A load still running for another file is dropped. */
    fun open(path: String) {
        if (mutableState.value.saving) return
        val serial = ++loads
        // Another file, another session: its own answer. The same one (restored after a process death) keeps it.
        if (handle.get<String>(KEY_PATH) != path) handle[KEY_SAVE_UNCOPIED] = null
        handle[KEY_PATH] = path
        val chosen = File(path)
        file = chosen
        stamp = null
        restored = false
        mutableState.value = CsvEditorState(name = chosen.name, loading = true)
        viewModelScope.launch {
            val result = loadOrFailure { withContext(io) { read(chosen) } }
            if (serial != loads) return@launch
            stamp = result.getOrNull()?.second
            mutableState.update { current ->
                result.fold(
                    { current.copy(loading = false, edits = it.first, version = current.version + 1, modified = false) },
                    { current.copy(loading = false, failure = readFailure(chosen.name, it)) },
                )
            }
        }
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

    /**
     * Writes the file; [thenLeave] from the leave dialog, which the screen leaves on success only. The first
     * save of a file over the history's cap asks first ([askOverCap]): the version it replaces cannot be kept,
     * which the save did without a word.
     */
    fun save(thenLeave: Boolean = false) {
        val current = mutableState.value
        val edits = current.edits?.takeUnless { current.saving }
        val target = file
        val opened = stamp
        if (edits == null || target == null || opened == null) return
        if (!CsvSaving.keepsCopy(opened) && handle.get<String>(KEY_SAVE_UNCOPIED) != target.path) {
            askedThenLeave = thenLeave
            mutableState.value = current.copy(askOverCap = (FileCaps.EDITOR_HISTORY_COPY / MIB).toInt())
            return
        }
        write(thenLeave, overwrite = false)
    }

    /** "Overwrite" of [CsvEditorState.changedOnDisk]: the edits over the file as it is now, its version kept first when it fits. */
    fun overwrite() {
        // The dialog stays while it writes, and says it if that fails — as the code editor's (GPT review, phase 8):
        // closed first, its "saving" never showed and a failure went below the screen.
        if (mutableState.value.changedOnDisk) write(askedThenLeave, overwrite = true)
    }

    /** "Cancel" of [CsvEditorState.changedOnDisk]: nothing written, the edits kept on screen. */
    fun keepOnDisk() = mutableState.update { it.copy(changedOnDisk = false, saveFailure = null) }

    private fun write(thenLeave: Boolean, overwrite: Boolean) {
        val current = mutableState.value
        val edits = current.edits?.takeUnless { current.saving }
        val target = file
        val opened = stamp
        if (edits == null || target == null || opened == null) return
        // A restore still being read lands no more: the save writes what is shown (Claude review R21).
        ++loads
        mutableState.value = current.copy(saving = true, saveFailure = null)
        viewModelScope.launch {
            val error = loadOrFailure {
                withContext(io) {
                    if (overwrite) {
                        CsvSaving.overwrite(target, history, edits::writeTo)
                    } else {
                        CsvSaving.save(target, opened, history, edits::writeTo)
                    }
                }
            }.exceptionOrNull()
            if (error == null) saved(target, thenLeave, edits) else failed(target, error, thenLeave)
        }
    }

    /**
     * The answer to [CsvEditorState.askOverCap]. "Save anyway" ([saveAnyway]): the save asked about goes on, and
     * the answer is kept for the rest of this file's session — in the saved state, so a process death does not
     * ask again. "Cancel": nothing written, and the next save asks again.
     */
    fun answerOverCap(saveAnyway: Boolean) {
        val target = file
        if (target == null || mutableState.value.askOverCap == null) return
        mutableState.update { it.copy(askOverCap = null) }
        if (saveAnyway) {
            handle[KEY_SAVE_UNCOPIED] = target.path
            save(askedThenLeave)
        }
    }

    private suspend fun saved(target: File, thenLeave: Boolean, written: CsvEdits) {
        restored = false
        messages.info(uiText(R.string.editor_saved))
        // Read again what was written: the next save starts from the file as it is now.
        val serial = ++loads
        val reread = loadOrFailure { withContext(io) { read(target) } }
        val landed = serial == loads
        if (landed) stamp = reread.getOrNull()?.second
        reread.getOrNull()?.first?.let { keepChoice(written, it) }
        mutableState.update { state ->
            val done = state.copy(saving = false, modified = false, changedOnDisk = false, left = thenLeave)
            when {
                !landed -> state.copy(saving = false, changedOnDisk = false)
                else -> reread.fold(
                    { done.copy(edits = it.first, version = state.version + 1) },
                    { done.copy(edits = null, failure = readFailure(target.name, it)) },
                )
            }
        }
    }

    /**
     * Nothing was written: said in the leave dialog when it asked, which stays (D3); otherwise below. A file another
     * app changed is asked about instead ([CsvEditorState.changedOnDisk]), over the leave dialog when it asked.
     */
    private suspend fun failed(target: File, error: Throwable, thenLeave: Boolean) {
        if (error is FileChangedException) {
            askedThenLeave = thenLeave
            val kept = withContext(io) { CsvSaving.keepsCurrent(target) }
            mutableState.update { it.copy(saving = false, changedOnDisk = true, changedKept = kept) }
            return
        }
        val why = IoErrors.reasonOf(error)
        val reason = uiText(R.string.editor_save_failed, why)
        val asked = mutableState.value.changedOnDisk
        mutableState.update { it.copy(saving = false, saveFailure = reason) }
        // Said in the dialog that asked — leave or overwrite —, which stays; below the screen otherwise.
        if (!thenLeave && !asked) messages.error(reason)
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

    /**
     * Loads [copy] in the editor (D-021): nothing is written to the file until the user saves. Cells typed
     * and not saved go to the history first, as in the code editor — the screen held while they are
     * written: restoring is undone by restoring them; when they cannot be kept, nothing is restored.
     *
     * Never while a file is written or opened: it would drop the open's result, the screen left loading,
     * with no stamp to save (Claude review R21).
     */
    fun restore(copy: HistoryCopy) {
        val current = mutableState.value
        val target = file
        if (current.saving || current.loading || target == null) return
        val serial = ++loads
        val previous = current.edits
        val unsaved = previous?.takeIf { it.modified }
        mutableState.value = current.copy(versions = null, saving = unsaved != null)
        viewModelScope.launch {
            val read = loadOrFailure { withContext(io) { CsvEdits.read(StorageFileSource(copy.file)) } }
            val kept = if (unsaved == null || read.isFailure) {
                Result.success(Unit)
            } else {
                loadOrFailure { withContext(io) { CsvSaving.keep(target, history, unsaved::writeTo) } }
            }
            if (unsaved != null) mutableState.update { it.copy(saving = false) }
            if (serial != loads) return@launch
            read.fold(
                { edits -> kept.fold({ restored(edits, previous) }, { messages.error(notKept(it)) }) },
                { messages.error(readFailure(mutableState.value.name, it)) },
            )
        }
    }

    private fun restored(edits: CsvEdits, previous: CsvEdits?) {
        previous?.let { keepChoice(it, edits) }
        restored = true
        mutableState.update { it.copy(edits = edits, version = it.version + 1, modified = true) }
        messages.info(uiText(R.string.editor_restored))
    }

    private companion object {
        const val KEY_PATH = "csv_editor_path"

        /** The file whose saves over the history's cap the user accepted, without a copy of what they replace. */
        const val KEY_SAVE_UNCOPIED = "csv_editor_save_uncopied"
    }
}

/**
 * The edits of [chosen] and its stamp, read together; the stamp first: a change while it is read is a change.
 * Out of the view model, which needs nothing of it, and stays within detekt's count of functions.
 */
private suspend fun read(chosen: File): Pair<CsvEdits, FileStamp> {
    val opened = FileStamp.of(chosen)
    return CsvEdits.read(StorageFileSource(chosen)) to opened
}

/** The encoding the user chose for the file, kept while it is open — read again after a save, restored. */
private fun keepChoice(from: CsvEdits, to: CsvEdits) {
    from.chosenEncoding?.takeIf { it in to.encodingChoices }?.let { to.chosenEncoding = it }
}

/** Why a restore did not happen: the cells not saved could not be kept first. */
private fun notKept(error: Throwable): UiText {
    val why = when (error) {
        is FileTooLargeException -> uiText(R.string.csv_editor_unsaved_too_large, (error.capBytes / MIB).toInt())
        else -> IoErrors.reasonOf(error)
    }
    return uiText(R.string.editor_restore_failed, why)
}
