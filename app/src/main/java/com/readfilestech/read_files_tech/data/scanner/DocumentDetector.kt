/*
 * Portions ported from OSS Document Scanner (https://github.com/ossappscollective/OSS-DocumentScanner),
 * Copyright (c) 2022 Martin Guillon, MIT License — full text in NOTICE.
 */
package com.readfilestech.read_files_tech.data.scanner

/**
 * Finds a document's four corners in a small image — OSS Document Scanner's still-photo detection
 * (`cpp/src/DocumentDetector.cpp`, MIT) rewritten in pure Kotlin, with the improvements the bench kept
 * (`pure+oc30+fb4+refine`, RAPPORT-SCANNER §5: 33 % → 64 % good frames on SmartDoc's real photos).
 *
 * Upstream: a 10-pixel black border, a 3×3 median; for each of the blue, green and red channels a
 * binary threshold at 160 (closed 4×4, dilated 3×3), then Canny at (2t, 4t) for t = 60, 50 … 10 (dilated
 * 3×3); every border approximated at 2 % of its perimeter; the convex quadrilaterals with every corner
 * angle's |cos| below 0.4, between 10 % and 92 % of the image, ranked by area + 3 000 000 × (1 − max|cos|):
 * the angle outweighs the area.
 *
 * Kept improvements:
 * - every pass is run (no early exit), then the choice climbs to an ENCLOSING quadrilateral at least
 *   1.12 × larger, containing 92 % of it, with max|cos| below 0.30: an inner photo or table, or the lit
 *   part of a page crossed by a shadow, was the commonest wrong answer (19 of the base's 20);
 * - a document between 4 % and 10 % of the image (far away, a receipt) only when nothing larger exists;
 * - the corners are then snapped to the gradients of a larger image ([EdgeRefiner]).
 *
 * One change for speed, none for the result: the 4 % candidates are gathered in the same sweep as the
 * 10 % ones (the bench swept again), since nothing else differs between the two.
 */
object DocumentDetector {
    /** The long side of the image the detection works on (OSS Document Scanner's `resizeThreshold` for a photo). */
    const val WORK_SIDE = 300

    private const val BORDER = 10
    private const val BINARY_THRESHOLD = 160
    private const val CLOSE_KERNEL = 4
    private const val DILATE_KERNEL = 3
    private const val CANNY_FIRST = 60
    private const val CANNY_LAST = 10
    private const val CANNY_STEP = 10
    private const val CANNY_LOW_FACTOR = 2.0
    private const val CANNY_HIGH_FACTOR = 4.0
    private const val MIN_AREA = 0.10
    private const val FALLBACK_MIN_AREA = 0.04
    private const val MAX_AREA = 0.92
    private const val MIN_PERIMETER = 100.0
    private const val APPROX_EPSILON = 0.02
    private const val MAX_COS = 0.4
    private const val ANGLE_WEIGHT = 3_000_000
    private const val OUTER_MIN_GROWTH = 1.12
    private const val OUTER_MIN_COVER = 0.92
    private const val OUTER_MAX_COS = 0.30

    /** Channels in OSS Document Scanner's order: blue, green, red. */
    private val CHANNELS = intArrayOf(2, 1, 0)

    private class Candidate(val corners: List<Point>, val area: Double, val maxCos: Double, val weight: Int) {
        val rank: Double get() = area + weight.toDouble() * (1 - maxCos)
    }

    /**
     * The document in [photo] — its four corners as fractions of the photo's width and height, top-left,
     * top-right, bottom-right, bottom-left — or null. Detected on a [WORK_SIDE] image, then refined on a
     * [EdgeRefiner.REFINE_SIDE] one, both made from [photo] (decoded at least that large when it can be).
     */
    fun locate(photo: RgbImage, checkpoint: () -> Unit = {}): List<Point>? {
        val work = ScanImages.downscale(photo, WORK_SIDE)
        val found = detect(work, checkpoint) ?: return null
        val longSide = maxOf(photo.width, photo.height).toDouble()
        val workScale = longSide / maxOf(work.width, work.height)
        val sharp = ScanImages.downscale(photo, EdgeRefiner.REFINE_SIDE)
        checkpoint()
        val gray = ScanImages.gauss5(ScanImages.luma(sharp))
        val refined = EdgeRefiner.refine(
            gray,
            found.map { Point(it.x * workScale, it.y * workScale) },
            scale = longSide / maxOf(sharp.width, sharp.height),
            workScale = workScale,
            frame = PixelSize(photo.width, photo.height),
        )
        return refined.map { Point(it.x / photo.width, it.y / photo.height) }
    }

