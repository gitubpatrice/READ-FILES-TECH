package com.readfilestech.read_files_tech.data.viewer.pdf

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import org.junit.jupiter.api.Test

/** The one rule both processes lay a page out and render it by. */
class PdfPageFitTest {
    private val a4 = PageSize(595f, 842f)

    /** Where a point (x, y) of the page lands in the bitmap. */
    private fun PageRender.at(x: Float, y: Float) = (x * scale + dx) to (y * scale + dy)

    @Test
    fun `a page fills the column's width, its height in its own ratio`() {
        assertThat(PdfPageFit.boxHeight(a4, 1080)).isEqualTo(1528)
        assertThat(PdfPageFit.boxHeight(PageSize(842f, 595f), 1080)).isEqualTo(763)
    }

    @Test
    fun `a page of an extreme ratio, or of no size, gets a bounded box`() {
        assertThat(PdfPageFit.boxHeight(PageSize(1f, 100_000f), 1000)).isEqualTo(16_000)
        assertThat(PdfPageFit.boxHeight(PageSize(100_000f, 1f), 1000)).isEqualTo(63)
        assertThat(PdfPageFit.boxHeight(PageSize(0f, 842f), 1000)).isEqualTo(1000)
        assertThat(PdfPageFit.boxHeight(PageSize(Float.NaN, 842f), 1000)).isEqualTo(1000)
    }

    @Test
    fun `the whole page fills its bitmap, corner to corner`() {
        val render = requireNotNull(PdfPageFit.render(a4, 1080, null, Long.MAX_VALUE))

        assertThat(render.width to render.height).isEqualTo(1080 to 1528)
        val (left, top) = render.at(0f, 0f)
        val (right, bottom) = render.at(595f, 842f)
        assertThat(left).isWithin(0.5f).of(0f)
        assertThat(top).isWithin(0.5f).of(0f)
        assertThat(right).isWithin(0.5f).of(1080f)
        assertThat(bottom).isWithin(0.5f).of(1528f)
    }

    @Test
    fun `a region is rendered alone, the page shifted under it`() {
        // The lower right quarter of an A4 page, in a column zoomed three times.
        val render = requireNotNull(PdfPageFit.render(a4, 3240, PixelRect(1620, 2292, 3240, 4584), Long.MAX_VALUE))

        assertThat(render.width to render.height).isEqualTo(1620 to 2292)
        val (x, y) = render.at(595f / 2, 842f / 2)
        assertThat(x).isWithin(1f).of(0f)
        assertThat(y).isWithin(1f).of(0f)
    }

    @Test
    fun `a render past its pixels is smaller than the region, and never past the image cap`() {
        val render = requireNotNull(PdfPageFit.render(a4, 1080, null, 400_000))
        assertThat(render.width.toLong() * render.height).isAtMost(400_000)
        assertThat(render.width.toFloat() / render.height).isWithin(0.01f).of(1080f / 1528)
        assertThat(render.at(595f, 842f).first).isWithin(1f).of(render.width.toFloat())

        val huge = requireNotNull(PdfPageFit.render(PageSize(1f, 100_000f), 100_000, null, Long.MAX_VALUE))
        assertThat(huge.width.toLong() * huge.height).isAtMost(FileCaps.IMAGE_PIXELS)
    }

    @Test
    fun `a page clamped in its box is centred in it, with a margin`() {
        val render = requireNotNull(PdfPageFit.render(PageSize(1f, 100f), 160, null, Long.MAX_VALUE))

        // A box 16 times taller than wide: the page, 100 times, is 25.6 px wide in it, in its middle.
        assertThat(render.width to render.height).isEqualTo(160 to 2560)
        assertThat(render.at(0f, 0f).first).isWithin(0.5f).of((160 - 25.6f) / 2)
        assertThat(render.at(1f, 100f).second).isWithin(0.5f).of(2560f)
    }

    @Test
    fun `a region off the page, or no pixel at all, renders nothing, and a page of no size draws nothing`() {
        assertThat(PdfPageFit.render(a4, 1080, PixelRect(0, 2000, 1080, 2500), Long.MAX_VALUE)).isNull()
        assertThat(PdfPageFit.render(a4, 1080, null, 0)).isNull()
        assertThat(requireNotNull(PdfPageFit.render(PageSize(0f, 0f), 100, null, Long.MAX_VALUE)).scale).isEqualTo(0f)
    }
}
