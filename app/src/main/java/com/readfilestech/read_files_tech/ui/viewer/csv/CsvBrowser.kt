package com.readfilestech.read_files_tech.ui.viewer.csv

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.csv.CsvColumnKeys
import com.readfilestech.read_files_tech.data.viewer.csv.CsvFilter
import com.readfilestech.read_files_tech.data.viewer.csv.CsvSort
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.data.viewer.csv.CsvView
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.BitSet

/** A sort asked for: by [column], up or down. */
data class CsvSortChoice(val column: Int, val ascending: Boolean) {
    /** One integer for the saved state of the screen: twice the column, plus one going down. */
    val saved: Int get() = column * 2 + if (ascending) 0 else 1

    companion object {
        const val NONE = -1

        fun fromSaved(saved: Int): CsvSortChoice? = if (saved < 0) null else CsvSortChoice(saved / 2, saved % 2 == 0)

        /** A tap on [column]'s name after [current]: up, then down, then up again (the 2.15.3's `_sort`). */
        fun tapped(current: CsvSortChoice?, column: Int): CsvSortChoice {
            val again = current?.column == column
            return CsvSortChoice(column, ascending = !again || current?.ascending == false)
        }
    }
}

/**
 * What the table shows: its rows for [query] in the order of [sort]; [busy] while the next is computed,
 * [failure] when the last could not be (the rows shown are then those before it).
 */
data class CsvShown(val view: CsvView, val query: String, val sort: CsvSortChoice?, val busy: Boolean, val failure: UiText? = null)

/**
 * The search and the sort of a table, off the main thread (the 2.15.3 ran both on it, at each key). The
 * newest asked cancels the one running — a letter typed stops the search of the one before — and a
 * query waits [DEBOUNCE_MS] for the next letter, as the explorer's filter does. The keys of the column
 * sorted, its order and the rows found are kept for the next change of the other.
 */
@OptIn(FlowPreview::class)
class CsvBrowser(
    private val table: CsvTable,
    private val io: CoroutineDispatcher,
    private val operations: Operations = TableOperations(table),
) {
    /** The two computations, apart so that a test can make them slow. */
    interface Operations {
        suspend fun matching(query: String): BitSet

        suspend fun order(sort: CsvSortChoice): IntArray
    }

    /**
     * Gives [publish] what the table shows for each query and sort, until cancelled. A search or a sort
     * that fails — out of memory on a hostile file — leaves the rows as they were and says so: its error
     * would otherwise end the app (the R9 review's lesson on the text viewer's colours).
     */
    suspend fun run(queries: Flow<String>, sorts: Flow<CsvSortChoice?>, publish: (CsvShown) -> Unit) {
        var shown = CsvShown(CsvView.of(table.rows, null, null), query = "", sort = null, busy = false)
        publish(shown)
        combine(queries.debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS }, sorts, ::Pair)
            .distinctUntilChanged()
            .collectLatest { (query, sort) ->
                publish(shown.copy(busy = true))
                val view = loadOrFailure {
                    withContext(io) {
                        val order = sort?.let { operations.order(it) }
                        val matches = if (query.isEmpty()) null else operations.matching(query)
                        CsvView.of(table.rows, order, matches)
                    }
                }
                shown = view.fold(
                    onSuccess = { CsvShown(it, query, sort, busy = false) },
                    onFailure = { shown.copy(busy = false, failure = reasonOf(it)) },
                )
                publish(shown)
            }
    }

    private fun reasonOf(error: Throwable): UiText =
        (ViewerErrors.failureOf(error) as? ViewerFailure.Reason)?.text ?: uiText(R.string.viewer_unreadable)

    /**
     * The last results, reused: the keys of a column for its other direction, an order for a new query,
     * the rows found for a new sort. One computation at a time ([run] waits for the one it cancels).
     */
    private class TableOperations(private val table: CsvTable) : Operations {
        private var keys: CsvColumnKeys? = null
        private var order: Pair<CsvSortChoice, IntArray>? = null
        private var matches: Pair<String, BitSet>? = null

        override suspend fun matching(query: String): BitSet =
            matches?.takeIf { it.first == query }?.second ?: CsvFilter.matching(table, query).also { matches = query to it }

        override suspend fun order(sort: CsvSortChoice): IntArray = order?.takeIf { it.first == sort }?.second ?: run {
            val columnKeys = keys?.takeIf { it.column == sort.column } ?: CsvSort.keys(table, sort.column).also { keys = it }
            CsvSort.order(table, columnKeys, sort.ascending).also { order = sort to it }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 150L
    }
}
