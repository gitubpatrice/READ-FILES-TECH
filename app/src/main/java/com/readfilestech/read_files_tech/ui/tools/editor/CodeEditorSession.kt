package com.readfilestech.read_files_tech.ui.tools.editor

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.TextPreview
import com.readfilestech.read_files_tech.data.editor.EditedFile
import com.readfilestech.read_files_tech.data.editor.NotEditableException
import com.readfilestech.read_files_tech.data.editor.SaveOutcome
import com.readfilestech.read_files_tech.data.editor.TextTooLargeException
import com.readfilestech.read_files_tech.data.editor.UnencodableTextException
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.readFailure
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/** The answers of the leave dialog (`_onWillPop` of the 2.15.3). */
enum class LeaveChoice {
    /** Leave without saving. */
    DISCARD,

    /** Stay on the screen, the text as it is. */
    CANCEL,

    /** Save, and leave once the file is written — only then (D3). */
    SAVE,
}

private const val MIB = 1024L * 1024

/** The saved versions of the open file, newest first: its own (docs D-021), and apart the 2.15.x's of its exact name. */
data class EditorVersions(val versions: List<HistoryCopy>, val legacy: List<HistoryCopy>)

/** A saved version shown before it is restored: its first lines (D-021's preview). */
data class VersionPreview(val copy: HistoryCopy, val legacy: Boolean, val lines: List<String>)

/**
 * The code editor's screen.
 *
 * @param failure why the file did not open
 * @param leaving the leave dialog is shown
 * @param changedOnDisk the file changed since it was read: overwrite it? asked before anything is written
 * @param leaveAfterSave the save asked from the leave dialog, which leaves once the file is written
 * @param saveFailure why the last save wrote nothing, said in the dialog that asked for it, which stays (D3)
 * @param left the screen goes
 */
data class CodeEditorState(
    val name: String = "",
    val loading: Boolean = false,
    val failure: UiText? = null,
    val encoding: TextEncoding? = null,
    val modified: Boolean = false,
    val saving: Boolean = false,
    val leaving: Boolean = false,
    val changedOnDisk: Boolean = false,
    val leaveAfterSave: Boolean = false,
    val saveFailure: UiText? = null,
    val left: Boolean = false,
    val versions: EditorVersions? = null,
    val preview: VersionPreview? = null,
) {
    val isOpen: Boolean get() = encoding != null
}

/**
 * What the code editor does, apart from the text field (`CodeEditorScreen` of the 2.15.3): open a file,
 * save it, leave, the history. Free of Android, so that its rules are tested on the JVM — the 2.15.3's
 * leave dialog was tested on a replica (`editor_leave_dialog_test.dart`), and its "Save" left the screen
 * even when the save failed (D3, `code_editor_screen.dart:280-285`).
 *
 * Called from one thread (the screen's); the files are read and written on [io].
 */
