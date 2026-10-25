package com.readfilestech.read_files_tech.data.scanner

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Snaps a detected quadrilateral onto the page's edges in a larger image: the detection works on 300
 * pixels, so its corners are off by a few pixels of the photo. Each side is fitted again on the
 * strongest luminance gradient found along its normal, then the four lines are intersected — the
 * principle of MakeACopy's `EdgeSnapCornerRefiner` (Apache-2.0), written independently for the bench
 * (RAPPORT-SCANNER §5: median corner error 0.95 % → 0.70 % of the diagonal, SmartDoc 1.37 % → 0.45 %).
 *
 * A side keeps its place when the evidence is weak; the whole refinement is dropped when it would move a
 * corner by more than a few working pixels — it corrects quantisation, never the detection.
 */
object EdgeRefiner {
    /** The long side of the image the refinement works on. */
    const val REFINE_SIDE = 1600

    /** Below this many pixels on a side, nothing is refined. */
    private const val MIN_SIDE = 16
    private const val MIN_RADIUS = 4.0
    private const val RADIUS_IN_WORK_PIXELS = 1.6
    private const val MAX_CORNER_SHIFT = 2.5
    private const val SEARCH_STEP = 0.5
    private const val MIN_SAMPLES = 12
    private const val PIXELS_PER_SAMPLE = 6
    private const val SIDE_MARGIN = 0.1
    private const val SIDE_SPAN = 0.8
    private const val MIN_GRADIENT = 12.0
    private const val ROBUST_ROUNDS = 2

    /** A side is fitted only on a third of its samples or more. */
    private const val MIN_HITS_DIVISOR = 3
    private const val OUTLIER_FACTOR = 2.5

    /** cos 3°: a fitted side may not turn further from the detected one. */
    private const val MIN_ALIGNMENT = 0.9986
    private const val PARALLEL = 1e-9

    /**
     * [quad] (top-left, top-right, bottom-right, bottom-left, in the pixels of a [frame]-sized image),
     * snapped onto the gradients of [gray] — the same picture at [scale] times fewer pixels, already
     * smoothed — given that the detection worked at [workScale] times fewer pixels than the frame.
     */
    fun refine(gray: GrayImage, quad: List<Point>, scale: Double, workScale: Double, frame: PixelSize): List<Point> {
        if (gray.width < MIN_SIDE || gray.height < MIN_SIDE) return quad
        val pts = quad.map { Point(it.x / scale, it.y / scale) }
        // The search band: about 1.6 working pixels on each side of the detected edge.
        val radius = max(MIN_RADIUS, RADIUS_IN_WORK_PIXELS * workScale / scale)
        val lines = pts.indices.map { fitSide(gray, pts[it], pts.after(it), radius) }
        // A side without evidence keeps its place; a corner that would move too far drops the refinement.
        val corners = pts.indices.map { i ->
            val l1 = lines.before(i) ?: Line.through(pts.before(i), pts[i])
            val l2 = lines[i] ?: Line.through(pts[i], pts.after(i))
            l1.intersect(l2)?.takeIf { it.distanceTo(pts[i]) <= radius * MAX_CORNER_SHIFT }
        }
        return if (corners.any { it == null }) {
            quad
        } else {
            corners.filterNotNull().map {
                Point((it.x * scale).coerceIn(0.0, frame.width.toDouble()), (it.y * scale).coerceIn(0.0, frame.height.toDouble()))
            }
        }
    }

    /** A line as `nx·x + ny·y = d`, (nx, ny) a unit normal. */
    private class Line(val nx: Double, val ny: Double, val d: Double) {
        fun intersect(o: Line): Point? {
            val det = nx * o.ny - ny * o.nx
            return if (abs(det) < PARALLEL) null else Point((d * o.ny - ny * o.d) / det, (nx * o.d - d * o.nx) / det)
        }

        fun distanceTo(x: Double, y: Double): Double = abs(nx * x + ny * y - d)

