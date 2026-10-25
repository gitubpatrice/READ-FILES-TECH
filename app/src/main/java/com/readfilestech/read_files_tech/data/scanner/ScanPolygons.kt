/*
 * Portions ported from OSS Document Scanner (https://github.com/ossappscollective/OSS-DocumentScanner),
 * Copyright (c) 2022 Martin Guillon, MIT License — full text in NOTICE.
 */
package com.readfilestech.read_files_tech.data.scanner

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** The element after [i], around a closed polygon. */
internal fun <T> List<T>.after(i: Int): T = this[(i + 1) % size]

/** The element before [i], around a closed polygon. */
internal fun <T> List<T>.before(i: Int): T = this[(i + size - 1) % size]

/** A point of an image, in pixels or in fractions of its size. */
data class Point(val x: Double, val y: Double) {
    fun distanceTo(o: Point): Double = hypot(x - o.x, y - o.y)
}

/** Polygons: the contours' simplification, the quadrilaterals' tests, and their clipping. */
object ScanPolygons {
    private const val FARTHEST_POINT_ROUNDS = 3

    /** A polygon has three points at least. */
    const val MIN_POLYGON = 3
    private const val SHIFT_32 = 32
    private const val LOW_32 = 0xffffffffL
    private const val TINY = 1e-9
    private const val COS_EPSILON = 1e-10

    /**
     * Closed Douglas-Peucker, as `cv::approxPolyDP` for a closed curve: a pair of far-apart points first
     * (three farthest-point rounds from point 0), then both chains between them simplified. Returns the
     * indices of the points kept, in the contour's order.
     */
    fun approxClosed(c: Contour, eps: Double): IntArray {
        val n = c.size
        if (n < MIN_POLYGON) return IntArray(n) { it }
        var a = 0
        var b = 0
        repeat(FARTHEST_POINT_ROUNDS) { round ->
            b = farthestFrom(c, a)
            if (round < FARTHEST_POINT_ROUNDS - 1) {
                val t = a
                a = b
                b = t
            }
        }
        return if (a == b) intArrayOf(a) else keptBetween(c, a, b, eps)
    }

    /** The points of [c] kept when both chains between [a] and [b] are simplified, from [a] in order. */
    private fun keptBetween(c: Contour, a: Int, b: Int, eps: Double): IntArray {
        val n = c.size
        val keep = BooleanArray(n)
        keep[a] = true
        keep[b] = true
        simplify(c, a, b, eps, keep)
        simplify(c, b, a, eps, keep)
        val out = IntArray(keep.count { it })
        var j = 0
        // From a, in the contour's order.
        for (s in 0 until n) {
            val i = (a + s) % n
            if (keep[i]) out[j++] = i
        }
        return out
    }

    private fun farthestFrom(c: Contour, from: Int): Int {
        var best = -1L
        var index = 0
        for (i in 0 until c.size) {
            val dx = (c.xs[i] - c.xs[from]).toLong()
            val dy = (c.ys[i] - c.ys[from]).toLong()
            val d = dx * dx + dy * dy
            if (d > best) {
                best = d
                index = i
            }
        }
        return index
    }

    /** Keeps, between [from] and [to] along the contour, every point farther than [eps] from its chord. */
    private fun simplify(c: Contour, from: Int, to: Int, eps: Double, keep: BooleanArray) {
        val n = c.size
        val stack = ArrayDeque<Long>()
        stack.addLast(span(from, to))
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            val s = (v shr SHIFT_32).toInt()
            val e = (v and LOW_32).toInt()
            if (((e - s) % n + n) % n >= 2) {
                val far = farthestFromChord(c, s, e)
                if (far.second > eps && far.first >= 0) {
                    keep[far.first] = true
                    stack.addLast(span(s, far.first))
                    stack.addLast(span(far.first, e))
                }
            }
        }
    }

    private fun span(start: Int, end: Int): Long = (start.toLong() shl SHIFT_32) or end.toLong()

    /** The index of the point between [s] and [e] farthest from the chord s-e, and its distance. */
    private fun farthestFromChord(c: Contour, s: Int, e: Int): Pair<Int, Double> {
        val n = c.size
        val x0 = c.xs[s].toDouble()
        val y0 = c.ys[s].toDouble()
        val dx = c.xs[e] - x0
        val dy = c.ys[e] - y0
        val norm = sqrt(dx * dx + dy * dy)
        var best = -1.0
        var index = -1
        var i = (s + 1) % n
        while (i != e) {
            val px = c.xs[i] - x0
            val py = c.ys[i] - y0
            val d = if (norm < TINY) sqrt(px * px + py * py) else abs(px * dy - py * dx) / norm
            if (d > best) {
                best = d
                index = i
            }
            i = (i + 1) % n
        }
        return index to best
    }

    /** Whether the polygon turns always the same way (collinear points allowed, a flat one is not). */
    fun isConvex(points: List<Point>): Boolean {
        val n = points.size
        var sign = 0
        for (i in 0 until n) {
            val a = points[i]
            val b = points[(i + 1) % n]
            val c = points[(i + 2) % n]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0 && sign != 0 && s != sign) return false
            if (s != 0) sign = s
        }
        return sign != 0
    }

    /** The largest |cos| of the four corner angles: 0 for a rectangle (OSS Document Scanner's criterion). */
    fun maxCos(q: List<Point>): Double {
        var m = 0.0
        for (i in q.indices) {
            val p = q.before(i)
            val o = q[i]
            val r = q.after(i)
            val ux = p.x - o.x
            val uy = p.y - o.y
            val vx = r.x - o.x
            val vy = r.y - o.y
            m = max(m, abs(ux * vx + uy * vy) / sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy) + COS_EPSILON))
        }
        return m
    }

    fun signedArea(p: List<Point>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }

    fun area(p: List<Point>): Double = abs(signedArea(p))
}

/** Clipping one polygon by another: how much of a candidate lies inside a larger one. */
object PolygonClip {
    private const val CROSS_TINY = 1e-12

    /** Sutherland-Hodgman: [subject] clipped by the convex polygon [clip], both in either orientation. */
    fun clip(subject: List<Point>, clip: List<Point>): List<Point> {
        var out = counterClockwise(subject)
        val c = counterClockwise(clip)
        for (i in c.indices) {
            if (out.isEmpty()) break
            out = clipByEdge(out, c[i], c[(i + 1) % c.size])
        }
        return out
    }

    private fun counterClockwise(p: List<Point>): List<Point> = if (ScanPolygons.signedArea(p) < 0) p.reversed() else p

    private fun clipByEdge(input: List<Point>, a: Point, b: Point): List<Point> {
        fun inside(p: Point) = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x) >= 0
        val out = ArrayList<Point>()
        for (j in input.indices) {
            val p = input[j]
            val q = input[(j + 1) % input.size]
            val pIn = inside(p)
            val qIn = inside(q)
            if (pIn) out.add(p)
            if (pIn != qIn) out.add(intersection(a, b, p, q))
        }
        return out
    }

    /** Where the line a-b meets the segment p-q ([p] when they are parallel). */
    private fun intersection(a: Point, b: Point, p: Point, q: Point): Point {
        val a1 = b.y - a.y
        val b1 = a.x - b.x
        val c1 = a1 * a.x + b1 * a.y
        val a2 = q.y - p.y
        val b2 = p.x - q.x
        val c2 = a2 * p.x + b2 * p.y
        val det = a1 * b2 - a2 * b1
        if (abs(det) < CROSS_TINY) return p
        return Point((b2 * c1 - b1 * c2) / det, (a1 * c2 - a2 * c1) / det)
    }
}
