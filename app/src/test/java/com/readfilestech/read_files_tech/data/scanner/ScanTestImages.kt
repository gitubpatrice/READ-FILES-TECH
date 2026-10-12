package com.readfilestech.read_files_tech.data.scanner

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Test pictures: the bench's samples read with javax.imageio (not by the code under test), and scenes
 * rendered here — a page of "text" seen in perspective on a background — whose corners are known exactly.
 */
object ScanTestImages {
    fun resource(name: String): RgbImage {
        val stream = requireNotNull(ScanTestImages::class.java.getResourceAsStream("/scanner/$name")) { "missing $name" }
        return stream.use { of(ImageIO.read(it)) }
    }

    fun of(image: BufferedImage): RgbImage {
        val px = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return RgbImage(image.width, image.height, px)
    }

    fun toBuffered(image: RgbImage): BufferedImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).apply {
        setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
    }

    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** A sheet of paper with lines of "text": dark strokes on a light page, a margin around them. */
    fun textPage(paper: Int = rgb(246, 245, 240), ink: Int = rgb(35, 35, 45), seed: Int = 1): (Double, Double) -> Int {
        val random = Random(seed)
        val words = List(40) { line -> List(8) { random.nextDouble(0.04, 0.11) } to line }
        return { u, v ->
            val row = ((v - 0.08) / 0.021).toInt()
            val inRow = (v - 0.08) / 0.021 - row
            val inText = u in 0.1..0.9 && v in 0.08..0.92 && inRow in 0.25..0.6
            if (inText && row in words.indices && isWord(words[row].first, u)) ink else paper
        }
    }

    private fun isWord(widths: List<Double>, u: Double): Boolean {
        var x = 0.1
        for (w in widths) {
            if (u in x..min(0.9, x + w)) return true
            x += w + 0.02
        }
        return false
    }

    /** A page that is mostly a framed picture: a large dark rectangle with a light border inside the sheet. */
    fun picturePage(paper: Int = rgb(246, 245, 240)): (Double, Double) -> Int = { u, v ->
        when {
            u in 0.08..0.92 && v in 0.1..0.62 -> rgb(40 + (u * 60).toInt(), 70, 110 + (v * 80).toInt())
            else -> paper
        }
    }

    /** Wood-like: brown stripes with grain, a little noise. */
    fun wood(seed: Int = 2): (Int, Int) -> Int {
        val random = Random(seed)
        return { x, y ->
            val grain = 0.5 + 0.35 * kotlin.math.sin(x * 0.045 + kotlin.math.sin(y * 0.01) * 3) + random.nextDouble(-0.05, 0.05)
            val v = 0.8 + 0.3 * grain
            rgb((150 * v).toInt(), (100 * v).toInt(), (60 * v).toInt())
        }
    }

    /** Dark and slightly noisy. */
    fun dark(seed: Int = 3): (Int, Int) -> Int {
        val random = Random(seed)
        return { _, _ ->
            val v = 30 + random.nextInt(8)
            rgb(v, v, v + 2)
        }
    }

    /**
     * [width] × [height] pixels: [page] (a function of the page's own coordinates u, v in 0..1) seen inside
     * [quad] (pixels, TL TR BR BL), [background] elsewhere; edge pixels are averaged over 4 × 4 samples, as
     * a lens would soften them.
     */
    fun scene(width: Int, height: Int, quad: List<Point>, page: (Double, Double) -> Int, background: (Int, Int) -> Int): RgbImage {
        val toPage = Homography.squareTo(quad).inverse()
        val px = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                px[y * width + x] = pixel(x, y, toPage, page, background)
            }
        }
        return RgbImage(width, height, px)
    }

    private fun pixel(x: Int, y: Int, toPage: Homography, page: (Double, Double) -> Int, background: (Int, Int) -> Int): Int {
        val centre = toPage.map(x + 0.5, y + 0.5)
        val nearEdge = listOf(centre.x, centre.y, 1 - centre.x, 1 - centre.y).any { abs(it) < 0.01 }
        if (!nearEdge) return if (inside(centre)) page(centre.x, centre.y) else background(x, y)
        var r = 0
        var g = 0
        var b = 0
        val bg = background(x, y)
        for (sy in 0 until 4) {
            for (sx in 0 until 4) {
                val p = toPage.map(x + (sx + 0.5) / 4, y + (sy + 0.5) / 4)
                val c = if (inside(p)) page(p.x.coerceIn(0.0, 1.0), p.y.coerceIn(0.0, 1.0)) else bg
                r += (c shr 16) and 255
                g += (c shr 8) and 255
                b += c and 255
            }
        }
        return rgb(r / 16, g / 16, b / 16)
    }

    private fun inside(p: Point) = p.x in 0.0..1.0 && p.y in 0.0..1.0

    /** The mean distance between matching corners, as a fraction of [quad]'s longest diagonal. */
    fun cornerError(found: List<Point>, quad: List<Point>): Double {
        val diagonal = max(quad[0].distanceTo(quad[2]), quad[1].distanceTo(quad[3]))
        return found.indices.sumOf { found[it].distanceTo(quad[it]) } / found.size / diagonal
    }
}

/** A projective transform, solved from four point pairs (Gauss-Jordan): the test's own, not the app's. */
class Homography(private val m: DoubleArray) {
    fun map(x: Double, y: Double): Point {
        val d = m[6] * x + m[7] * y + m[8]
        return Point((m[0] * x + m[1] * y + m[2]) / d, (m[3] * x + m[4] * y + m[5]) / d)
    }

    fun inverse(): Homography {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        return Homography(
            doubleArrayOf(
                (a[4] * a[8] - a[5] * a[7]) / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
                (a[5] * a[6] - a[3] * a[8]) / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
                (a[3] * a[7] - a[4] * a[6]) / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det,
            ),
        )
    }

    companion object {
        /** Gauss-Jordan with partial pivoting: column [c] cleared everywhere but on its pivot's row. */
        private fun eliminate(a: Array<DoubleArray>, c: Int) {
            var p = c
            for (r in c + 1 until 8) if (abs(a[r][c]) > abs(a[p][c])) p = r
            val t = a[c]
            a[c] = a[p]
            a[p] = t
            for (r in 0 until 8) {
                val f = if (r == c) 0.0 else a[r][c] / a[c][c]
                for (k in c until 9) a[r][k] -= f * a[c][k]
            }
        }

        /** The transform taking the unit square (0,0) (1,0) (1,1) (0,1) to [quad]. */
        fun squareTo(quad: List<Point>): Homography = solve(
            listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)),
            quad
        )

        fun solve(from: List<Point>, to: List<Point>): Homography {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = from[i]
                val (u, v) = to[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            for (c in 0 until 8) eliminate(a, c)
            return Homography(DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] })
        }
    }
}
