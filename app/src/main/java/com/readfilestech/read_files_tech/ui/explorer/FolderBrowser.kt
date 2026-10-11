package com.readfilestech.read_files_tech.ui.explorer

import android.annotation.SuppressLint
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.SortMode
import com.readfilestech.read_files_tech.data.storage.VolumeRoot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Going from folder to folder: listing off the main thread, the explorer's own history (the arrow AND
 * the system "back" go up it — in the 2.15.x the system key left the explorer), the name filter.
 *
 * @param granted whether the app sees all of shared storage now.
 * @param mounted the volumes mounted now.
 */
@OptIn(FlowPreview::class)
class FolderBrowser(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ExplorerUiState>,
    private val granted: () -> Boolean,
    private val mounted: () -> List<VolumeRoot>,
    private val io: CoroutineDispatcher,
) {
    private val history = ArrayDeque<File>()
    private val query = MutableStateFlow("")
    private var listing: Job? = null

    init {
        scope.launch {
            // 150 ms, as the 2.15.x: typing in a folder of thousands of entries refilters once, not per key.
            query.drop(1).debounce(SEARCH_DEBOUNCE_MS).collect { text -> state.update { it.copy(query = text).refiltered() } }
        }
    }

    fun open(dir: File, remember: Boolean = true) {
        val current = state.value.dir
        if (remember && current != null && current != dir) history.addLast(current)
        load(dir)
    }

    /** Back up the explorer's own history; false when there is nowhere to go back to. */
    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        load(previous)
        return true
    }

    fun refresh() {
        state.value.dir?.let(::load)
    }

    /** Back from the settings: an access granted there shows at once. */
    fun onResume() {
        if (state.value.accessBanner && granted()) refresh()
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun toggleHidden() = state.update { it.copy(showHidden = !it.showHidden).refiltered() }

    fun setSort(mode: SortMode) = state.update { it.sortedBy(mode) }

    private fun load(dir: File) {
        listing?.cancel()
        // Another folder starts unfiltered; a refresh of this one keeps its filter.
        val otherFolder = dir != state.value.dir
        if (otherFolder) query.value = ""
        state.update { it.copy(loading = true, canGoBack = history.isNotEmpty(), query = if (otherFolder) "" else it.query) }
        listing = scope.launch {
            val (result, volumes, denied) = withContext(io) {
                Triple(DirectoryListing.list(dir), mounted(), !granted() && isUnderSharedStorage(dir))
            }
            state.update { it.listed(dir, result, denied, volumes, canGoBack = history.isNotEmpty()) }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}

/**
 * Shared storage, every volume: what the storage permission opens. `/sdcard` is a real alias of the
 * primary volume that the user reaches by climbing the breadcrumb to `/`: it is recognised, not built.
 */
@SuppressLint("SdCardPath")
internal fun isUnderSharedStorage(dir: File): Boolean =
    dir.path.startsWith("/storage/") || dir.path == "/sdcard" || dir.path.startsWith("/sdcard/")
