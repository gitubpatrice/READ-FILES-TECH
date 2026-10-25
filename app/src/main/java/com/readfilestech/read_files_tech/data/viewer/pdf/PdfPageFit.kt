package com.readfilestech.read_files_tech.data.viewer.pdf

import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A page's size in points (1/72 inch), its `/Rotate` applied: what PdfRenderer reports. */
data class PageSize(val width: Float, val height: Float) {
    /** Drawable: a size of no area, or not a number (a hostile MediaBox), is laid out as a blank square. */
    val usable: Boolean get() = width.isFinite() && height.isFinite() && width > 0f && height > 0f
}

/** A rectangle of pixels, right and bottom excluded. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** The pixels this rectangle has in common with [other]; empty when none. */
    fun intersect(other: PixelRect): PixelRect =
        PixelRect(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))
}

/**
 * A render of part of a page: a bitmap of [width] × [height] pixels, onto which a point (x, y) of the page
 * goes to (x × [scale] + [dx], y × [scale] + [dy]). A [scale] of 0 draws nothing: the page has no usable size.
 */
data class PageRender(val width: Int, val height: Int, val scale: Float, val dx: Float, val dy: Float)

/**
 * How the PDF viewer lays a page out and renders it — ONE rule for the screen, which lays the pages out
 * before it knows them all, and for the `:pdf` process, which renders them (docs D-029).
 *
 * A page fills the column's width, in a box whose height follows the page's ratio clamped to
 * [MAX_ASPECT]: a page of 1 × 100 000 points is drawn inside a box 16 times taller than wide, never as a
 * column 100 000 times its width, nor as thousands of slivers on one screen (the corpus's huge MediaBox).
 * Pure: the same numbers on both sides, tested on the JVM.
 */
object PdfPageFit {
    /** The tallest (or widest) a page's box gets, against its width (or height). */
    const val MAX_ASPECT = 16f

    /** The height of [page]'s box in a column [width] pixels wide. */
    fun boxHeight(page: PageSize, width: Int): Int {
        val ratio = if (page.usable) (page.height / page.width).coerceIn(1 / MAX_ASPECT, MAX_ASPECT) else 1f
        return max(1, (width * ratio).roundToInt())
    }

    /**
     * The render of [region] of [page]'s box in a column [width] pixels wide — the whole box when null —
     * in at most [maxPixels] pixels, and never more than [FileCaps.IMAGE_PIXELS]: past them the bitmap is
     * smaller than the region, and drawn stretched over it. Null when [region] misses the box.
     */
    fun render(page: PageSize, width: Int, region: PixelRect?, maxPixels: Long): PageRender? {
        val box = PixelRect(0, 0, width, boxHeight(page, width))
        val wanted = (region ?: box).intersect(box)
        if (wanted.isEmpty || maxPixels <= 0) return null
        val area = wanted.width.toLong() * wanted.height
        val shrink = min(1.0, sqrt(min(maxPixels, FileCaps.IMAGE_PIXELS).toDouble() / area))
        val outWidth = max(1, floor(wanted.width * shrink).toInt())
        val outHeight = max(1, floor(wanted.height * shrink).toInt())
        return if (page.usable) placed(page, box, wanted, outWidth, outHeight) else PageRender(outWidth, outHeight, 0f, 0f, 0f)
    }

    /** [page] fitted and centred in its [box] — a page whose ratio was clamped leaves a margin — seen through [region]. */
    private fun placed(page: PageSize, box: PixelRect, region: PixelRect, width: Int, height: Int): PageRender {
        val fit = min(box.width / page.width, box.height / page.height)
        val left = (box.width - page.width * fit) / 2
        val top = (box.height - page.height * fit) / 2
        val x = width.toFloat() / region.width
        val y = height.toFloat() / region.height
        // One scale for both axes: the shrink is the same on both but for the rounding of the bitmap's sides.
        return PageRender(width, height, fit * min(x, y), (left - region.left) * x, (top - region.top) * y)
    }
}
