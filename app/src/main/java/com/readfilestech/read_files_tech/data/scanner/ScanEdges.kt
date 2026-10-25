/*
 * Portions ported from OSS Document Scanner (https://github.com/ossappscollective/OSS-DocumentScanner),
 * Copyright (c) 2022 Martin Guillon, MIT License — full text in NOTICE.
 */
package com.readfilestech.read_files_tech.data.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Morphology and Canny edges on binary and grey images, with OpenCV's semantics (the detection's
 * thresholds were measured with them, RAPPORT-SCANNER §5).
 */
object ScanEdges {
    fun dilate(src: GrayImage, k: Int): GrayImage = morph(src, k, dilate = true)

    /** Closing: dilation then erosion, which fills small gaps in a white border. */
    fun close(src: GrayImage, k: Int): GrayImage = morph(dilate(src, k), k, dilate = false)

    /**
     * A k×k rectangle with OpenCV's default anchor (k/2), separable. Outside the image a pixel is the
     * value that does not change the result (OpenCV's default morphology border).
     */
    private fun morph(src: GrayImage, k: Int, dilate: Boolean): GrayImage {
        val w = src.width
        val h = src.height
        val anchor = k / 2
        val start = if (dilate) 0 else Packed.MAX
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                var m = start
                for (xx in max(0, x - anchor) until min(w, x - anchor + k)) m = pick(dilate, m, src.pixels[r + xx])
                tmp[r + x] = m
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = start
                for (yy in max(0, y - anchor) until min(h, y - anchor + k)) m = pick(dilate, m, tmp[yy * w + x])
                out[y * w + x] = m
            }
        }
        return GrayImage(w, h, out)
    }

    @Suppress("NOTHING_TO_INLINE") // the innermost loop of every pass: no call per pixel
    private inline fun pick(dilate: Boolean, a: Int, b: Int): Int = if (dilate) max(a, b) else min(a, b)

    /**
     * Canny: 3×3 Sobel (reflect-101 border), L1 magnitude, non-maximum suppression in 4 directions,
     * hysteresis in 8-connectivity — OpenCV's semantics. White (255) on the edges, black elsewhere.
     */
    fun canny(src: GrayImage, low: Double, high: Double): GrayImage {
        val gradient = sobel(src)
        val state = suppressNonMaxima(gradient, low.toFloat(), high.toFloat())
        follow(state, src.width, src.height)
        return GrayImage(src.width, src.height, IntArray(state.size) { if (state[it] == STRONG) Packed.MAX else 0 })
    }

    private class Gradient(val width: Int, val height: Int, val dx: IntArray, val dy: IntArray, val magnitude: FloatArray) {
        /** Whether the magnitude [m] at [k] peaks across the edge, in the gradient's direction rounded to 45°. */
        fun isLocalMaximum(k: Int, m: Float): Boolean {
            val ax = abs(dx[k]).toDouble()
            val ay = abs(dy[k]).toDouble()
            return when {
                ay < ax * TAN_22_5 -> m > magnitude[k - 1] && m >= magnitude[k + 1]
                ay > ax * (1 / TAN_22_5) -> m > magnitude[k - width] && m >= magnitude[k + width]
                else -> {
                    val s = if ((dx[k] xor dy[k]) < 0) -1 else 1
                    m > magnitude[k - width - s] && m > magnitude[k + width + s]
                }
            }
        }
    }

    private fun sobel(src: GrayImage): Gradient {
        val w = src.width
        val h = src.height
        val dx = IntArray(w * h)
        val dy = IntArray(w * h)

        // Reflect-101: -1 reads 1, w reads w - 2.
        fun at(x: Int, y: Int): Int {
            val xx = if (x < 0) 1 else if (x >= w) w - 2 else x
            val yy = if (y < 0) 1 else if (y >= h) h - 2 else y
            return src.pixels[yy * w + xx]
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = at(x - 1, y - 1)
                val b = at(x, y - 1)
                val c = at(x + 1, y - 1)
                val d = at(x - 1, y)
                val f = at(x + 1, y)
                val g = at(x - 1, y + 1)
                val hh = at(x, y + 1)
                val i = at(x + 1, y + 1)
                dx[y * w + x] = (c + 2 * f + i) - (a + 2 * d + g)
                dy[y * w + x] = (g + 2 * hh + i) - (a + 2 * b + c)
            }
        }
        val magnitude = FloatArray(w * h) { (abs(dx[it]) + abs(dy[it])).toFloat() }
        return Gradient(w, h, dx, dy, magnitude)
    }

    /** NONE, WEAK (between the thresholds) or STRONG for each local maximum along the gradient. */
    private fun suppressNonMaxima(g: Gradient, low: Float, high: Float): ByteArray {
        val w = g.width
        val state = ByteArray(w * g.height)
        for (y in 1 until g.height - 1) {
            for (x in 1 until w - 1) state[y * w + x] = classify(g, y * w + x, low, high)
        }
        return state
    }

    private fun classify(g: Gradient, k: Int, low: Float, high: Float): Byte {
        val m = g.magnitude[k]
        return when {
            m <= low || !g.isLocalMaximum(k, m) -> NONE
            m > high -> STRONG
            else -> WEAK
        }
    }

    /** Hysteresis: a weak pixel 8-connected to a strong one becomes strong. Each pixel is pushed once. */
    private fun follow(state: ByteArray, w: Int, h: Int) {
        val stack = IntArray(state.size)
        var sp = 0
        for (k in state.indices) if (state[k] == STRONG) stack[sp++] = k
        while (sp > 0) {
            val k = stack[--sp]
            sp = promoteNeighbours(state, k, w, h, stack, sp)
        }
    }

    /** The weak neighbours of [k] made strong and pushed on [stack]; the stack's new size. */
    @Suppress("LongParameterList") // the hysteresis' state, passed down from its loop rather than boxed
    private fun promoteNeighbours(state: ByteArray, k: Int, w: Int, h: Int, stack: IntArray, size: Int): Int {
        var sp = size
        val y = k / w
        val x = k - y * w
        for (ny in max(0, y - 1)..min(h - 1, y + 1)) {
            for (nx in max(0, x - 1)..min(w - 1, x + 1)) {
                val n = ny * w + nx
                if (state[n] == WEAK) {
                    state[n] = STRONG
                    stack[sp++] = n
                }
            }
        }
        return sp
    }

    private const val NONE: Byte = 0
    private const val WEAK: Byte = 1
    private const val STRONG: Byte = 2
    private const val TAN_22_5 = 0.4142135623730950488
}
