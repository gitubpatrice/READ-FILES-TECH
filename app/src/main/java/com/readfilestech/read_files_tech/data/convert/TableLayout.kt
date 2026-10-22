package com.readfilestech.read_files_tech.data.convert

/** One page of a table: its columns [firstColumn] until [untilColumn], its rows [firstRow] until [untilRow] (0 under the header). */
internal data class TablePage(val firstColumn: Int, val untilColumn: Int, val firstRow: Int, val untilRow: Int)

/**
 * Where a table of [columns] columns and [rows] rows under its header goes on the pages of [CsvTablePdf]: its
 * header at the top of every page, then [rowsPerPage] rows of [ROW_HEIGHT]; its columns side by side, sharing
 * the width, each at least [MIN_COLUMN_WIDTH] — a wider table is cut into bands of [columnsPerBand] columns,
 * the pages of a band one after the other, then those of the next band ("down, then over", as Excel prints).
 * The 2.15.3 shared the width between all the columns, whatever their number: fifty columns were ten points
 * wide each, their text unreadable (`csv_tools_screen.dart:80`). Pure: the JVM tests measure it.
 */
internal class TableLayout(
    val columns: Int,
    val rows: Int,
    pageWidth: Float = PdfPages.WIDTH.toFloat(),
    pageHeight: Float = PdfPages.HEIGHT.toFloat(),
    val margin: Float = ImagePlacement.MARGIN,
) {
    init {
        require(columns > 0 && rows >= 0) { "a table of $columns columns and $rows rows" }
    }

    private val contentWidth = pageWidth - 2 * margin

    val columnsPerBand: Int = (contentWidth / MIN_COLUMN_WIDTH).toInt().coerceIn(1, columns)

    val bands: Int = ceilDiv(columns, columnsPerBand)

    val columnWidth: Float = contentWidth / columnsPerBand

    val rowsPerPage: Int = maxOf(1, ((pageHeight - 2 * margin - HEADER_HEIGHT) / ROW_HEIGHT).toInt())

    private val pagesPerBand = maxOf(1, ceilDiv(rows, rowsPerPage))

    /** The pages the table makes — a header alone makes one —, counted before any is drawn. */
    val pages: Long = bands.toLong() * pagesPerBand

    /** Page [index], from 0. */
    fun page(index: Int): TablePage {
        require(index >= 0 && index < pages) { "page $index of $pages" }
        val firstColumn = index / pagesPerBand * columnsPerBand
        val firstRow = index % pagesPerBand * rowsPerPage
        return TablePage(firstColumn, minOf(columns, firstColumn + columnsPerBand), firstRow, minOf(rows, firstRow + rowsPerPage))
    }

    companion object {
        /** The 2.15.3's Helvetica 9, its header row 16 points high and its rows 14 (`csv_tools_screen.dart:62-74`). */
        const val FONT_SIZE = 9f
        const val HEADER_HEIGHT = 16f
        const val ROW_HEIGHT = 14f

        /** About ten characters: ten columns side by side on A4 at most, the 2.15.3's common case. */
        const val MIN_COLUMN_WIDTH = 48f

        /** Space left of and right of a cell's text, the 2.15.3's 2 points. */
        const val CELL_PADDING = 2f

        /** Characters of a cell read to be drawn: a column a page wide shows far fewer. */
        const val CELL_CHARS = 1024

        const val ELLIPSIS = "…"

        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        /**
         * What is drawn of the cell [value] in [width]: its line breaks, tabs and other control characters as
         * spaces — a cell is one line —, whole when it fits; otherwise its longest beginning that fits followed
         * by an ellipsis, never half of a surrogate pair. [value] was read up to [CELL_CHARS] + 1 characters:
         * one more, and it is longer than what is drawn. [measure] is the width of a text as drawn.
         *
         * Only what is visible goes into the page: a text clipped by the canvas would stay whole in the PDF,
         * where a search or a copy would find what the page does not show, and pdftotext would read it over
         * the next column.
         */
        fun drawn(value: String, width: Float, measure: (String) -> Float): String {
            val cut = value.length > CELL_CHARS
            val text = String(CharArray(minOf(value.length, CELL_CHARS)) { value[it].takeIf { c -> c >= ' ' } ?: ' ' })
            if (!cut && measure(text) <= width) return text
            var low = 0
            var high = text.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (measure(text.substring(0, mid) + ELLIPSIS) <= width) low = mid else high = mid - 1
            }
            if (low > 0 && text[low - 1].isHighSurrogate()) low--
            return if (low == 0 && measure(ELLIPSIS) > width) "" else text.substring(0, low) + ELLIPSIS
        }
    }
}
