/*
 * Portions ported from OSS Document Scanner (https://github.com/ossappscollective/OSS-DocumentScanner),
 * Copyright (c) 2022 Martin Guillon, MIT License — full text in NOTICE.
 */
package com.readfilestech.read_files_tech.data.scanner

import kotlin.math.max
import kotlin.math.min

/** An 8-bit single-channel image, row-major; the values 0..255 are kept in an IntArray, faster to work on. */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "bad grey image $width x $height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/** Packed `0xAARRGGBB` pixels, row-major — the layout of Android's `Bitmap.getPixels`; the alpha is ignored. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "bad colour image $width x $height" }
    }
}

/** The channels of a packed pixel, and back. */
internal object Packed {
    const val MAX = 255
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val OPAQUE = -0x1000000 // 0xFF000000

    fun red(c: Int): Int = (c shr RED_SHIFT) and MAX

    fun green(c: Int): Int = (c shr GREEN_SHIFT) and MAX

    fun blue(c: Int): Int = c and MAX

    /** Opaque, so that a Bitmap given these pixels is not transparent. */
    fun rgb(r: Int, g: Int, b: Int): Int = OPAQUE or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b

    fun gray(v: Int): Int = rgb(v, v, v)

    /** OpenCV's RGB2GRAY in fixed point: 0.299 R + 0.587 G + 0.114 B. */
    fun luma(c: Int): Int = (red(c) * LUMA_R + green(c) * LUMA_G + blue(c) * LUMA_B + LUMA_ROUND) shr LUMA_SHIFT

    private const val LUMA_R = 4899
    private const val LUMA_G = 9617
    private const val LUMA_B = 1868
    private const val LUMA_ROUND = 8192
    private const val LUMA_SHIFT = 14
}

/**
 * The image primitives the detection needs, in pure Kotlin — no OpenCV, no native code. Each follows the
 * OpenCV function it replaces closely enough to give the same quadrilateral as OSS Document Scanner's
 * C++ pipeline on all 584 photos of the bench (RAPPORT-SCANNER §5); differences are said where they are.
 */
object ScanImages {
    /** The luminance of each pixel. */
    fun luma(src: RgbImage): GrayImage {
        val values = IntArray(src.pixels.size) { Packed.luma(src.pixels[it]) }
        return GrayImage(src.width, src.height, values)
    }

    /** One channel: 0 = red, 1 = green, 2 = blue. */
    fun channel(src: RgbImage, channel: Int): GrayImage {
        val pick: (Int) -> Int = when (channel) {
            0 -> Packed::red
            1 -> Packed::green
            else -> Packed::blue
        }
        return GrayImage(src.width, src.height, IntArray(src.pixels.size) { pick(src.pixels[it]) })
    }

