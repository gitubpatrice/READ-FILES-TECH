package com.readfilestech.read_files_tech.ui.explorer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.sharing.KnownApps
import com.readfilestech.read_files_tech.data.storage.StorageAccess
import com.readfilestech.read_files_tech.data.storage.StorageVolumes
import com.readfilestech.read_files_tech.ui.common.FileOperations
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
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

/**
 * The explorer: `FileExplorerScreen` of the 2.15.x. [browser] goes from folder to folder, [launching]
 * hands files to other apps; this selects, and sends the changes to [FileOperations], which finishes
 * them even if the user leaves.
 */
@HiltViewModel
class ExplorerViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    access: StorageAccess,
    volumes: StorageVolumes,
    private val operations: FileOperations,
    val launching: EntryLaunching,
    val thumbnails: Thumbnails,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(ExplorerUiState(pickFolder = savedState.get<String>(Routes.ARG_PICK) == Routes.PICK_FOLDER))
    val uiState: StateFlow<ExplorerUiState> = state.asStateFlow()

    val browser = FolderBrowser(viewModelScope, state, access::isGranted, volumes::list, io)

    init {
        browser.open(savedState.get<String>(Routes.ARG_PATH)?.let(::File) ?: volumes.primary(), remember = false)
        viewModelScope.launch { operations.changes.collect { browser.refresh() } }
        viewModelScope.launch {
            // Once per screen: an entry that always failed ("send to kDrive" without kDrive) is not offered.
            val (kDrive, proton) = withContext(io) {
                launching.isInstalled(KnownApps.KDRIVE) to launching.isInstalled(KnownApps.PROTON_DRIVE)
            }
            state.update { it.copy(kDriveInstalled = kDrive, protonInstalled = proton) }
        }
    }

    fun toggleSelection(entry: FileEntry) = state.update { it.toggled(entry) }

    fun selectAll() = state.update { it.allSelected() }

    fun clearSelection() = state.update { it.copy(selection = emptySet()) }

    fun createFolder(name: String) {
        state.value.dir?.let { operations.createFolder(it, name.trim()) }
    }

    fun rename(entry: FileEntry, newName: String) = operations.rename(entry.file, newName.trim())

    fun moveToTrash(entries: List<FileEntry>) {
        clearSelection()
        operations.moveToTrash(entries.map(FileEntry::file))
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
