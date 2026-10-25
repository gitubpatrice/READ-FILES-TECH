package com.readfilestech.read_files_tech.data.scanner

import kotlin.math.max
import kotlin.math.min

/** What a scanned page looks like in the PDF. */
enum class PageFilter {
    /** The photo's colours, straightened. */
    ORIGINAL,

    /** Grey, the paper flattened to white: shadows and uneven light removed. */
    GREY,

    /** Black ink on white paper, from the flattened grey. */
    BLACK_WHITE,

    /** The colours kept, the paper flattened to white ("white paper"). */
    WHITE_PAPER,
}

/**
 * The rows of an image, read and written by bands: a straightened page of 3 500 pixels never needs a
 * second copy of itself in the Java heap (a phone's heap may be 256 MB; a Bitmap's pixels live outside it).
 * Pixels are packed `0xAARRGGBB`, as `Bitmap.getPixels` gives them.
 */
interface PixelRows {
    val width: Int
    val height: Int

    /** Rows [y] to [y] + [rows] − 1 into [into], row after row. */
    fun read(y: Int, rows: Int, into: IntArray)

    fun write(y: Int, rows: Int, from: IntArray)
}

/**
 * The page filters. "White paper" divides each pixel by an estimate of the paper's own colour around it —
 * MakeACopy's background division (Apache-2.0, its idea, not its code; RAPPORT-SCANNER §7) — so a shadow or
 * a gradient of light disappears while the ink keeps its contrast; OSS Document Scanner's own whitepaper
 * paled the text inside a shadow, and was not kept. The estimate: the page at one eighth of its size, a
 * 5 × 5 maximum (the ink is darker than the paper, so it leaves the estimate), two 7 × 7 box means, then
 * bilinear interpolation back to full size.
 */
object PageFilters {
    /** The paper estimate is computed at 1 / [BLOCK] of the page's size. */
    private const val BLOCK = 8
    private const val MAX_RADIUS = 2
    private const val BOX_RADIUS = 3
    private const val BAND_ROWS = BLOCK
    private const val LEVELS = 256

    /** Otsu's level on a flattened page sits between ink and paper: moved a little towards the paper. */
    private const val BW_BIAS = 10
    private const val BW_MAX_LEVEL = 235

    /** Applies [filter] to [page] in place; [checkpoint] runs between bands (a cancellation stops it). */
    fun apply(filter: PageFilter, page: PixelRows, checkpoint: () -> Unit = {}) {
        when (filter) {
            PageFilter.ORIGINAL -> Unit
            PageFilter.WHITE_PAPER -> {
                val papers = papers(page, COLOURS, checkpoint)
                transform(page, checkpoint) { x, y, c ->
                    Packed.rgb(
                        divide(Packed.red(c), papers[0].at(x, y)),
                        divide(Packed.green(c), papers[1].at(x, y)),
                        divide(Packed.blue(c), papers[2].at(x, y)),
                    )
                }
            }
            PageFilter.GREY -> {
                val paper = papers(page, LUMA, checkpoint).single()
                transform(page, checkpoint) { x, y, c -> Packed.gray(divide(Packed.luma(c), paper.at(x, y))) }
            }
            PageFilter.BLACK_WHITE -> {
                val paper = papers(page, LUMA, checkpoint).single()
                val histogram = IntArray(LEVELS)
                forEachPixel(page, checkpoint) { x, y, c -> histogram[divide(Packed.luma(c), paper.at(x, y))]++ }
                val level = min(BW_MAX_LEVEL, ScanImages.otsu(histogram) + BW_BIAS)
                transform(page, checkpoint) { x, y, c ->
                    Packed.gray(if (divide(Packed.luma(c), paper.at(x, y)) > level) Packed.MAX else 0)
                }
            }
        }
    }

    private fun divide(value: Int, paper: Int): Int = min(Packed.MAX, value * Packed.MAX / paper)

    private val COLOURS: List<(Int) -> Int> = listOf(Packed::red, Packed::green, Packed::blue)
    private val LUMA: List<(Int) -> Int> = listOf(Packed::luma)

    /**
     * The paper's estimated value of one channel, at 1 / [BLOCK] scale ([w] × [h]), read back at full size
     * ([width] pixels a row) by [at], bilinearly. The two grid rows around a page row are interpolated
     * across once and kept while the page's rows stay between them (eight rows): a pixel then costs one
     * interpolation, not three. The arithmetic is the bench's, operation for operation.
     */
    private class Paper(private val w: Int, private val h: Int, private val values: IntArray, width: Int) {
        private val x0 = IntArray(width)
        private val x1 = IntArray(width)
        private val tx = DoubleArray(width)
        private val top = DoubleArray(width)
        private val bottom = DoubleArray(width)
        private var gridRow = -1

