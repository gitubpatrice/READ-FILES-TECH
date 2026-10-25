package com.readfilestech.read_files_tech.data.convert

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Where the CSV tools' table PDF puts each cell: every one exactly once, its header on every page, its text cut to its column. */
class TableLayoutTest {
    /** A text as wide as its characters, 5 points each: what fits is countable. */
    private val measure = { text: String -> text.length * 5f }

    /** Every (row, column) the pages draw, and the pages' count checked against [TableLayout.pages]. */
    private fun cellsOf(layout: TableLayout): List<Pair<Int, Int>> = (0 until layout.pages.toInt()).flatMap { index ->
        val page = layout.page(index)
        (page.firstRow until page.untilRow).flatMap { row -> (page.firstColumn until page.untilColumn).map { row to it } }
    }

    @Test
    fun `a few columns share the page's width, as the 2_15_3`() {
        val layout = TableLayout(columns = 4, rows = 10)
        assertThat(layout.bands).isEqualTo(1)
        assertThat(layout.columnsPerBand).isEqualTo(4)
        assertThat(layout.columnWidth * 4).isWithin(0.01f).of(PdfPages.WIDTH - 2 * layout.margin)
        assertThat(layout.pages).isEqualTo(1)
    }

    @Test
    fun `a wide table goes in bands of columns at least the minimum wide, down then over`() {
        val layout = TableLayout(columns = 25, rows = 120)
        assertThat(layout.columnWidth).isAtLeast(TableLayout.MIN_COLUMN_WIDTH)
        assertThat(layout.columnsPerBand).isEqualTo(10)
        assertThat(layout.bands).isEqualTo(3)
        val pagesPerBand = (120 + layout.rowsPerPage - 1) / layout.rowsPerPage
        assertThat(layout.pages).isEqualTo(3L * pagesPerBand)
        // The pages of the first band first, top to bottom.
        assertThat(layout.page(0)).isEqualTo(TablePage(0, 10, 0, layout.rowsPerPage))
        assertThat(layout.page(1).firstColumn).isEqualTo(0)
        assertThat(layout.page(pagesPerBand)).isEqualTo(TablePage(10, 20, 0, layout.rowsPerPage))
        assertThat(layout.page(layout.pages.toInt() - 1).untilColumn).isEqualTo(25)
    }

    @Test
    fun `every cell is on exactly one page`() {
        for ((columns, rows) in listOf(1 to 0, 1 to 1, 3 to 53, 3 to 54, 11 to 200, 40 to 7)) {
            val layout = TableLayout(columns, rows)
            val cells = cellsOf(layout)
            assertThat(cells).containsNoDuplicates()
            assertThat(cells.size).isEqualTo(columns * rows)
            val room = PdfPages.HEIGHT - 2 * layout.margin - TableLayout.HEADER_HEIGHT
            assertThat(layout.rowsPerPage).isEqualTo((room / TableLayout.ROW_HEIGHT).toInt())
        }
    }

    @Test
    fun `a header alone makes one page`() {
        val layout = TableLayout(columns = 3, rows = 0)
        assertThat(layout.pages).isEqualTo(1)
        assertThat(layout.page(0)).isEqualTo(TablePage(0, 3, 0, 0))
    }

    @Test
    fun `the largest table the viewer opens counts its pages before any is drawn`() {
        val layout = TableLayout(columns = 16_384, rows = 1_048_575)
        assertThat(layout.bands).isEqualTo(1639)
        assertThat(layout.pages).isEqualTo(1639L * ((1_048_575 + layout.rowsPerPage - 1) / layout.rowsPerPage))
    }

    @Test
    fun `a cell that fits is drawn whole, one that does not is cut with an ellipsis`() {
        assertThat(TableLayout.drawn("short", 100f, measure)).isEqualTo("short")
        // 50 points: 9 characters and the ellipsis.
        assertThat(TableLayout.drawn("a long cell of text", 50f, measure)).isEqualTo("a long ce" + TableLayout.ELLIPSIS)
        assertThat(TableLayout.drawn("abc", 3f, measure)).isEmpty()
    }

    @Test
    fun `a cell is one line, never half of a surrogate pair`() {
        assertThat(TableLayout.drawn("two\r\nlines\tand\u0000", 1000f, measure)).isEqualTo("two  lines and ")
        // 20 points: "ab", then the emoji's high half would come — dropped with it.
        assertThat(TableLayout.drawn("ab😀cd", 20f, measure)).isEqualTo("ab" + TableLayout.ELLIPSIS)
    }

    @Test
    fun `a cell longer than what is read is cut even when its beginning fits`() {
        val long = "x".repeat(TableLayout.CELL_CHARS + 1)
        val shown = TableLayout.drawn(long, Float.MAX_VALUE, measure)
        assertThat(shown).endsWith(TableLayout.ELLIPSIS)
        assertThat(shown.length).isEqualTo(TableLayout.CELL_CHARS + 1)
    }
}