        companion object {
            fun through(a: Point, b: Point): Line {
                val l = hypot(b.x - a.x, b.y - a.y)
                val nx = -(b.y - a.y) / l
                val ny = (b.x - a.x) / l
                return Line(nx, ny, nx * a.x + ny * a.y)
            }
        }
    }

    /** One edge sample: where the gradient peaks along the normal, and its sign. */
    private class Hit(val x: Double, val y: Double, val positive: Boolean)

    /** The side a-b fitted on the image's gradients, or null when they do not show it clearly. */
    private fun fitSide(g: GrayImage, a: Point, b: Point, radius: Double): Line? {
        val base = Line.through(a, b)
        val n = max(MIN_SAMPLES, (a.distanceTo(b) / PIXELS_PER_SAMPLE).toInt())
        val hits = (0 until n).mapNotNull { k ->
            val t = SIDE_MARGIN + SIDE_SPAN * k / (n - 1)
            strongestAlongNormal(g, Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t), base, radius)
        }
        val enough = n / MIN_HITS_DIVISOR
        // The dominant polarity: the paper brighter (or darker) than the background on one side.
        val positive = hits.count { it.positive } * 2 >= hits.size
        val kept = hits.filter { it.positive == positive }.takeIf { hits.size >= enough && it.size >= enough }
        return kept?.let { robustFit(it, enough) }?.takeIf { abs(it.nx * base.nx + it.ny * base.ny) >= MIN_ALIGNMENT }
    }

    /** A line through [points], refitted twice without the points far from it; null when too few remain. */
    private fun robustFit(points: List<Hit>, enough: Int): Line? {
        var kept = points
        var line = fitLine(kept)
        var round = 0
        while (line != null && round < ROBUST_ROUNDS) {
            val fitted: Line = line
            val residuals = kept.map { fitted.distanceTo(it.x, it.y) }
            val tolerance = max(1.0, residuals.sorted()[residuals.size / 2] * OUTLIER_FACTOR)
            kept = kept.filterIndexed { i, _ -> residuals[i] <= tolerance }
            line = if (kept.size >= enough) fitLine(kept) else null
            round++
        }
        return line
    }

    private fun strongestAlongNormal(g: GrayImage, c: Point, base: Line, radius: Double): Hit? {
        var best = 0.0
        var bestOffset = 0.0
        var off = -radius
        while (off <= radius) {
            val ahead = sample(g, c.x + base.nx * (off + 1), c.y + base.ny * (off + 1))
            val behind = sample(g, c.x + base.nx * (off - 1), c.y + base.ny * (off - 1))
            val d = ahead - behind
            if (abs(d) > abs(best)) {
                best = d
                bestOffset = off
            }
            off += SEARCH_STEP
        }
        return if (abs(best) < MIN_GRADIENT) null else Hit(c.x + base.nx * bestOffset, c.y + base.ny * bestOffset, best > 0)
    }

    /** Bilinear sample, clamped to the image. */
    private fun sample(g: GrayImage, x: Double, y: Double): Double {
        val xi = x.toInt().coerceIn(0, g.width - 2)
        val yi = y.toInt().coerceIn(0, g.height - 2)
        val fx = (x - xi).coerceIn(0.0, 1.0)
        val fy = (y - yi).coerceIn(0.0, 1.0)
        return g[xi, yi] * (1 - fx) * (1 - fy) + g[xi + 1, yi] * fx * (1 - fy) + g[xi, yi + 1] * (1 - fx) * fy + g[xi + 1, yi + 1] * fx * fy
    }

    /** Total least squares: the line through the points' centre along their main direction. */
    private fun fitLine(points: List<Hit>): Line? {
        if (points.size < 2) return null
        val mx = points.sumOf { it.x } / points.size
        val my = points.sumOf { it.y } / points.size
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in points) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val theta = atan2(2 * sxy, sxx - syy) / 2
        val nx = -sin(theta)
        val ny = cos(theta)
        return Line(nx, ny, nx * mx + ny * my)
    }
}
