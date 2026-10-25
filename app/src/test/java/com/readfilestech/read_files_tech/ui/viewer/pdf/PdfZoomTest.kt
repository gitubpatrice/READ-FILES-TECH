package com.readfilestech.read_files_tech.ui.viewer.pdf

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The zoom of the column of pages, the tiles it renders, the counter and "Go to page". */
class PdfZoomTest {
    private val width = 1000f

    /** Where a point of the list (x, y below its scroll position [top]) is on screen. */
    private fun PdfZoom.screen(x: Float, y: Float, top: Float) = (x * scale + this.x) to ((y - top) * scale)

    @Test
    fun `a pinch keeps the point under the fingers under them, both ways`() {
        val start = PdfZoom(1.5f, -200f)
        val top = 3000f
        // The point of the list under (600, 900) before the pinch.
        val x = (600f - start.x) / start.scale
        val y = top + 900f / start.scale

        val step = start.transformed(Offset(600f, 900f), Offset.Zero, 1.6f, width)

        assertThat(step.zoom.scale).isWithin(1e-4f).of(2.4f)
        val (sx, sy) = step.zoom.screen(x, y, top + step.scroll)
        assertThat(sx).isWithin(0.01f).of(600f)
        assertThat(sy).isWithin(0.01f).of(900f)
    }

    @Test
    fun `a move of the fingers moves the pages with them`() {
        val start = PdfZoom(2f, -300f)
        val step = start.transformed(Offset(500f, 500f), Offset(40f, 30f), 1f, width)

        assertThat(step.zoom.x).isEqualTo(-260f)
        // The list scrolls back by 30 screen pixels: 15 of its own at twice the size.
        assertThat(step.scroll).isEqualTo(-15f)
    }

    @Test
    fun `the zoom stays between fitted and three times, the column never off its edges`() {
        assertThat(PdfZoom.NONE.transformed(Offset(500f, 500f), Offset.Zero, 0.5f, width).zoom).isEqualTo(PdfZoom.NONE)
        assertThat(PdfZoom.NONE.transformed(Offset(500f, 500f), Offset.Zero, 10f, width).zoom.scale).isEqualTo(PdfZoom.MAX_SCALE)
        assertThat(PdfZoom(2f, 0f).panned(300f, width).x).isEqualTo(0f)
        assertThat(PdfZoom(2f, 0f).panned(-5000f, width).x).isEqualTo(-1000f)
    }

    @Test
    fun `a double tap zooms twice around the point, and a second one back to fitted`() {
        val zoomed = PdfZoom.NONE.doubleTapped(Offset(250f, 400f), width)
        assertThat(zoomed.zoom.scale).isEqualTo(PdfZoom.DOUBLE_TAP_SCALE)
        assertThat(zoomed.zoom.x).isEqualTo(-250f)
        assertThat(zoomed.scroll).isEqualTo(200f)

        val back = zoomed.zoom.doubleTapped(Offset(250f, 400f), width)
        assertThat(back.zoom).isEqualTo(PdfZoom.NONE)
        assertThat(back.scroll).isEqualTo(-200f)
    }

    @Test
    fun `tiles cover what the screen shows of each page, never more pixels than the screen`() {
        val pages = listOf(VisiblePage(4, -300, 600), VisiblePage(5, 304, 1400))
        val zoom = PdfZoom(2f, -500f)

        val tiles = PdfView.tiles(pages, 1000, 2000, zoom)

        assertThat(tiles.map { it.index }).containsExactly(4, 5).inOrder()
        assertThat(tiles.all { it.width == 2000 }).isTrue()
        // Page 4: its box from y = -600 to 600 on screen, x from -500: its rows 600 to 1200, columns 500 to 1500.
        assertThat(tiles[0].region.top).isEqualTo(600)
        assertThat(tiles[0].region.left).isEqualTo(500)
        assertThat(tiles[0].region.right).isEqualTo(1500)
        assertThat(tiles[0].top).isEqualTo(300f)
        assertThat(tiles.sumOf { it.region.width.toLong() * it.region.height }).isAtMost(1000L * 2000)
        assertThat(PdfView.tiles(pages, 1000, 2000, PdfZoom.NONE)).isEmpty()
    }

    @Test
    fun `the counter follows the page under the middle of the screen`() {
        val pages = listOf(VisiblePage(0, -900, 1402), VisiblePage(1, 506, 1400))

        assertThat(PdfView.currentPage(pages, 2000, PdfZoom.NONE)).isEqualTo(1)
        assertThat(PdfView.currentPage(pages, 2000, PdfZoom(2f))).isEqualTo(0)
        assertThat(PdfView.currentPage(emptyList(), 2000, PdfZoom.NONE)).isNull()
    }

    @Test
    fun `go to page takes a number of the document's pages only`() {
        assertThat(PdfView.pageTyped(" 12 ", 100)).isEqualTo(11)
        assertThat(PdfView.pageTyped("1", 1)).isEqualTo(0)
        for (wrong in listOf("", "0", "101", "-3", "1.5", "12a", "99999999999")) {
            assertThat(PdfView.pageTyped(wrong, 100)).isNull()
        }
    }
}
