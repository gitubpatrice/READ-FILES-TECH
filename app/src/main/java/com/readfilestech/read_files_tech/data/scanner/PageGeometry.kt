package com.readfilestech.read_files_tech.data.scanner

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A size in pixels. */
data class PixelSize(val width: Int, val height: Int) {
    val longSide: Int get() = max(width, height)
}

/**
 * The geometry of a page: its quadrilateral (fractions of the upright photo: top-left, top-right,
 * bottom-right, bottom-left), the quarter turns the user added, the size of the straightened page.
 */
object PageGeometry {
    /** A page has four corners. */
    const val CORNERS = 4

    /** The long side of a straightened page: ≈ 300 dpi for A4, and a bound on memory (a 256 MB heap on an S9). */
    const val MAX_PAGE_SIDE = 3500

    /** A quadrilateral smaller than this fraction of the photo is not a page (a corner dragged onto another). */
    private const val MIN_AREA = 0.01
    private const val QUARTER_TURNS = 4

    /** The whole photo: what the crop screen starts from when nothing was found. */
    val WHOLE: List<Point> = listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0))

    /** Whether [quad] can be straightened: four corners inside the photo, convex, not a sliver. */
    fun isUsable(quad: List<Point>): Boolean = quad.size == CORNERS &&
        quad.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 } &&
        ScanPolygons.isConvex(quad) &&
        ScanPolygons.area(quad) >= MIN_AREA

    /** [quad] in the pixels of an image of [size]. */
    fun inPixels(quad: List<Point>, size: PixelSize): List<Point> = quad.map {
        Point(it.x * size.width, it.y * size.height)
    }

    /**
     * The straightened page's size for [quad] (in pixels of the upright photo), turned by [turns] quarters:
     * its longest opposite sides, then scaled down to [maxSide] if larger.
     */
    fun pageSize(quad: List<Point>, turns: Int, maxSide: Int = MAX_PAGE_SIDE): PixelSize {
        // Top-left, top-right, bottom-right, bottom-left.
        val w = max(quad[0].distanceTo(quad[1]), quad.last().distanceTo(quad[2]))
        val h = max(quad[0].distanceTo(quad.last()), quad[1].distanceTo(quad[2]))
        val (tw, th) = if (normalized(turns) % 2 == 1) h to w else w to h
        val scale = min(1.0, maxSide / max(tw, th))
        return PixelSize(max(1, (tw * scale).roundToInt()), max(1, (th * scale).roundToInt()))
    }

    /**
     * Where each corner of the quadrilateral lands in a page of [size], turned by [turns] quarters
     * clockwise: unturned, top-left goes to (0, 0), top-right to (width, 0), and so on.
     */
    fun pageCorners(size: PixelSize, turns: Int): List<Point> {
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        val clockwise = listOf(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
        return List(clockwise.size) { clockwise[(it + normalized(turns)) % QUARTER_TURNS] }
    }

    /** Quarter turns brought to 0..3. */
    fun normalized(turns: Int): Int = Math.floorMod(turns, QUARTER_TURNS)
}