    /**
     * Area-average downscale so that the long side is at most [maxSide] (OpenCV's INTER_AREA for the
     * integer-ish ratios a photo has); [src] itself when already small enough.
     */
    fun downscale(src: RgbImage, maxSide: Int): RgbImage {
        val scale = maxSide.toDouble() / max(src.width, src.height)
        if (scale >= 1.0) return src
        val nw = max(1, Math.round(src.width * scale).toInt())
        val nh = max(1, Math.round(src.height * scale).toInt())
        val fx = src.width.toDouble() / nw
        val fy = src.height.toDouble() / nh
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = (y * fy).toInt()
            val y1 = min(src.height, max(y0 + 1, ((y + 1) * fy).toInt()))
            for (x in 0 until nw) {
                val x0 = (x * fx).toInt()
                val x1 = min(src.width, max(x0 + 1, ((x + 1) * fx).toInt()))
                out[y * nw + x] = blockAverage(src, x0, x1, y0, y1)
            }
        }
        return RgbImage(nw, nh, out)
    }

    private fun blockAverage(src: RgbImage, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        for (yy in y0 until y1) {
            var o = yy * src.width + x0
            repeat(x1 - x0) {
                val c = src.pixels[o++]
                r += Packed.red(c)
                g += Packed.green(c)
                b += Packed.blue(c)
            }
        }
        val n = (x1 - x0) * (y1 - y0)
        return Packed.rgb(r / n, g / n, b / n)
    }

    /** Separable 5×5 Gaussian [1 4 6 4 1]/16, replicated border (OpenCV's GaussianBlur 5×5, sigma 0). */
    @Suppress("MagicNumber") // the binomial weights 1 4 6 4 1 of the 5-tap kernel
    fun gauss5(src: GrayImage): GrayImage {
        val w = src.width
        val h = src.height
        val p = src.pixels
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                tmp[r + x] = p[r + max(0, x - 2)] + 4 * p[r + max(0, x - 1)] + 6 * p[r + x] +
                    4 * p[r + min(w - 1, x + 1)] + p[r + min(w - 1, x + 2)]
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ym2 = max(0, y - 2) * w
            val ym1 = max(0, y - 1) * w
            val y0 = y * w
            val yp1 = min(h - 1, y + 1) * w
            val yp2 = min(h - 1, y + 2) * w
            for (x in 0 until w) {
                val sum = tmp[ym2 + x] + 4 * tmp[ym1 + x] + 6 * tmp[y0 + x] + 4 * tmp[yp1 + x] + tmp[yp2 + x]
                out[y0 + x] = (sum + GAUSS_ROUND) shr GAUSS_SHIFT
            }
        }
        return GrayImage(w, h, out)
    }

    /** 3×3 median, replicated border. */
    fun median3(src: GrayImage): GrayImage {
        val w = src.width
        val h = src.height
        val out = IntArray(w * h)
        val window = IntArray(MEDIAN_WINDOW)
        for (y in 0 until h) {
            for (x in 0 until w) out[y * w + x] = medianOf9(window(src, x, y, window))
        }
        return GrayImage(w, h, out)
    }

    /** The 3 × 3 neighbourhood of (x, y) into [into], the border replicated. */
    private fun window(src: GrayImage, x: Int, y: Int, into: IntArray): IntArray {
        var k = 0
        for (dy in -1..1) {
            val row = min(src.height - 1, max(0, y + dy)) * src.width
            for (dx in -1..1) into[k++] = src.pixels[row + min(src.width - 1, max(0, x + dx))]
        }
        return into
    }

    /** Insertion sort of the nine values, then the middle one. */
    private fun medianOf9(v: IntArray): Int {
        for (i in 1 until MEDIAN_WINDOW) {
            val t = v[i]
            var j = i - 1
            while (j >= 0 && v[j] > t) {
                v[j + 1] = v[j]
                j--
            }
            v[j + 1] = t
        }
        return v[MEDIAN_WINDOW / 2]
    }

    /** Otsu's threshold of [histogram] (256 bins): the level that best separates the two classes. */
    fun otsu(histogram: IntArray): Int {
        val total = histogram.sum().toDouble()
        var sum = 0.0
        for (i in histogram.indices) sum += i * histogram[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var level = 0
        for (i in histogram.indices) {
            wB += histogram[i]
            val wF = total - wB
            if (wB > 0.0 && wF > 0.0) {
                sumB += i * histogram[i].toDouble()
                val mB = sumB / wB
                val mF = (sum - sumB) / wF
                val between = wB * wF * (mB - mF) * (mB - mF)
                if (between > best) {
                    best = between
                    level = i
                }
            }
        }
        return level
    }

    /** `src > t ? 255 : 0`. */
    fun threshold(src: GrayImage, t: Int): GrayImage =
        GrayImage(src.width, src.height, IntArray(src.pixels.size) { if (src.pixels[it] > t) Packed.MAX else 0 })

    private const val GAUSS_ROUND = 128
    private const val GAUSS_SHIFT = 8
    private const val MEDIAN_WINDOW = 9
}
