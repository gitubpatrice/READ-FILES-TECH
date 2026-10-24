package com.readfilestech.read_files_tech.ui.viewer.csv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CsvViewerViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    sources: ViewerSources,
) : ViewModel() {
    val source: ViewerSource? = sources.from(handle)

    val content = ViewerContent(viewModelScope, io, source?.shareable) {
        CsvTable.read(requireNotNull(source) { "no file in the route" })
    }
        .also(::addCloseable)

    // Kept across a process death, as the search field keeps its text.
    private val query = handle.getStateFlow(KEY_QUERY, "")
    private val sort = handle.getStateFlow(KEY_SORT, CsvSortChoice.NONE)

    private val mutableShown = MutableStateFlow<Pair<CsvTable, CsvShown>?>(null)

    /** The rows shown, once the table is open, with the table they were found in: shown on that one only. */
    val shown: StateFlow<Pair<CsvTable, CsvShown>?> = mutableShown.asStateFlow()

    init {
        viewModelScope.launch {
            // Each table read — the first, and the one read again after the editor saved it.
            content.state.filterIsInstance<ViewerState.Ready<CsvTable>>().collectLatest { ready ->
                val table = ready.content
                val sorts = sort.map { saved -> CsvSortChoice.fromSaved(saved)?.takeIf { it.column < table.columns } }
                CsvBrowser(table, io).run(query, sorts) { mutableShown.value = table to it }
            }
        }
    }

    fun search(text: String) {
        handle[KEY_QUERY] = text
    }

    /** A tap on a column's name. */
    fun sortBy(column: Int) {
        handle[KEY_SORT] = CsvSortChoice.tapped(CsvSortChoice.fromSaved(sort.value), column).saved
    }

    private companion object {
        const val KEY_QUERY = "csv_query"
        const val KEY_SORT = "csv_sort"
    }
}
