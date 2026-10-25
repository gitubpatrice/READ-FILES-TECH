package com.readfilestech.read_files_tech.data.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.max
import kotlin.math.min

/**
 * The filters, against the bench's whole-image formulas (tools/scanner-bench `PureFilters`, copied below
 * as the oracle): the app reads and writes the page by bands of rows, the bench held three copies of it.
 */
class PageFiltersTest {
    /** An IntArray behind [PixelRows], as a Bitmap is on the phone. */
    private class ArrayRows(override val width: Int, override val height: Int, val pixels: IntArray) : PixelRows {
        var reads = 0

        override fun read(y: Int, rows: Int, into: IntArray) {
            reads++
            System.arraycopy(pixels, y * width, into, 0, rows * width)
        }

        override fun write(y: Int, rows: Int, from: IntArray) = System.arraycopy(from, 0, pixels, y * width, rows * width)
    }

    /**
     * A page of text crossed by a hand's shadow: the light falls from 245 to 120 across a diagonal band.
     * 203 × 157: neither side a multiple of the 8-row band, nor of the 8-pixel block.
     */
    private fun shadowedPage(w: Int = 203, h: Int = 157, pencil: Boolean = false): RgbImage {
        val text = ScanTestImages.textPage(paper = ScanTestImages.rgb(250, 248, 240), ink = ScanTestImages.rgb(30, 30, 60))
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            // Every grey between ink and paper along the last rows (pencil, a stamp): where a threshold sits.
            val ramp = 40 + 210 * x / w
            val c = if (pencil && y >= h * 9 / 10) ScanTestImages.rgb(ramp, ramp, ramp) else text(x.toDouble() / w, y.toDouble() / h)
            val light = if (x + y < (w + h) / 2) 1.0 else 0.5
            ScanTestImages.rgb((((c shr 16) and 255) * light).toInt(), (((c shr 8) and 255) * light).toInt(), ((c and 255) * light).toInt())
        }
        return RgbImage(w, h, px)
    }

    private fun filtered(filter: PageFilter, page: RgbImage): IntArray {
        val rows = ArrayRows(page.width, page.height, page.pixels.copyOf())
        PageFilters.apply(filter, rows)
        return rows.pixels
    }

    @ParameterizedTest
    @EnumSource(PageFilter::class)
    fun `each filter gives the bench's whole-image result, pixel for pixel`(filter: PageFilter) {
        val page = shadowedPage(pencil = true)
        val expected = when (filter) {
            PageFilter.ORIGINAL -> page.pixels.copyOf()
            PageFilter.WHITE_PAPER -> Bench.backgroundDivide(page)
            PageFilter.GREY -> Bench.grayDivide(page).map { gray(it) }.toIntArray()
            PageFilter.BLACK_WHITE -> Bench.blackWhite(page).map { gray(it) }.toIntArray()
        }
        assertThat(filtered(filter, page).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun `white paper takes the shadow away and keeps the ink`() {
        val page = shadowedPage(400, 300)
        val out = filtered(PageFilter.WHITE_PAPER, page)
        val text = ScanTestImages.textPage()
        var paperMin = 255
        var inkMax = 0
        for (i in out.indices) {
            val x = i % 400
            val y = i / 400
            val luma = Packed.luma(out[i])
            // More than ≈ 70 pixels from the shadow's edge: the estimate is blurred on purpose (8-pixel blocks,
            // a 5-block maximum, two 7-block means), and a hard edge of light is a few blocks wide in it.
            if (kotlin.math.abs(x + y - 350) > 100) {
                if (text(x / 400.0, y / 300.0) == text(0.0, 0.0)) paperMin = min(paperMin, luma) else inkMax = max(inkMax, luma)
            }
        }
        assertThat(paperMin).isAtLeast(235)
        assertThat(inkMax).isAtMost(150)
        // In the photo, the paper in the shadow was darker than that.
        assertThat(Packed.luma(page.pixels[299 * 400 + 399])).isLessThan(140)
    }

    @Test
    fun `black and white is only black and white, in that place`() {
        val page = shadowedPage(400, 300)
        val out = filtered(PageFilter.BLACK_WHITE, page)
        assertThat(out.map { Packed.luma(it) }.toSet()).containsExactly(0, 255)
        // The paper in the shadow is white: its paper estimate removed the shadow.
        assertThat(out[299 * 400 + 399]).isEqualTo(gray(255))
        assertThat(out.all { it ushr 24 == 0xFF }).isTrue()
    }

    @Test
    fun `grey is grey, and opaque`() {
        val out = filtered(PageFilter.GREY, shadowedPage())
        val grey = out.all { Packed.red(it) == Packed.green(it) && Packed.green(it) == Packed.blue(it) && it ushr 24 == 0xFF }
        assertThat(grey).isTrue()
    }

    @Test
    fun `the page is read by bands, never whole`() {
        val page = shadowedPage()
        val rows = ArrayRows(page.width, page.height, page.pixels.copyOf())
        PageFilters.apply(PageFilter.WHITE_PAPER, rows)
        // One reading for the paper, one for the result: 20 bands of 8 rows each.
        assertThat(rows.reads).isEqualTo(2 * ((157 + 7) / 8))
    }

    @Test
    fun `a page of one pixel, or one row, is filtered without failing`() {
        for ((w, h) in listOf(1 to 1, 1 to 37, 41 to 1)) {
            val page = RgbImage(w, h, IntArray(w * h) { ScanTestImages.rgb(200, 180, 160) })
            PageFilter.entries.forEach { filter ->
                assertThat(filtered(filter, page).size).isEqualTo(w * h)
            }
        }
    }

    @Test
    fun `a cancellation between two bands stops the filter`() {
        val page = shadowedPage()
        val rows = ArrayRows(page.width, page.height, page.pixels.copyOf())
        var bands = 0
        assertThrows<IllegalStateException> {
            PageFilters.apply(PageFilter.BLACK_WHITE, rows) { check(++bands < 5) { "cancelled" } }
        }
        assertThat(bands).isEqualTo(5)
    }

    private fun gray(v: Int) = ScanTestImages.rgb(v, v, v)

    /** The bench's `PureFilters` (scanner-bench, 2026-10-12), whole image at once: the oracle. */
    // NestedBlockDepth, LoopWithTooManyJumpStatements: the bench's code as it is, so that it stays the oracle.
    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
    private object Bench {
        private fun boxMean(p: IntArray, w: Int, h: Int, r: Int): IntArray {
            val integ = LongArray((w + 1) * (h + 1))
            for (y in 0 until h) {
                var row = 0L
                for (x in 0 until w) {
                    row += p[y * w + x]
                    integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
                }
            }
            val out = IntArray(w * h)
            for (y in 0 until h) {
                val y0 = max(0, y - r)
                val y1 = min(h, y + r + 1)
                for (x in 0 until w) {
                    val x0 = max(0, x - r)
                    val x1 = min(w, x + r + 1)
                    val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                    out[y * w + x] = (s / ((y1 - y0) * (x1 - x0))).toInt()
                }
            }
            return out
        }

        private fun background(p: IntArray, w: Int, h: Int, f: Int = 8): IntArray {
            val sw = (w + f - 1) / f
            val sh = (h + f - 1) / f
            val s = IntArray(sw * sh)
            for (y in 0 until sh) {
                for (x in 0 until sw) {
                    var acc = 0
                    var n = 0
                    for (yy in y * f until min(h, y * f + f)) {
                        for (xx in x * f until min(w, x * f + f)) {
                            acc += p[yy * w + xx]
                            n++
                        }
                    }
                    s[y * sw + x] = acc / n
                }
            }
            val m = IntArray(sw * sh)
            for (y in 0 until sh) {
                for (x in 0 until sw) {
                    var v = 0
                    for (dy in -2..2) {
                        for (dx in -2..2) v = max(v, s[(y + dy).coerceIn(0, sh - 1) * sw + (x + dx).coerceIn(0, sw - 1)])
                    }
                    m[y * sw + x] = v
                }
            }
            val bg = boxMean(boxMean(m, sw, sh, 3), sw, sh, 3)
            val out = IntArray(w * h)
            for (y in 0 until h) {
                val fy = (y + 0.5) / f - 0.5
                val y0 = fy.toInt().coerceIn(0, sh - 1)
                val y1 = min(sh - 1, y0 + 1)
                val ty = (fy - y0).coerceIn(0.0, 1.0)
                for (x in 0 until w) {
                    val fx = (x + 0.5) / f - 0.5
                    val x0 = fx.toInt().coerceIn(0, sw - 1)
                    val x1 = min(sw - 1, x0 + 1)
                    val tx = (fx - x0).coerceIn(0.0, 1.0)
                    val a = bg[y0 * sw + x0] * (1 - tx) + bg[y0 * sw + x1] * tx
                    val b = bg[y1 * sw + x0] * (1 - tx) + bg[y1 * sw + x1] * tx
                    out[y * w + x] = max(1, (a * (1 - ty) + b * ty).toInt())
                }
            }
            return out
        }

        fun backgroundDivide(src: RgbImage): IntArray {
            val ch = arrayOf(
                IntArray(src.pixels.size) { (src.pixels[it] shr 16) and 255 },
                IntArray(src.pixels.size) { (src.pixels[it] shr 8) and 255 },
                IntArray(src.pixels.size) { src.pixels[it] and 255 },
            )
            for (p in ch) {
                val bg = background(p, src.width, src.height)
                for (i in p.indices) p[i] = min(255, p[i] * 255 / bg[i])
            }
            return IntArray(src.pixels.size) { ScanTestImages.rgb(ch[0][it], ch[1][it], ch[2][it]) }
        }

        fun grayDivide(src: RgbImage): IntArray {
            val g = IntArray(src.pixels.size) {
                val c = src.pixels[it]
                (((c shr 16) and 255) * 4899 + ((c shr 8) and 255) * 9617 + (c and 255) * 1868 + 8192) shr 14
            }
            val bg = background(g, src.width, src.height)
            for (i in g.indices) g[i] = min(255, g[i] * 255 / bg[i])
            return g
        }

        fun blackWhite(src: RgbImage): IntArray {
            val g = grayDivide(src)
            val hist = IntArray(256)
            for (p in g) hist[p]++
            val total = g.size.toDouble()
            var sum = 0.0
            for (i in 0 until 256) sum += i * hist[i].toDouble()
            var sumB = 0.0
            var wB = 0.0
            var best = 0.0
            var t = 0
            for (i in 0 until 256) {
                wB += hist[i]
                if (wB == 0.0) continue
                val wF = total - wB
                if (wF == 0.0) break
                sumB += i * hist[i].toDouble()
                val mB = sumB / wB
                val mF = (sum - sumB) / wF
                val between = wB * wF * (mB - mF) * (mB - mF)
                if (between > best) {
                    best = between
                    t = i
                }
            }
            val th = min(235, t + 10)
            return IntArray(g.size) { if (g[it] > th) 255 else 0 }
        }
    }
}
