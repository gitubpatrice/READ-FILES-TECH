package com.readfilestech.read_files_tech.data.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The page's size, its quarter turns, and which frames can be straightened. */
class PageGeometryTest {
    @Test
    fun `the page's size is its longest opposite sides, turned, then bounded`() {
        // An A4 sheet (210 × 297) seen in perspective: top side shorter than the bottom one.
        // Sides: top 190, bottom 210, left 297.04, right 297.38.
        val quad = listOf(Point(105.0, 100.0), Point(295.0, 100.0), Point(310.0, 397.0), Point(100.0, 397.0))
        assertThat(PageGeometry.pageSize(quad, turns = 0)).isEqualTo(PixelSize(210, 297))
        assertThat(PageGeometry.pageSize(quad, turns = 1)).isEqualTo(PixelSize(297, 210))
        assertThat(PageGeometry.pageSize(quad, turns = -2)).isEqualTo(PixelSize(210, 297))
        // A 12 Mpx photo framed whole: brought to the page limit, its shape kept.
        val whole = PageGeometry.inPixels(PageGeometry.WHOLE, PixelSize(4032, 3024))
        assertThat(PageGeometry.pageSize(whole, turns = 0)).isEqualTo(PixelSize(3500, 2625))
    }

    @Test
    fun `turned a quarter clockwise, the top-left corner goes to the top right`() {
        val size = PixelSize(30, 20)
        assertThat(
            PageGeometry.pageCorners(size, 0)
        ).containsExactly(Point(0.0, 0.0), Point(30.0, 0.0), Point(30.0, 20.0), Point(0.0, 20.0)).inOrder()
        assertThat(
            PageGeometry.pageCorners(size, 1)
        ).containsExactly(Point(30.0, 0.0), Point(30.0, 20.0), Point(0.0, 20.0), Point(0.0, 0.0)).inOrder()
        assertThat(
            PageGeometry.pageCorners(size, 2)
        ).containsExactly(Point(30.0, 20.0), Point(0.0, 20.0), Point(0.0, 0.0), Point(30.0, 0.0)).inOrder()
        assertThat(PageGeometry.pageCorners(size, 7)).isEqualTo(PageGeometry.pageCorners(size, 3))
    }

    @Test
    fun `only a convex frame inside the photo, not a sliver, can be straightened`() {
        assertThat(PageGeometry.isUsable(PageGeometry.WHOLE)).isTrue()
        val crossed = listOf(Point(0.1, 0.1), Point(0.9, 0.9), Point(0.9, 0.1), Point(0.1, 0.9))
        assertThat(PageGeometry.isUsable(crossed)).isFalse()
        val outside = listOf(Point(-0.1, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0))
        assertThat(PageGeometry.isUsable(outside)).isFalse()
        val sliver = listOf(Point(0.1, 0.1), Point(0.9, 0.1), Point(0.9, 0.11), Point(0.1, 0.11))
        assertThat(PageGeometry.isUsable(sliver)).isFalse()
        assertThat(PageGeometry.isUsable(PageGeometry.WHOLE.take(3))).isFalse()
    }
}
