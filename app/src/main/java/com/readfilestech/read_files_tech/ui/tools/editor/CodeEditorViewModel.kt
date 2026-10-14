package com.readfilestech.read_files_tech.ui.tools.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * `CodeEditorScreen` of the 2.15.3: a text file chosen in the app's picker, or handed by the explorer or
 * the picker's "Edit" with the route; its rules in [CodeEditorSession].
 *
 * The text lives here, in a [TextFieldState]: it outlives a rotation, and its undo history with it. It
 * is not in the saved state — up to [com.readfilestech.read_files_tech.data.files.FileCaps.CODE_EDITOR]
 * would overflow it —, so a process death loses what was not saved, as in the 2.15.3; the file's path
 * is kept, and the file read again.
 */
@Suppress("TooManyFunctions") // one per action of the screen, each handing it to the session
@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    history: EditorHistory,
    messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val text = TextFieldState()

    private val session = CodeEditorSession(history, messages, io)
    val state: StateFlow<CodeEditorState> = session.state

    private val leavable = MutableStateFlow<FileEntry?>(null)

    /** The open file, when it may be handed to another app (PathGuard); null otherwise. */
    val shareable: StateFlow<FileEntry?> = leavable.asStateFlow()

    private var opening: Job? = null

    init {
        (handle.get<String>(KEY_PATH) ?: handle.get<String>(Routes.ARG_PATH)?.takeIf(String::isNotEmpty))?.let(::open)
        viewModelScope.launch { snapshotFlow { text.text }.collect(session::textChanged) }
    }

    // The text field's undo history (UndoState) is still marked experimental.
    @OptIn(ExperimentalFoundationApi::class)
    fun open(path: String) {
        handle[KEY_PATH] = path
        opening?.cancel()
        opening = viewModelScope.launch {
            val file = File(path)
            leavable.value = null
            val initial = session.open(file) ?: return@launch
            replaceText(initial)
            // Undo goes back to the file, never to an empty field.
            text.undoState.clearHistory()
            leavable.value = withContext(io) { DirectoryListing.entryOf(file).takeIf(launching::canLeave) }
        }
    }

    fun save() {
        viewModelScope.launch { session.save(text.text.toString()) }
    }

    fun askToLeave() = session.askToLeave()

    fun leave(choice: LeaveChoice) {
        viewModelScope.launch { session.leave(choice, text.text.toString()) }
    }

    fun overwrite() {
        viewModelScope.launch { session.overwrite(text.text.toString()) }
    }

    fun keepOnDisk() = session.keepOnDisk()

    fun showHistory() {
        viewModelScope.launch { session.showHistory() }
    }

    fun hideHistory() = session.hideHistory()

    fun preview(copy: HistoryCopy, legacy: Boolean) {
        viewModelScope.launch { session.preview(copy, legacy) }
    }

    fun hidePreview() = session.hidePreview()

    fun restore(copy: HistoryCopy) {
        viewModelScope.launch { session.restore(copy, text.text.toString())?.let(::replaceText) }
    }

    /** The whole text replaced by the app, the cursor at its start — not typed, so not through the field's filter. */
    private fun replaceText(value: String) {
        text.edit {
            replace(0, length, value)
            placeCursorBeforeCharAt(0)
        }
    }

    private companion object {
        const val KEY_PATH = "code_editor_path"
    }
}