        init {
            for (x in 0 until width) {
                val fx = (x + HALF) / BLOCK - HALF
                x0[x] = fx.toInt().coerceIn(0, w - 1)
                x1[x] = min(w - 1, x0[x] + 1)
                tx[x] = (fx - x0[x]).coerceIn(0.0, 1.0)
            }
        }

        fun at(x: Int, y: Int): Int {
            val fy = (y + HALF) / BLOCK - HALF
            val y0 = fy.toInt().coerceIn(0, h - 1)
            if (y0 != gridRow) across(y0)
            val ty = (fy - y0).coerceIn(0.0, 1.0)
            return max(1, (top[x] * (1 - ty) + bottom[x] * ty).toInt())
        }

        private fun across(y0: Int) {
            val y1 = min(h - 1, y0 + 1)
            for (x in top.indices) {
                top[x] = values[y0 * w + x0[x]] * (1 - tx[x]) + values[y0 * w + x1[x]] * tx[x]
                bottom[x] = values[y1 * w + x0[x]] * (1 - tx[x]) + values[y1 * w + x1[x]] * tx[x]
            }
            gridRow = y0
        }

        private companion object {
            const val HALF = 0.5
        }
    }

    /** The paper's estimate of each of [channels], in one reading of the page. */
    private fun papers(page: PixelRows, channels: List<(Int) -> Int>, checkpoint: () -> Unit): List<Paper> {
        val sw = (page.width + BLOCK - 1) / BLOCK
        val sh = (page.height + BLOCK - 1) / BLOCK
        val sums = List(channels.size) { LongArray(sw * sh) }
        val counts = IntArray(sw * sh)
        forEachPixel(page, checkpoint) { x, y, c ->
            val k = (y / BLOCK) * sw + x / BLOCK
            for (i in channels.indices) sums[i][k] += channels[i](c).toLong()
            counts[k]++
        }
        return sums.map { channel ->
            val means = IntArray(sw * sh) { (channel[it] / counts[it]).toInt() }
            Paper(sw, sh, boxMean(boxMean(maxFilter(means, sw, sh), sw, sh), sw, sh), page.width)
        }
    }

    /** The largest value in a 5 × 5 window, clamped at the edges. */
    private fun maxFilter(p: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) out[y * w + x] = windowMax(p, w, h, x, y)
        }
        return out
    }

    private fun windowMax(p: IntArray, w: Int, h: Int, x: Int, y: Int): Int {
        var v = 0
        for (dy in -MAX_RADIUS..MAX_RADIUS) {
            val row = (y + dy).coerceIn(0, h - 1) * w
            for (dx in -MAX_RADIUS..MAX_RADIUS) v = max(v, p[row + (x + dx).coerceIn(0, w - 1)])
        }
        return v
    }

    /** The mean of a 7 × 7 window, the window clipped to the image (an integral image). */
    private fun boxMean(p: IntArray, w: Int, h: Int): IntArray {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += p[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - BOX_RADIUS)
            val y1 = min(h, y + BOX_RADIUS + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - BOX_RADIUS)
                val x1 = min(w, x + BOX_RADIUS + 1)
                val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] -
                    integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                out[y * w + x] = (s / ((y1 - y0) * (x1 - x0))).toInt()
            }
        }
        return out
    }

    /** Every pixel, read band by band. */
    private inline fun forEachPixel(page: PixelRows, checkpoint: () -> Unit, action: (x: Int, y: Int, c: Int) -> Unit) {
        val band = IntArray(page.width * BAND_ROWS)
        var y0 = 0
        while (y0 < page.height) {
            checkpoint()
            val rows = min(BAND_ROWS, page.height - y0)
            page.read(y0, rows, band)
            for (r in 0 until rows) {
                for (x in 0 until page.width) action(x, y0 + r, band[r * page.width + x])
            }
            y0 += rows
        }
    }

    /** Every pixel replaced by [map]'s value, band by band. */
    private inline fun transform(page: PixelRows, checkpoint: () -> Unit, map: (x: Int, y: Int, c: Int) -> Int) {
        val band = IntArray(page.width * BAND_ROWS)
        var y0 = 0
        while (y0 < page.height) {
            checkpoint()
            val rows = min(BAND_ROWS, page.height - y0)
            page.read(y0, rows, band)
            for (r in 0 until rows) {
                for (x in 0 until page.width) {
                    val i = r * page.width + x
                    band[i] = map(x, y0 + r, band[i])
                }
            }
            page.write(y0, rows, band)
            y0 += rows
        }
    }
}
