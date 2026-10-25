package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException

/** What bounds the reading of a workbook: the 2.15.3's caps, smaller ones in tests only. */
internal data class SheetLimits(
    /** The file itself (the 2.15.3's `spreadsheet`). */
    val fileBytes: Long = FileCaps.SPREADSHEET,
    /** One XML part, as much of it as is read: a sheet is read up to its row cap only. */
    val partBytes: Long = FileCaps.ZIP_ENTRY_DECOMPRESSED,
    val rows: Int = FileCaps.SHEET_ROWS,
    val columns: Int = FileCaps.SHEET_COLUMNS,
    val cellChars: Int = FileCaps.SHEET_CELL_CHARS,
    val textChars: Long = FileCaps.SHEET_TEXT_CHARS,
    val stretchChars: Long = FileCaps.XML_STRETCH_CHARS,
    val depth: Int = FileCaps.XML_DEPTH,
    val elements: Int = FileCaps.XML_ELEMENTS,
)

/**
 * One sheet as the viewer shows it (`XlsxViewerScreen` of the 2.15.3): its first row is the header, the
 * [rows] rows under it, [columns] wide — empty rows and columns at its end left out, as the 2.15.3 did.
 * [rowsCut] and [columnsCut] say that the sheet goes on past the caps; the 2.15.3 cut silently, its comment
 * promising a screen that would "show the true size" (`xlsx_viewer_screen.dart:29-31`) it never showed
 * (`:170` counts the rows kept).
 *
 * The cells are made into text when asked for, those on screen only: formatting is what costs (45 µs a
 * number on a phone, prototype), and a sheet of 5 000 rows by 200 columns would take a minute whole.
 * Safe to call from any thread: the workbook's formatter is used under its lock.
 */
abstract class Sheet internal constructor(private val lines: Int, val columns: Int, val rowsCut: Boolean, val columnsCut: Boolean) {
    /** The rows under the header. */
    val rows: Int get() = maxOf(0, lines - 1)

    /** Nothing at all to show, not even a header. */
    val isEmpty: Boolean get() = lines == 0

    /** The header's cells [first] to [until] (excluded). */
    fun header(first: Int, until: Int): List<String> = line(0, first, until)

    /** The cells [first] to [until] (excluded) of the row [row] under the header. */
    fun cells(row: Int, first: Int, until: Int): List<String> = line(row + 1, first, until)

    /** The cells of line [index] (0 is the header), an empty text where there is none. */
    protected abstract fun line(index: Int, first: Int, until: Int): List<String>
}

/** [text], cut to [max] chars — never half of a surrogate pair. */
internal fun cut(text: String, max: Int): String = when {
    text.length <= max -> text
    text[max - 1].isHighSurrogate() -> text.substring(0, max - 1)
    else -> text.substring(0, max)
}

/** The chars of text a workbook keeps, against [limit]: past it the workbook is refused, before it is held. */
internal class TextBudget(private val limit: Long, private var spent: Long = 0) {
    /** A budget for one sheet: what the workbook already holds is spent from it. */
    fun forSheet(): TextBudget = TextBudget(limit, spent)

    fun spend(chars: Int) {
        spent += chars
        if (spent > limit) throw FileTooLargeException(limit)
    }
}

/**
 * The cells of one row as read, by column: a text, a number formatted when shown, or the index of a shared
 * string of an .xlsx, resolved once the sheet is read. Grows with its widest cell, up to the column cap.
 */
internal class GridRow {
    private var texts = arrayOfNulls<String>(INITIAL_WIDTH)
    private var values = DoubleArray(0)

    /** Per cell: [TEXT] (or empty), [SHARED], or the number's style plus one. */
    private var kinds = IntArray(INITIAL_WIDTH)

    var width = 0
        private set

    fun text(column: Int, text: String) {
        set(column, TEXT)
        texts[column] = text
    }

    fun number(column: Int, value: Double, style: Int) {
        set(column, style + 1)
        values[column] = value
    }

    fun shared(column: Int, index: Int) {
        set(column, SHARED)
        values[column] = index.toDouble()
    }

    fun forEachShared(action: (Int) -> Unit) {
        for (column in 0 until width) if (kinds[column] == SHARED) action(values[column].toInt())
    }

