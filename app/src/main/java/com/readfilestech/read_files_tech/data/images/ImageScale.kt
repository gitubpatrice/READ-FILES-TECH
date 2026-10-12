package com.readfilestech.read_files_tech.data.images

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How much of an image is decoded: a power-of-two subsampling, the one rule of `BitmapFactory`
 * (`inSampleSize`) and of `ImageDecoder` (`setTargetSampleSize`) — so the decoder produces that size
 * directly. An exact target size would make `ImageDecoder` decode at the next sample up and scale
 * down: the whole image in memory, for a moment, whenever the target sat just above half its size.
 */
object ImageScale {
    /** The viewer decodes at up to twice the size shown, as the 2.15.3 (`cacheWidth` twice the screen): a zoom keeps detail. */
    const val ZOOM_DETAIL = 2

    /**
     * The smallest power of two that brings neither side over its limit — `ceil(side / sample)` is the
     * largest size a decoder makes of it. Limits below one pixel count as one.
     */
    fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        val limitWidth = max(1, maxWidth)
        val limitHeight = max(1, maxHeight)
        var sample = 1
        while (ceilDiv(width, sample) > limitWidth || ceilDiv(height, sample) > limitHeight) sample *= 2
        return sample
    }

    /**
     * The viewer's page: an image fitted in [boxWidth] × [boxHeight] pixels, decoded at up to [detail]
     * times the size it is shown at, never above its own size. Between once and [detail] times the size
     * shown, then: never below the screen's resolution.
     */
    fun fittingSample(width: Int, height: Int, boxWidth: Int, boxHeight: Int, detail: Int = ZOOM_DETAIL): Int {
        val shown = min(boxWidth.toDouble() / width, boxHeight.toDouble() / height)
        return sampleOf(width, height, min(1.0, shown * detail))
    }

    /**
     * A thumbnail: its short side to [side], cropped to a square when shown; its long side at most
     * [maxElongation] times [side] — a 1080 × 20 000 screenshot would come out 135 × 2 500 otherwise.
     */
    fun coveringSample(width: Int, height: Int, side: Int, maxElongation: Int): Int {
        val scale = minOf(1.0, side.toDouble() / min(width, height), side.toDouble() * maxElongation / max(width, height))
        return sampleOf(width, height, scale)
    }

    private fun sampleOf(width: Int, height: Int, scale: Double): Int =
        sampleSize(width, height, ceil(width * scale).toInt(), ceil(height * scale).toInt())

    private fun ceilDiv(value: Int, divisor: Int): Long = (value.toLong() + divisor - 1) / divisor
}

/**
 * An EXIF orientation (TIFF tag 274, 1 to 8) as what shows the image upright: a mirror of its columns
 * first, then a clockwise rotation. `ImageDecoder` applies it itself; below API 28 the decoder does.
 */
data class ExifOrientation(val mirrored: Boolean, val degrees: Int) {
    /** A quarter turn: the image shown is as wide as the stored one is high. */
    val swapsSides: Boolean get() = degrees % HALF_TURN != 0

    companion object {
        private const val QUARTER_TURN = 90
        private const val HALF_TURN = 180
        private const val THREE_QUARTERS = 270

        val UPRIGHT = ExifOrientation(mirrored = false, degrees = 0)

        /** Any other value — absent, 0, out of range — shows the image as stored. */
        @Suppress("MagicNumber") // the eight values of the EXIF specification, by their number
        fun of(tag: Int): ExifOrientation = when (tag) {
            2 -> ExifOrientation(mirrored = true, degrees = 0)
            3 -> ExifOrientation(mirrored = false, degrees = HALF_TURN)
            4 -> ExifOrientation(mirrored = true, degrees = HALF_TURN)
            5 -> ExifOrientation(mirrored = true, degrees = THREE_QUARTERS)
            6 -> ExifOrientation(mirrored = false, degrees = QUARTER_TURN)
            7 -> ExifOrientation(mirrored = true, degrees = QUARTER_TURN)
            8 -> ExifOrientation(mirrored = false, degrees = THREE_QUARTERS)
            else -> UPRIGHT
        }
    }
}
