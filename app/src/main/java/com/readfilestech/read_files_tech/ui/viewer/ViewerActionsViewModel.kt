package com.readfilestech.read_files_tech.ui.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.vault.VaultSession
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
 * The way out of a viewer to other apps — share and "Open with…", for a file that may leave the app — and
 * what a viewer of a vault file is bound to: the vault open.
 */
@HiltViewModel
class ViewerActionsViewModel @Inject constructor(
    handle: SavedStateHandle,
    session: VaultSession,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    /** The route is a vault file's (docs D-028): its viewer is secured, and leaves when the vault locks. */
    val inVault: Boolean = ViewerSources.isVault(handle)

    /** Whether the vault is open; a vault file's viewer shows nothing the moment it is not. */
    val vaultOpen: StateFlow<Boolean> = session.unlocked

    private val leaving = MutableStateFlow<FileEntry?>(null)

    /** The file the viewer shows, when it may be handed to another app (PathGuard); null otherwise. */
    val leavable: StateFlow<FileEntry?> = leaving.asStateFlow()

    private var loading: Job? = null

    /** The last file asked wins: a gallery asks at each page, and an earlier answer must not land after it. */
    fun load(file: File?) {
        if (file == null) return
        loading?.cancel()
        loading = viewModelScope.launch {
            leaving.value = withContext(io) {
                file.takeIf(File::isFile)?.let(DirectoryListing::entryOf)?.takeIf(launching::canLeave)
            }
        }
    }
}
