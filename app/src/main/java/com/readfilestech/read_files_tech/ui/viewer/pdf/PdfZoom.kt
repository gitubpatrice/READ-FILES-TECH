package com.readfilestech.read_files_tech.ui.viewer.pdf

import androidx.compose.ui.geometry.Offset
import com.readfilestech.read_files_tech.data.viewer.pdf.PixelRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The zoom of the PDF viewer's column of pages — Syncfusion's of the 2.15.3: from fitted to the width to
 * [MAX_SCALE] times, a double tap to [DOUBLE_TAP_SCALE] (`pdfviewer.dart` 33.2.4: `maxZoomLevel = 3`,
 * `_maximumZoomLevel = 2.0`). The column is scaled by [scale] from its top left corner, then shifted by
 * [x] pixels; up and down are the list's own scroll, which a pinch moves too, so that the point under
 * the fingers stays under them. Pure, tested on the JVM.
 */
data class PdfZoom(val scale: Float = 1f, val x: Float = 0f) {
    /** Larger than fitted: one finger moves the pages sideways too, and the visible ones are rendered sharper. */
    val zoomed: Boolean get() = scale > 1f + SNAP

    /** A zoom, and the scroll the list makes with it, in the list's own (unscaled) pixels. */
    data class Step(val zoom: PdfZoom, val scroll: Float)

    /** A pinch of [factor] around [centroid] and a move of [pan], in the screen's pixels, over a column [width] pixels wide. */
    fun transformed(centroid: Offset, pan: Offset, factor: Float, width: Float): Step {
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val shifted = centroid.x - (centroid.x - x) * (next / scale) + pan.x
        // The point under the centroid is centroid.y / scale below the list's top: after, at centroid.y / next.
        val scroll = centroid.y / scale - centroid.y / next - pan.y / next
        return Step(PdfZoom(next, shifted).within(width), scroll)
    }

    /** One finger, zoomed: the sideways part of [panX]; the list scrolls by itself. */
    fun panned(panX: Float, width: Float): PdfZoom = copy(x = x + panX).within(width)

    /** Back to fitted when zoomed, else [DOUBLE_TAP_SCALE] times — around the point tapped, both ways. */
    fun doubleTapped(at: Offset, width: Float): Step =
        transformed(at, Offset.Zero, (if (zoomed) MIN_SCALE else DOUBLE_TAP_SCALE) / scale, width)

    /** Never past the column's edges. */
    private fun within(width: Float) = copy(x = x.coerceIn(width - width * scale, 0f))

    companion object {
        val NONE = PdfZoom()
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 3f
        const val DOUBLE_TAP_SCALE = 2f

        /** A pinch that ends this close to fitted has not zoomed. */
        private const val SNAP = 0.01f
    }
}

/** A page the list shows: its index, and its box's top and height in the list's own pixels. */
data class VisiblePage(val index: Int, val offset: Int, val size: Int)

/**
 * A sharper render of the part of a page the screen shows when zoomed: [region] of the page's box in a
 * column [width] pixels wide (the column at the zoom), drawn over the page's rectangle ([left], [top],
 * [right], [bottom]) in the page's own pixels.
 */
data class TileRequest(
    val index: Int,
    val width: Int,
    val region: PixelRect,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** What the PDF viewer renders, and which page it says it is on. Pure, tested on the JVM. */
object PdfView {
    /**
     * The tiles of the [pages] seen through a screen [width] × [height] at [zoom]: one per page, the part of
     * it on screen, at the screen's own resolution — all of them together never more pixels than the screen.
     * None at the fitted size, where the pages' own renders are sharp.
     */
    fun tiles(pages: List<VisiblePage>, width: Int, height: Int, zoom: PdfZoom): List<TileRequest> {
        if (!zoom.zoomed || width <= 0 || height <= 0) return emptyList()
        val column = (width * zoom.scale).roundToInt()
        val screen = PixelRect(0, 0, width, height)
        return pages.mapNotNull { page ->
            val left = floor(zoom.x).toInt()
            val top = floor(page.offset * zoom.scale).toInt()
            val bottom = ceil((page.offset + page.size) * zoom.scale).toInt()
            val shown = PixelRect(left, top, left + column, bottom).intersect(screen)
            if (shown.isEmpty) return@mapNotNull null
            val region = PixelRect(shown.left - left, shown.top - top, shown.right - left, shown.bottom - top)
            TileRequest(
                index = page.index,
                width = column,
                region = region,
                left = region.left / zoom.scale,
                top = region.top / zoom.scale,
                right = region.right / zoom.scale,
                bottom = region.bottom / zoom.scale,
            )
        }
    }

    /**
     * The page under the middle of a screen [height] pixels tall at [zoom] (the 2.15.3's counter followed
     * the page shown); the next one when the middle falls between two, the last one past them all.
     */
    fun currentPage(pages: List<VisiblePage>, height: Int, zoom: PdfZoom): Int? {
        val middle = height / 2f / zoom.scale
        val under = pages.firstOrNull { middle < it.offset + it.size }
        return (under ?: pages.lastOrNull())?.index
    }

    /**
     * The page typed in "Go to page", 0-based, or null when the text is not one of 1 to [pageCount] — the
     * 2.15.3 closed its dialog on anything typed, a wrong number doing nothing (F25).
     */
    fun pageTyped(text: String, pageCount: Int): Int? {
        val digits = text.trim()
        if (digits.isEmpty() || digits.length > MAX_DIGITS || !digits.all { it in '0'..'9' }) return null
        return digits.toInt().takeIf { it in 1..pageCount }?.minus(1)
    }

    /** More digits than any page count (an Int has ten). */
    private const val MAX_DIGITS = 9
}