@Suppress("TooManyFunctions") // one per action of the screen: split, the rules shared by them would be passed around
class CodeEditorSession(
    private val history: EditorHistory,
    private val messages: UserMessages,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(CodeEditorState())
    val state: StateFlow<CodeEditorState> = mutableState.asStateFlow()

    private var opened: EditedFile? = null

    /** The file open, for sharing it. */
    val file: File? get() = opened?.file

    /** Opens [file]: its text, for the text field, or null when it did not open — said in the state. */
    suspend fun open(file: File): String? {
        opened = null
        mutableState.value = CodeEditorState(name = file.name, loading = true)
        val result = loadOrFailure { withContext(io) { EditedFile.open(file) } }
        return result.fold(
            { edited ->
                opened = edited
                mutableState.update { it.copy(loading = false, encoding = edited.content.encoding) }
                edited.text
            },
            { error ->
                mutableState.update { it.copy(loading = false, failure = openFailure(file.name, error)) }
                null
            },
        )
    }

    /** The text field changed: "modified" while it is not what the file holds. */
    fun textChanged(current: CharSequence) {
        val edited = opened ?: return
        val modified = edited.isModified(current)
        if (modified != mutableState.value.modified) mutableState.update { it.copy(modified = modified) }
    }

    /** Back: leaves at once when everything is saved, asks otherwise. */
    fun askToLeave() {
        if (mutableState.value.modified) {
            mutableState.update { it.copy(leaving = true, saveFailure = null) }
        } else {
            mutableState.update { it.copy(left = true) }
        }
    }

    /** The leave dialog's [choice]; [text] is the editor's, saved on [LeaveChoice.SAVE]. */
    suspend fun leave(choice: LeaveChoice, text: String) {
        if (mutableState.value.saving) return
        when (choice) {
            LeaveChoice.DISCARD -> mutableState.update { it.copy(leaving = false, left = true) }
            LeaveChoice.CANCEL -> mutableState.update { it.copy(leaving = false, saveFailure = null) }
            LeaveChoice.SAVE -> save(text, thenLeave = true)
        }
    }

    /**
     * Saves [text] over the file ([EditedFile.save]). [thenLeave]: the screen goes once the file is written,
     * and stays otherwise, the reason said where the save was asked (D3). [overwrite]: the user answered
     * that the file may be written though it changed since it was read.
     */
    suspend fun save(text: String, thenLeave: Boolean = false, overwrite: Boolean = false) {
        val edited = opened ?: return
        if (mutableState.value.saving) return
        mutableState.update { it.copy(saving = true, saveFailure = null) }
        val result = loadOrFailure { withContext(io) { edited.save(text, history, overwrite) } }
        result.fold(
            { outcome ->
                when (outcome) {
                    is SaveOutcome.Saved -> saved(outcome.file, thenLeave)
                    SaveOutcome.ChangedOnDisk ->
                        mutableState.update { it.copy(saving = false, changedOnDisk = true, leaveAfterSave = thenLeave) }
                }
            },
            { error -> failed(error) },
        )
    }

    /** "Overwrite" in the dialog saying the file changed: the save it stopped, again, the file's version kept first. */
    suspend fun overwrite(text: String) = save(text, thenLeave = mutableState.value.leaveAfterSave, overwrite = true)

    /** "Cancel" in that dialog: nothing written; the leave dialog, when it asked, is back. */
    fun keepOnDisk() = mutableState.update { it.copy(changedOnDisk = false, saveFailure = null) }

    private fun saved(file: EditedFile, thenLeave: Boolean) {
        opened = file
        mutableState.update {
            it.copy(
                saving = false,
                modified = false,
                changedOnDisk = false,
                leaving = it.leaving && !thenLeave,
                left = thenLeave,
            )
        }
        messages.info(uiText(R.string.editor_saved))
    }

    /** Nothing written: said in the dialog that asked, which stays (D3); below the screen otherwise. */
    private fun failed(error: Throwable) {
        val current = mutableState.value
        val reason = uiText(R.string.editor_save_failed, saveReason(current.name, error))
        mutableState.update { it.copy(saving = false, saveFailure = reason) }
        if (!current.leaving && !current.changedOnDisk) messages.error(reason)
    }

    /** The open file's saved versions, for the history dialog. */
    suspend fun showHistory() {
        val file = opened?.file ?: return
        val versions = withContext(io) { EditorVersions(history.versions(file.absolutePath), history.legacyFor(file.name)) }
        mutableState.update { it.copy(versions = versions) }
    }

    fun hideHistory() = mutableState.update { it.copy(versions = null, preview = null) }

    /** The first lines of [copy], before restoring it. */
    suspend fun preview(copy: HistoryCopy, legacy: Boolean) {
        val lines = loadOrFailure { withContext(io) { TextPreview.firstLines(copy.file) } }
        lines.fold(
            { mutableState.update { state -> state.copy(preview = VersionPreview(copy, legacy, it)) } },
            { messages.error(readFailure(mutableState.value.name, it)) },
        )
    }

    fun hidePreview() = mutableState.update { it.copy(preview = null) }

    /**
     * Loads [copy] in the editor (docs D-021): nothing is written to the file until the user saves. Its
     * text is returned for the text field; [current], the editor's text, goes to the history first when it
     * is not saved — restoring is undone by restoring it. Null when nothing changes, the reason said.
     *
     * The screen is held meanwhile, as by a save ([CodeEditorState.saving]): what was typed during the
     * reads and writes was replaced and kept nowhere, and a save asked then ended "saved" with the version
     * restored shown, unsaved (Claude review R22).
     */
    suspend fun restore(copy: HistoryCopy, current: String): String? {
        val edited = opened?.takeUnless { mutableState.value.saving } ?: return null
        mutableState.update { it.copy(versions = null, preview = null, saving = true) }
        val result = loadOrFailure {
            withContext(io) {
                val text = EditedFile.textOf(copy.file)
                if (edited.isModified(current)) edited.keep(current, history)
                text
            }
        }
        mutableState.update { it.copy(saving = false) }
        return result.fold(
            {
                messages.info(uiText(R.string.editor_restored))
                it
            },
            {
                messages.error(uiText(R.string.editor_restore_failed, restoreReason(it)))
                null
            },
        )
    }

    private fun restoreReason(error: Throwable): UiText = when (error) {
        is UnencodableTextException, is TextTooLargeException -> saveReason(mutableState.value.name, error)
        is NotEditableException -> uiText(R.string.editor_version_not_text)
        is FileTooLargeException -> readFailure(mutableState.value.name, error)
        else -> IoErrors.reasonOf(error)
    }

    companion object {
        /** Why [name] did not open, in words: not text, not written back as read, too large, the system's reason. */
        fun openFailure(name: String, error: Throwable): UiText = when {
            error is NotEditableException && error.reason == NotEditableException.Reason.NOT_TEXT ->
                uiText(R.string.editor_not_text, name)
            error is NotEditableException -> uiText(R.string.editor_not_reversible, name)
            else -> readFailure(name, error)
        }

        /** Why a save of [name] wrote nothing. */
        fun saveReason(name: String, error: Throwable): UiText = when (error) {
            is UnencodableTextException -> uiText(R.string.editor_unencodable, encodingName(error.encoding), error.character)
            is TextTooLargeException -> uiText(R.string.editor_text_too_large, error.capBytes / MIB)
            is FileTooLargeException -> readFailure(name, error)
            else -> IoErrors.reasonOf(error)
        }

        fun encodingName(encoding: TextEncoding): UiText = uiText(
            when (encoding) {
                TextEncoding.UTF_8 -> R.string.encoding_utf8
                TextEncoding.UTF_8_BOM -> R.string.encoding_utf8_bom
                TextEncoding.UTF_16LE -> R.string.encoding_utf16le
                TextEncoding.UTF_16BE -> R.string.encoding_utf16be
                TextEncoding.WINDOWS_1252 -> R.string.encoding_windows_1252
            },
        )
    }
}
