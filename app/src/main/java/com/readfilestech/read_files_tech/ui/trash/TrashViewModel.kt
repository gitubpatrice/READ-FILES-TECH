package com.readfilestech.read_files_tech.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.trash.TrashEntry
import com.readfilestech.read_files_tech.data.trash.TrashStore
import com.readfilestech.read_files_tech.ui.common.FileOperations
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TrashUiState(val loading: Boolean = true, val entries: List<TrashEntry> = emptyList()) {
    /** What the trash holds, folders (size unknown, -1) counted as nothing — as the 2.15.x. */
    val totalBytes: Long get() = entries.sumOf { it.size.coerceAtLeast(0) }
}

/** The trash (`trash_screen.dart` of the 2.15.x): every volume's, the most recent first. Nothing is purged by itself. */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trash: TrashStore,
    private val operations: FileOperations,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = state.asStateFlow()
    private var loading: Job? = null

    init {
        load()
        viewModelScope.launch { operations.changes.collect { load() } }
    }

    fun load() {
        loading?.cancel()
        state.update { it.copy(loading = true) }
        loading = viewModelScope.launch {
            val entries = withContext(io) { trash.list() }
            state.update { TrashUiState(loading = false, entries = entries) }
        }
    }

    fun restore(entry: TrashEntry) = operations.restore(listOf(entry))

    fun deleteForever(entry: TrashEntry) = operations.deleteFromTrash(listOf(entry))

    fun emptyAll() = operations.deleteFromTrash(state.value.entries)
}
