package com.readfilestech.read_files_tech.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.recents.RecentFile
import com.readfilestech.read_files_tech.data.recents.RecentFiles
import com.readfilestech.read_files_tech.data.storage.StorageSpace
import com.readfilestech.read_files_tech.data.storage.StorageVolumes
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.theme.ThemeMode
import com.readfilestech.read_files_tech.ui.theme.ThemeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class HomeUiState(
    val loaded: Boolean = false,
    val space: StorageSpace? = null,
    /** The most recent first; favourites among them, flagged. */
    val recents: List<RecentFile> = emptyList(),
    val quickAccess: List<QuickAccess> = emptyList(),
) {
    val favorites: List<RecentFile> get() = recents.filter(RecentFile::isFavorite)
    val others: List<RecentFile> get() = recents.filterNot(RecentFile::isFavorite)
}

/**
 * The home (`HomeScreen` of the 2.15.3). Everything is read AGAIN on each return and after each change:
 * the 2.15.x loaded the recents once and wrote that stale list back at every open, favourite or removal
 * — erasing what the tools had recorded since, and undoing even the panic wipe (inventory D42, D43).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recents: RecentFiles,
    private val volumes: StorageVolumes,
    private val messages: UserMessages,
    val themes: ThemeSettings,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = state.asStateFlow()

    fun reload() {
        viewModelScope.launch {
            state.value = withContext(io) {
                HomeUiState(
                    loaded = true,
                    space = volumes.primarySpace(),
                    recents = recents.load(),
                    quickAccess = HomeLogic.quickAccess(volumes.primary()),
                )
            }
        }
    }

    fun toggleFavorite(file: RecentFile) = change { recents.toggleFavorite(file.path) }

    /**
     * Removed from the home AND from the picker's list of outputs, until opened again: the 2.15.x's home
     * removal let an output come back through the picker (inventory F27).
     */
    fun remove(file: RecentFile) = change { recents.dismiss(file.path) }

    /**
     * Opens [path] if it is still there — the 2.15.x pushed a viewer onto a missing file (F26) — through
     * [then], then records it as opened: AFTER, so a format nothing opens is not recorded (F17).
     */
    fun open(path: String, then: (FileEntry) -> Unit) {
        viewModelScope.launch {
            val entry = withContext(io) { File(path).takeIf(File::isFile)?.let(DirectoryListing::entryOf) }
            if (entry == null) {
                messages.error(uiText(R.string.error_not_found))
                reload()
                return@launch
            }
            then(entry)
            change { recents.addOrUpdate(path) }
        }
    }

    /** Applied at once, written off the main thread (a synchronous commit). */
    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch(io) { themes.set(mode) }
    }

    private fun change(write: () -> Unit) {
        viewModelScope.launch {
            withContext(io) { write() }
            reload()
        }
    }
}
