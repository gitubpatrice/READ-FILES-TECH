package com.readfilestech.read_files_tech.data.convert

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import java.io.OutputStream

/**
 * A CSV as a table in a PDF (`_exportPdf` of the CSV tools, `csv_tools_screen.dart:56-144`), drawn by Android's
 * `PdfDocument` in the main process, as [TextPdf] draws a text (docs D-005): A4, half an inch of margin, the
 * 2.15.3's 9 pt, its header row on a pale blue ground at the top of every page, every other row shaded. The
 * platform's font and its fallbacks draw every script, where the 2.15.3's Helvetica in WinAnsi lost all but
 * Latin-1 (as in Text → PDF, inventory F6). Each cell is one line, cut to its column ([TableLayout.drawn]);
 * a table too wide for one page goes in bands of columns ([TableLayout]). At most [FileCaps.CSV_TO_PDF_PAGES]
 * pages, counted before the first one is drawn.
 */
object CsvTablePdf {
    /**
     * [table] as a PDF on [out] (not closed). [onProgress] gets the part drawn, from 0 to 1, after each row;
     * what it throws (a cancellation) stops the work.
     *
     * @throws NothingToConvertException a table without a row, not even a header
     * @throws TooManyItemsException more than [FileCaps.CSV_TO_PDF_PAGES] pages
     */
    fun write(table: CsvTable, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        if (table.isEmpty) throw NothingToConvertException("a CSV without rows")
        val layout = TableLayout(maxOf(1, table.columns), table.rows)
        if (layout.pages > FileCaps.CSV_TO_PDF_PAGES) throw TooManyItemsException(FileCaps.CSV_TO_PDF_PAGES)
        val document = PdfDocument()
        val pages = PdfPages(document, FileCaps.CSV_TO_PDF_PAGES)
        val drawer = TableDrawer(table, layout)
        val total = maxOf(1L, table.rows.toLong() * layout.bands).toFloat()
        var drawn = 0L
        try {
            for (index in 0 until layout.pages.toInt()) {
                drawer.draw(pages.next().canvas, layout.page(index)) { onProgress(++drawn / total) }
            }
            pages.finish()
            document.writeTo(out)
        } finally {
            // A page left open by a stop: PdfDocument.close() would throw on it (Claude review R18).
            pages.release()
            document.close()
        }
    }
}

/** Draws the pages of one table. */
private class TableDrawer(private val table: CsvTable, private val layout: TableLayout) {
    private val text = paint(Typeface.DEFAULT, Color.rgb(TEXT, TEXT, TEXT))
    private val header = paint(Typeface.DEFAULT_BOLD, Color.rgb(HEADER_RED, HEADER_GREEN, HEADER_BLUE))
    private val fill = Paint().apply { style = Paint.Style.FILL }

    fun draw(canvas: Canvas, page: TablePage, onRow: () -> Unit) {
        val left = layout.margin
        val right = left + layout.columnWidth * (page.untilColumn - page.firstColumn)
        var top = layout.margin
        fill.color = Color.rgb(GROUND_RED, GROUND_GREEN, GROUND_BLUE)
        canvas.drawRect(left, top, right, top + TableLayout.HEADER_HEIGHT, fill)
        cells(canvas, table.header(page.firstColumn, page.untilColumn, TableLayout.CELL_CHARS + 1), header, top, TableLayout.HEADER_HEIGHT)
        top += TableLayout.HEADER_HEIGHT
        fill.color = Color.rgb(STRIPE, STRIPE, STRIPE)
        for (row in page.firstRow until page.untilRow) {
            if (row % 2 == 0) canvas.drawRect(left, top, right, top + TableLayout.ROW_HEIGHT, fill)
            val values = table.cells(row, page.firstColumn, page.untilColumn, TableLayout.CELL_CHARS + 1)
            cells(canvas, values, text, top, TableLayout.ROW_HEIGHT)
            top += TableLayout.ROW_HEIGHT
            onRow()
        }
    }

    /** [values] side by side in the row at [top], each cut to its column, its text centred in [height]. */
    private fun cells(canvas: Canvas, values: List<String>, paint: Paint, top: Float, height: Float) {
        val baseline = top + (height - (paint.descent() - paint.ascent())) / 2 - paint.ascent()
        val room = layout.columnWidth - 2 * TableLayout.CELL_PADDING
        values.forEachIndexed { index, value ->
            val shown = TableLayout.drawn(value, room) { paint.measureText(it) }
            if (shown.isNotEmpty()) {
                canvas.drawText(shown, layout.margin + index * layout.columnWidth + TableLayout.CELL_PADDING, baseline, paint)
            }
        }
    }

    private fun paint(face: Typeface, colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TableLayout.FONT_SIZE
        typeface = face
        color = colour
        fontFeatureSettings = PdfPages.NO_LIGATURES
    }

    private companion object {
        /** The 2.15.3's colours: text 30/30/30, header text 21/101/192 on 220/230/245, every other row 248. */
        const val TEXT = 30
        const val HEADER_RED = 21
        const val HEADER_GREEN = 101
        const val HEADER_BLUE = 192
        const val GROUND_RED = 220
        const val GROUND_GREEN = 230
        const val GROUND_BLUE = 245
        const val STRIPE = 248
    }
}
