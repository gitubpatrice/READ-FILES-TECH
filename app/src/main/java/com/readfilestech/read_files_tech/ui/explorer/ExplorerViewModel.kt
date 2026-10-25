package com.readfilestech.read_files_tech.ui.explorer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.RenameProposal
import com.readfilestech.read_files_tech.data.sharing.KnownApps
import com.readfilestech.read_files_tech.data.storage.StorageAccess
import com.readfilestech.read_files_tech.data.storage.StorageVolumes
import com.readfilestech.read_files_tech.ui.common.FileOperations
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.navigation.Routes
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

/** A dialog or sheet of the explorer; at most one at a time. */
internal sealed interface ExplorerDialog {
    data object NewFolder : ExplorerDialog

    data class Rename(val entry: FileEntry) : ExplorerDialog

    data class DeleteForever(val entries: List<FileEntry>) : ExplorerDialog

    data class Info(val entry: FileEntry) : ExplorerDialog

    data class Preview(val entry: FileEntry) : ExplorerDialog

    /** The selection's bulk rename (`FES:728`), over the explorer. */
    data class BulkRename(val entries: List<FileEntry>) : ExplorerDialog
}

/**
 * The explorer: `FileExplorerScreen` of the 2.15.x. [browser] goes from folder to folder, [launching]
 * hands files to other apps; this selects, and sends the changes to [FileOperations], which finishes
 * them even if the user leaves.
 */
// One function per action of the explorer's bar and menus: the screen's surface, not a class doing too much.
@Suppress("TooManyFunctions")
@HiltViewModel
class ExplorerViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    access: StorageAccess,
    volumes: StorageVolumes,
    private val operations: FileOperations,
    val launching: EntryLaunching,
    val thumbnails: Thumbnails,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(
        ExplorerUiState(
            pick = PickMode.of(savedState.get<String>(Routes.ARG_PICK)),
            extensions = Routes.extensionsOf(savedState.get<String>(Routes.ARG_EXTENSIONS)),
            title = savedState.get<String>(Routes.ARG_TITLE),
        ),
    )
    val uiState: StateFlow<ExplorerUiState> = state.asStateFlow()

    private val shownDialog = MutableStateFlow<ExplorerDialog?>(null)

    /** The dialog or sheet on top, kept across a rotation: held by the screen, it went with the activity (coherence audit, phase 9). */
    internal val dialog: StateFlow<ExplorerDialog?> = shownDialog.asStateFlow()

    /** The files chosen in [PickMode.FILES], across folders: kept across a process death, as a transfer is. */
    val picked = PickedFiles(savedState)

    val browser = FolderBrowser(viewModelScope, state, access::isGranted, volumes::list, io)

    init {
        browser.open(savedState.get<String>(Routes.ARG_PATH)?.let(::File) ?: volumes.primary(), remember = false)
        viewModelScope.launch { operations.changes.collect { browser.refresh() } }
        viewModelScope.launch {
            // Once per screen: an entry that always failed ("send to kDrive" without kDrive) is not offered.
            val installed = withContext(io) {
                listOf(KnownApps.KDRIVE, KnownApps.PROTON_DRIVE, KnownApps.PDF_TECH).associateWith(launching::isInstalled)
            }
            val apps = InstalledApps(
                kDrive = installed.getValue(KnownApps.KDRIVE),
                proton = installed.getValue(KnownApps.PROTON_DRIVE),
                pdfTech = installed.getValue(KnownApps.PDF_TECH),
            )
            state.update { it.copy(installed = apps) }
        }
    }

    fun toggleSelection(entry: FileEntry) = state.update { it.toggled(entry) }

    fun selectAll() = state.update { it.allSelected() }

    fun clearSelection() = state.update { it.copy(selection = emptySet()) }

    /** "Copy the path" of the info dialog, said as every other copy is: below Android 13 nothing else says it. */
    fun copied(path: String) = messages.info(uiText(R.string.value_copied, path))

    /** Shows [dialog] over the explorer, or closes the one shown (null). */
    internal fun show(dialog: ExplorerDialog?) {
        shownDialog.value = dialog
    }

    fun createFolder(name: String) {
        state.value.dir?.let { operations.createFolder(it, name.trim()) }
    }

    fun rename(entry: FileEntry, newName: String) = operations.rename(entry.file, newName.trim())

    fun moveToTrash(entries: List<FileEntry>) {
        clearSelection()
        operations.moveToTrash(entries.map(FileEntry::file))
    }

    /** The bulk rename's batch, its names checked by its preview; the explorer's operations finish it. */
    fun renameAll(proposals: List<RenameProposal>) {
        clearSelection()
        operations.renameAll(proposals)
    }

    fun deleteForever(entries: List<FileEntry>) {
        clearSelection()
        operations.deleteForever(entries.map(FileEntry::file))
    }

    /**
     * Copy or move to a folder the user will now choose: remembered in the saved state, so a process
     * death while choosing loses nothing.
     */
    fun startTransfer(entries: List<FileEntry>, move: Boolean) {
        savedState[KEY_TRANSFER_MOVE] = move
        savedState[KEY_TRANSFER_PATHS] = ArrayList(entries.map(FileEntry::path))
    }

    /**
     * The destination chosen for [startTransfer]. It arrives through the NAVIGATION entry's saved state,
     * which is not this view model's own (each view model gets its own handle) — the screen hands it
     * over. Measured on the emulator: read here, the result never arrived, and the copy never ran.
     */
    fun onFolderPicked(folder: String) {
        val paths = savedState.get<ArrayList<String>>(KEY_TRANSFER_PATHS) ?: return
        val move = savedState.get<Boolean>(KEY_TRANSFER_MOVE) ?: false
        savedState[KEY_TRANSFER_PATHS] = null
        clearSelection()
        operations.transfer(paths.map(::File), File(folder), move)
    }

    private companion object {
        const val KEY_TRANSFER_MOVE = "transfer_move"
        const val KEY_TRANSFER_PATHS = "transfer_paths"
    }
}