    /** Each shared string's index in the row, replaced by its text from [strings]. */
    fun resolve(strings: (Int) -> String) {
        for (column in 0 until width) {
            if (kinds[column] == SHARED) {
                kinds[column] = TEXT
                texts[column] = strings(values[column].toInt())
            }
        }
    }

    /** The cell's text: [format] makes a number's, from its value and style. */
    fun shown(column: Int, format: (Double, Int) -> String): String = when {
        column >= width -> ""
        kinds[column] > TEXT -> format(values[column], kinds[column] - 1)
        else -> texts[column].orEmpty()
    }

    private fun set(column: Int, kind: Int) {
        if (column >= kinds.size) grow(maxOf(column + 1, kinds.size * 2))
        if (kind != TEXT && values.size < kinds.size) values = values.copyOf(kinds.size)
        kinds[column] = kind
        if (column >= width) width = column + 1
    }

    private fun grow(size: Int) {
        texts = texts.copyOf(size)
        kinds = kinds.copyOf(size)
        if (values.isNotEmpty()) values = values.copyOf(size)
    }

    companion object {
        const val TEXT = 0
        const val SHARED = -1
        private const val INITIAL_WIDTH = 8
    }
}

/**
 * The cells of a sheet as they are read, within [limits]: a cell past the row or column cap is not kept,
 * and marks the sheet as cut; a text is cut to the chars a cell shows and spent from [budget]; an empty
 * cell is never kept, so rows and columns left empty at the end are not shown.
 */
internal class GridBuilder(private val limits: SheetLimits, private val budget: TextBudget) {
    private val lines = ArrayList<GridRow?>()
    var rowsCut = false
    var columnsCut = false

    /** Whether a cell at [row], [column] is kept: within the caps (a cell past them cuts the sheet). */
    fun keeps(row: Int, column: Int): Boolean {
        if (row >= limits.rows) rowsCut = true
        if (column >= limits.columns && row < limits.rows) columnsCut = true
        return row < limits.rows && column < limits.columns
    }

    /** A text; [counted] false for a text already spent, kept once more (a repetition holds the same String). */
    fun text(row: Int, column: Int, text: String, counted: Boolean = true) {
        if (text.isEmpty() || !keeps(row, column)) return
        val kept = cut(text, limits.cellChars)
        if (counted) budget.spend(kept.length)
        line(row).text(column, kept)
    }

    fun number(row: Int, column: Int, value: Double, style: Int) {
        if (keeps(row, column)) line(row).number(column, value, style)
    }

    fun shared(row: Int, column: Int, index: Int) {
        if (keeps(row, column)) line(row).shared(column, index)
    }

    /** The shared strings' indices the sheet keeps, sorted, once each. */
    fun sharedIndices(): IntArray {
        var all = IntArray(INITIAL_INDICES)
        var count = 0
        lines.forEach { row ->
            row?.forEachShared { index ->
                if (count == all.size) all = all.copyOf(count * 2)
                all[count++] = index
            }
        }
        all.sort(0, count)
        var distinct = 0
        for (i in 0 until count) if (distinct == 0 || all[i] != all[distinct - 1]) all[distinct++] = all[i]
        return all.copyOf(distinct)
    }

    /** Puts the shared strings back: [strings] gives the text of each index [sharedIndices] gave. */
    fun resolveShared(strings: (Int) -> String) = lines.forEach { it?.resolve(strings) }

    /** The sheet, its numbers formatted by [format] (value, style) when shown. */
    fun build(format: (Double, Int) -> String): Sheet {
        val columns = lines.maxOfOrNull { it?.width ?: 0 } ?: 0
        return GridSheet(lines.toTypedArray(), columns, rowsCut, columnsCut, limits.cellChars, format)
    }

    private fun line(row: Int): GridRow {
        while (lines.size <= row) lines += null
        return lines[row] ?: GridRow().also { lines[row] = it }
    }

    private companion object {
        const val INITIAL_INDICES = 1024
    }
}

private class GridSheet(
    private val grid: Array<GridRow?>,
    columns: Int,
    rowsCut: Boolean,
    columnsCut: Boolean,
    private val cellChars: Int,
    private val format: (Double, Int) -> String,
) : Sheet(grid.size, columns, rowsCut, columnsCut) {
    override fun line(index: Int, first: Int, until: Int): List<String> {
        val row = grid.getOrNull(index)
        return (first until until).map { column -> row?.let { cut(it.shown(column, format), cellChars) }.orEmpty() }
    }
}
