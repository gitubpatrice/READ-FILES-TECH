/*
 * Portions ported from OSS Document Scanner (https://github.com/ossappscollective/OSS-DocumentScanner),
 * Copyright (c) 2022 Martin Guillon, MIT License — full text in NOTICE.
 */
package com.readfilestech.read_files_tech.data.scanner

import kotlin.math.abs
import kotlin.math.hypot

/** A traced border: integer points of the image, closed. */
class Contour(val xs: IntArray, val ys: IntArray) {
    val size: Int get() = xs.size

    /** The area enclosed (shoelace formula). */
    fun area(): Double {
        var s = 0L
        for (i in 0 until size) {
            val j = if (i + 1 == size) 0 else i + 1
            s += xs[i].toLong() * ys[j] - xs[j].toLong() * ys[i]
        }
        return abs(s) / 2.0
    }

    fun arcLength(): Double {
        var s = 0.0
        for (i in 0 until size) {
            val j = if (i + 1 == size) 0 else i + 1
            s += hypot((xs[j] - xs[i]).toDouble(), (ys[j] - ys[i]).toDouble())
        }
        return s
    }
}

/**
 * Suzuki-Abe border following — the algorithm behind OpenCV's `findContours` — every border, outer and
 * hole (`RETR_LIST`), 8-connectivity, no chain compression. Borders of fewer than 8 points are dropped:
 * no document is that small.
 */
object ScanContours {
    // Clockwise from East, as (dx, dy) with y down.
    private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private const val EAST = 0
    private const val WEST = 4
    private const val DIRECTIONS = 8
    private const val DIRECTION_MASK = 7
    private const val MIN_POINTS = 8

    /**
     * A border visits each pixel at most a few times; beyond this many steps per pixel the tracing has
     * gone wrong, and stops instead of looping forever.
     */
    private const val MAX_STEPS_PER_PIXEL = 8

    fun find(binary: GrayImage): List<Contour> = Tracer(binary).run()

    /** The labelled image of the algorithm, framed by a row and a column of zeros on each side. */
    private class Tracer(binary: GrayImage) {
        private val w = binary.width + 2
        private val h = binary.height + 2
        private val f = IntArray(w * h)
        private var nbd = 1
        private val bx = IntList()
        private val by = IntList()
        private val maxSteps = MAX_STEPS_PER_PIXEL.toLong() * w * h

        init {
            for (y in 0 until binary.height) {
                for (x in 0 until binary.width) if (binary.pixels[y * binary.width + x] != 0) f[(y + 1) * w + x + 1] = 1
            }
        }

        fun run(): List<Contour> {
            val out = ArrayList<Contour>()
            for (i in 1 until h - 1) {
                for (j in 1 until w - 1) traceFrom(i, j)?.let(out::add)
            }
            return out
        }

        /** The border starting at (i, j), if a border starts there. */
        private fun traceFrom(i: Int, j: Int): Contour? {
            val k = i * w + j
            val v = f[k]
            val startDir = when {
                v == 1 && f[k - 1] == 0 -> WEST // an outer border: (i, j - 1) is outside
                v >= 1 && f[k + 1] == 0 -> EAST // a hole border: (i, j + 1) is inside the hole
                else -> return null
            }
            nbd++
            bx.clear()
            by.clear()
            val found = firstNeighbour(i, j, startDir)
            val closed = if (found < 0) {
                // An isolated pixel.
                f[k] = -nbd
                bx.add(j - 1)
                by.add(i - 1)
                true
            } else {
                follow(i, j, i + DY[found], j + DX[found])
            }
            return if (closed && bx.size >= MIN_POINTS) Contour(bx.toArray(), by.toArray()) else null
        }

        /** Step 3.1: clockwise from [startDir], the first non-zero neighbour of (i, j), or -1. */
        private fun firstNeighbour(i: Int, j: Int, startDir: Int): Int {
            for (s in 0 until DIRECTIONS) {
                val d = (startDir + s) and DIRECTION_MASK
                if (f[(i + DY[d]) * w + j + DX[d]] != 0) return d
            }
            return -1
        }

        /** Steps 3.2 to 3.5: around the border, counter-clockwise, until back at its start (false: it never came back). */
        private fun follow(i: Int, j: Int, i1: Int, j1: Int): Boolean {
            var i2 = i1
            var j2 = j1
            var i3 = i
            var j3 = j
            var steps = 0L
            while (steps++ < maxSteps) {
                // 3.3: counter-clockwise around (i3, j3), starting after (i2, j2).
                val dPrev = directionOf(j2 - j3, i2 - i3)
                var next = -1
                var eastWasZero = false
                var s = 1
                while (next < 0 && s <= DIRECTIONS) {
                    val d = (dPrev - s) and DIRECTION_MASK
                    val n = (i3 + DY[d]) * w + j3 + DX[d]
                    if (f[n] != 0) next = d else if (d == EAST) eastWasZero = true
                    s++
                }
                val k3 = i3 * w + j3
                if (eastWasZero) f[k3] = -nbd else if (f[k3] == 1) f[k3] = nbd
                bx.add(j3 - 1)
                by.add(i3 - 1)
                val i4 = i3 + DY[next]
                val j4 = j3 + DX[next]
                val backAtStart = i4 == i && j4 == j
                if (backAtStart && i3 == i1 && j3 == j1) return true
                i2 = i3
                j2 = j3
                i3 = i4
                j3 = j4
            }
            return false
        }

        private fun directionOf(dx: Int, dy: Int): Int {
            for (d in 0 until DIRECTIONS) if (DX[d] == dx && DY[d] == dy) return d
            error("not a neighbour: $dx, $dy")
        }
    }

    /** A growable list of ints, without boxing. */
    private class IntList {
        private var a = IntArray(INITIAL_CAPACITY)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun clear() {
            size = 0
        }

        fun toArray(): IntArray = a.copyOf(size)

        private companion object {
            const val INITIAL_CAPACITY = 256
        }
    }
}
