package com.readfilestech.read_files_tech.ui.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.recents.RecentFiles
import com.readfilestech.read_files_tech.data.storage.StorageAccess
import com.readfilestech.read_files_tech.data.storage.StorageVolumes
import com.readfilestech.read_files_tech.data.storage.VolumeRoot
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.explorer.PickedFiles
import com.readfilestech.read_files_tech.ui.explorer.Thumbnails
import com.readfilestech.read_files_tech.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.inject.Inject

data class PickerUiState(
    val loading: Boolean = true,
    val recents: List<FileEntry> = emptyList(),
    val shortcuts: List<PickerShortcut> = emptyList(),
    val folders: List<File> = emptyList(),
    val granted: Boolean = true,
    /**
     * Recents that may not leave the app (an output kept in its private fallback): no "Share", no other
     * app to open them — buttons that would only fail (inventory D46; Claude review R8).
     */
    val cannotLeave: Set<String> = emptySet(),
)

/** The picker: `RftPickerScreen.pickOne` / `pickMany` / `pickFolder` of the 2.15.3, its route's arguments. */
@HiltViewModel
class PickerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    recents: RecentFiles,
    outputs: OutputStorage,
    volumes: StorageVolumes,
    private val access: StorageAccess,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    val thumbnails: Thumbnails,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val sources = PickerSources(recents, outputs, volumes::primary) {
        volumes.list().filterNot(VolumeRoot::isPrimary)
    }

    val mode: PickMode = PickMode.of(savedState.get<String>(Routes.ARG_PICK)) ?: PickMode.FILE
    val extensions: Set<String>? = Routes.extensionsOf(savedState.get<String>(Routes.ARG_EXTENSIONS))
    val title: String? = savedState.get<String>(Routes.ARG_TITLE)

    private val state = MutableStateFlow(PickerUiState())
    val uiState: StateFlow<PickerUiState> = state.asStateFlow()

    /** The files chosen so far, choosing several: the explorer this screen opens to browse starts from them. */
    val picked = PickedFiles(savedState)

    /** Read again on each return: a file opened or made meanwhile shows. */
    fun reload() {
        viewModelScope.launch {
            val folderMode = mode == PickMode.FOLDER
            val loaded = withContext(io) {
                val shortcuts = sources.shortcuts(extensions, folderMode)
                val recents = if (folderMode) emptyList() else sources.recents(extensions)
                PickerUiState(
                    loading = false,
                    recents = recents,
                    shortcuts = shortcuts,
                    folders = sources.folders(shortcuts),
                    granted = access.isGranted(),
                    cannotLeave = recents.filterNot(launching::canLeave).mapTo(HashSet(), FileEntry::path),
                )
            }
            state.value = loaded
        }
    }

    fun remove(entry: FileEntry) {
        viewModelScope.launch {
            withContext(io) { sources.remove(entry.path) }
            messages.info(uiText(R.string.picker_removed))
            reload()
        }
    }

    /** "Files Tech" is created when opened, as the 2.15.x did; a folder that cannot be is still opened, empty. */
    fun prepare(shortcut: PickerShortcut, then: () -> Unit) {
        viewModelScope.launch {
            if (shortcut.kind == ShortcutKind.FILES_TECH) {
                withContext(io) {
                    try {
                        Files.createDirectories(shortcut.dir.toPath())
                    } catch (expected: IOException) {
                        // Refused (no access yet): the explorer shows why.
                    }
                }
            }
            then()
        }
    }
}
