package com.readfilestech.read_files_tech.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
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

/** The way out of a viewer to other apps: share and "Open with…", for a file that may leave the app. */
@HiltViewModel
class ViewerActionsViewModel @Inject constructor(
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
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