    /**
     * The document's corners in [work]'s pixels — top-left, top-right, bottom-right, bottom-left — or null
     * when nothing looks like one. [checkpoint] runs between passes: a cancellation thrown there stops it.
     */
    fun detect(work: RgbImage, checkpoint: () -> Unit = {}): List<Point>? {
        val padded = pad(work)
        val frameArea = padded.width.toDouble() * padded.height
        val found = ArrayList<Candidate>()
        var weight = ANGLE_WEIGHT
        for (channel in CHANNELS) {
            val plane = ScanImages.median3(ScanImages.channel(padded, channel))
            val binary = ScanEdges.dilate(ScanEdges.close(ScanImages.threshold(plane, BINARY_THRESHOLD), CLOSE_KERNEL), DILATE_KERNEL)
            collect(binary, padded, found, weight--)
            checkpoint()
            for (t in CANNY_FIRST downTo CANNY_LAST step CANNY_STEP) {
                val edges = ScanEdges.dilate(ScanEdges.canny(plane, CANNY_LOW_FACTOR * t, CANNY_HIGH_FACTOR * t), DILATE_KERNEL)
                collect(edges, padded, found, weight--)
                checkpoint()
            }
        }
        // Two-stage area rule: a small document only when nothing of 10 % or more was found.
        val large = found.filter { it.area >= frameArea * MIN_AREA }
        val pool = large.ifEmpty { found }
        if (pool.isEmpty()) return null
        val pick = climbToEnclosing(pool.sortedByDescending { it.rank })
        val corners = pick.corners.map {
            Point((it.x - BORDER).coerceIn(0.0, work.width.toDouble()), (it.y - BORDER).coerceIn(0.0, work.height.toDouble()))
        }
        return sortCorners(corners)
    }

    /** OSS Document Scanner's `sortPoints`: the two highest by x (left, right), then the two lowest by x descending. */
    private fun sortCorners(p: List<Point>): List<Point> {
        val byY = p.sortedBy { it.y }
        val half = p.size / 2
        return byY.subList(0, half).sortedBy { it.x } + byY.subList(half, p.size).sortedByDescending { it.x }
    }

    /** From the best-ranked candidate, up to the largest enclosing one, as long as one qualifies. */
    private fun climbToEnclosing(ranked: List<Candidate>): Candidate {
        var pick = ranked.first()
        while (true) {
            val current = pick
            val better = ranked.filter {
                it !== current && it.area >= current.area * OUTER_MIN_GROWTH && it.maxCos < OUTER_MAX_COS && containsMostly(it, current)
            }.maxByOrNull { it.area } ?: return current
            pick = better
        }
    }

    /** At least 92 % of [inner]'s area inside [outer]. */
    private fun containsMostly(outer: Candidate, inner: Candidate): Boolean {
        val overlap = PolygonClip.clip(inner.corners, outer.corners)
        return overlap.size >= ScanPolygons.MIN_POLYGON && ScanPolygons.area(overlap) >= OUTER_MIN_COVER * ScanPolygons.area(inner.corners)
    }

    private fun pad(src: RgbImage): RgbImage {
        val w = src.width + 2 * BORDER
        val h = src.height + 2 * BORDER
        // Black, alpha included: only the channels are read.
        val px = IntArray(w * h)
        for (y in 0 until src.height) System.arraycopy(src.pixels, y * src.width, px, (y + BORDER) * w + BORDER, src.width)
        return RgbImage(w, h, px)
    }

    /** OSS Document Scanner's `findSquares`, down to the fallback area: every acceptable quadrilateral of [binary]. */
    private fun collect(binary: GrayImage, frame: RgbImage, out: MutableList<Candidate>, weight: Int) {
        val w = frame.width.toDouble()
        val h = frame.height.toDouble()
        val maxArea = (w - 2 * BORDER) * (h - 2 * BORDER) * MAX_AREA
        val minArea = w * h * FALLBACK_MIN_AREA
        for (c in ScanContours.find(binary)) {
            val area = c.area()
            val q = if (area >= minArea && area < maxArea && c.arcLength() >= MIN_PERIMETER) quadrilateralOf(c, w, h) else null
            val mc = q?.let(ScanPolygons::maxCos)
            if (q != null && mc != null && mc < MAX_COS) out.add(Candidate(q, area, mc, weight))
        }
    }

    /** The contour as a convex quadrilateral clear of the black border, or null. */
    private fun quadrilateralOf(c: Contour, w: Double, h: Double): List<Point>? {
        val idx = ScanPolygons.approxClosed(c, c.arcLength() * APPROX_EPSILON)
        if (idx.size != PageGeometry.CORNERS) return null
        val q = idx.map { Point(c.xs[it].toDouble(), c.ys[it].toDouble()) }
        val clear = q.all { it.x >= BORDER && it.x < w - BORDER && it.y >= BORDER && it.y < h - BORDER }
        return q.takeIf { clear && ScanPolygons.isConvex(it) }
    }
}
