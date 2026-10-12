package com.readfilestech.read_files_tech.data.viewer.csv

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.BitSet

/**
 * The filter of the 2.15.3 (`_applySearch`): the rows under the header with a cell that contains the
 * query, case ignored (both lower-cased, as the 2.15.3 and the explorer do). It ran on the UI thread at
 * each key, over every cell; here each row is first matched as a whole, raw — a query without a quote,
 * found in no cell, is not in the row's text either — and only the rows it may be in are read cell by
 * cell. Cancelled at once when the next key replaces it.
 */
object CsvFilter {
    /** A row longer than this is read cell by cell at once, not lower-cased whole. */
    private const val RAW_ROW_UNITS = 64 * 1024
    private const val CANCEL_CHECK_EVERY = 1024

    /** The rows of [table] (0 is the first under the header) with a cell that contains [query]. */
    suspend fun matching(table: CsvTable, query: String): BitSet {
        val context = currentCoroutineContext()
        val needle = query.lowercase()
        // A cell's `"` is `""` in the raw row: there, a query holding a quote would be missed.
        val raw = '"' !in needle
        val matches = BitSet(table.rows)
        for (row in 0 until table.rows) {
            if (row % CANCEL_CHECK_EVERY == 0) context.ensureActive()
            val whole = if (raw) table.rawRow(row, RAW_ROW_UNITS) else null
            if ((whole == null || needle in whole.lowercase()) && hasCell(table, row, needle)) matches.set(row)
        }
        return matches
    }

    private fun hasCell(table: CsvTable, row: Int, needle: String): Boolean {
        var found = false
        table.scan(row) { _, start, end ->
            found = needle in table.fieldText(start, end).lowercase()
            !found
        }
        return found
    }
}

/** The rows under the header that the table shows, in the order shown. */
class CsvView private constructor(private val rows: IntArray?, val size: Int) {
    /** The table's row at [position]. */
    fun row(position: Int): Int = rows?.get(position) ?: position

    companion object {
        /** All [rows] of the table in [order] (the file's when null), those in [matches] only when given. */
        fun of(rows: Int, order: IntArray?, matches: BitSet?): CsvView {
            if (matches == null) return CsvView(order, order?.size ?: rows)
            val shown = IntArray(matches.cardinality())
            var size = 0
            for (position in 0 until rows) {
                val row = order?.get(position) ?: position
                if (matches[row]) shown[size++] = row
            }
            return CsvView(shown, size)
        }
    }
}
