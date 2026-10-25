package com.readfilestech.read_files_tech.data.images

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class ImageScaleTest {
    private fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor

    /** The decoded size never exceeds the limit, and the next power of two down would. */
    @Test
    fun `a sample is the smallest power of two that keeps under the limit`() {
        assertThat(ImageScale.sampleSize(4000, 3000, 4000, 3000)).isEqualTo(1)
        assertThat(ImageScale.sampleSize(4000, 3000, 3999, 3000)).isEqualTo(2)
        assertThat(ImageScale.sampleSize(4000, 3000, 2000, 1500)).isEqualTo(2)
        // ceil(4001 / 2) = 2001: a decoder makes 2001 pixels of it, one over.
        assertThat(ImageScale.sampleSize(4001, 3000, 2000, 1500)).isEqualTo(4)
        assertThat(ImageScale.sampleSize(12000, 5333, 1, 1)).isEqualTo(16384)
        assertThat(ImageScale.sampleSize(10, 10, 0, -5)).isEqualTo(16)
        // Width, height, and their limits.
        for (case in listOf(intArrayOf(4032, 3024, 2161, 1621), intArrayOf(9238, 6928, 2160, 1620), intArrayOf(1, 12000, 7, 3))) {
            val s = ImageScale.sampleSize(case[0], case[1], case[2], case[3])
            fun fits(sample: Int) = ceilDiv(case[0], sample) <= case[2] && ceilDiv(case[1], sample) <= case[3]
            assertThat(Integer.bitCount(s)).isEqualTo(1)
            assertThat(fits(s)).isTrue()
            if (s > 1) assertThat(fits(s / 2)).isFalse()
        }
    }

    /** The 2.15.3 decoded at twice the screen (`cacheWidth`): a page shows the image at 1x to 2x of what it decodes. */
    @Test
    fun `the viewer decodes at up to twice the size shown, never less than the size shown`() {
        // A 12 Mpx photo of the S9 on a 1080 x 2000 page: shown 1080 x 810, decoded 2016 x 1512 (12 MB, not 48).
        assertThat(ImageScale.fittingSample(4032, 3024, 1080, 2000)).isEqualTo(2)
        // Upright: shown 1080 x 1440, decoded 1512 x 2016.
        assertThat(ImageScale.fittingSample(3024, 4032, 1080, 2000)).isEqualTo(2)
        // A 64 Mpx picture: shown 1080 x 810, decoded 1155 x 866 (4 MB) — never the 256 MB of the whole.
        assertThat(ImageScale.fittingSample(9238, 6928, 1080, 2400)).isEqualTo(8)
        // A screenshot of the screen's own size, or smaller: whole.
        assertThat(ImageScale.fittingSample(1080, 2400, 1080, 2400)).isEqualTo(1)
        assertThat(ImageScale.fittingSample(300, 200, 1080, 2400)).isEqualTo(1)
        for ((w, h) in listOf(4032 to 3024, 12000 to 5333, 8000 to 8000, 1080 to 12000, 7 to 11_999)) {
            val s = ImageScale.fittingSample(w, h, 1080, 2400)
            val shown = minOf(1080.0 / w, 2400.0 / h)
            for ((side, decoded) in listOf(w to ceilDiv(w, s), h to ceilDiv(h, s))) {
                // At most twice the size shown, and at least the size shown — or the image whole.
                val twiceShown = minOf(side.toDouble(), shown * ImageScale.ZOOM_DETAIL * side)
                assertWithMessage("$w x $h").that(decoded.toDouble()).isAtMost(twiceShown + 1)
                assertWithMessage("$w x $h").that(decoded.toDouble()).isAtLeast(minOf(side.toDouble(), shown * side) - 1)
            }
        }
    }

    @Test
    fun `a thumbnail covers its square, a long side held to four times it`() {
        // 4032 x 3024 for a 264 px square (twice its box): its short side to 264 at most, 189 here (sample 16).
        assertThat(ImageScale.coveringSample(4032, 3024, 264, 4)).isEqualTo(16)
        assertThat(ceilDiv(3024, ImageScale.coveringSample(4032, 3024, 264, 4))).isAtMost(264)
        // A 1080 x 20 000 screenshot: the long side, not the short one, sets the sample.
        val tall = ImageScale.coveringSample(1080, 20_000, 132, 4)
        assertThat(ceilDiv(20_000, tall)).isAtMost(132 * 4)
        assertThat(ImageScale.coveringSample(100, 100, 264, 4)).isEqualTo(1)
    }

    /**
     * Each value of the EXIF specification, applied to a stored grid: where its row 0 and its column 0
     * end up on screen (TIFF 6.0, tag 274 — "the 0th row represents the visual top", and so on).
     */
    @Test
    fun `the eight EXIF orientations show the image upright`() {
        val expected = mapOf(
            1 to ("top" to "left"),
            2 to ("top" to "right"),
            3 to ("bottom" to "right"),
            4 to ("bottom" to "left"),
            5 to ("left" to "top"),
            6 to ("right" to "top"),
            7 to ("right" to "bottom"),
            8 to ("left" to "bottom"),
        )
        expected.forEach { (tag, sides) ->
            val shown = show(ExifOrientation.of(tag), width = 3, height = 2)
            assertWithMessage("orientation $tag, row 0").that(sideOf(shown) { it.second == 0 }).isEqualTo(sides.first)
            assertWithMessage("orientation $tag, column 0").that(sideOf(shown) { it.first == 0 }).isEqualTo(sides.second)
            assertWithMessage("orientation $tag").that(ExifOrientation.of(tag).swapsSides).isEqualTo(tag >= 5)
        }
        listOf(0, 9, -1).forEach { assertThat(ExifOrientation.of(it)).isEqualTo(ExifOrientation.UPRIGHT) }
    }

    /** A stored grid of (column, row) cells, mirrored then turned clockwise, as Matrix.postScale then postRotate. */
    private fun show(orientation: ExifOrientation, width: Int, height: Int): List<List<Pair<Int, Int>>> {
        var grid = List(height) { y -> List(width) { x -> x to y } }
        if (orientation.mirrored) grid = grid.map { it.reversed() }
        repeat(orientation.degrees / 90) {
            // Clockwise: the bottom row becomes the left column.
            grid = List(grid[0].size) { x -> List(grid.size) { y -> grid[grid.size - 1 - y][x] } }
        }
        return grid
    }

    /** The side of the shown grid that holds exactly the stored cells [stored] selects. */
    private fun sideOf(grid: List<List<Pair<Int, Int>>>, stored: (Pair<Int, Int>) -> Boolean): String? {
        val sides = mapOf(
            "top" to grid.first(),
            "bottom" to grid.last(),
            "left" to grid.map { it.first() },
            "right" to grid.map { it.last() },
        )
        val cells = grid.flatten().filter(stored).toSet()
        return sides.entries.singleOrNull { it.value.toSet() == cells }?.key
    }
}
