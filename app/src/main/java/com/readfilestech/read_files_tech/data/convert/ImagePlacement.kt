package com.readfilestech.read_files_tech.data.convert

import com.readfilestech.read_files_tech.data.images.ExifOrientation

/**
 * Where an image goes on a page of Images → PDF: an A4 page in portrait, the image upright, as large as
 * the margins let it be without changing its shape, centred — the 2.15.3's layout (`_imagesToPdf`,
 * convert_screen.dart:106-119, on Syncfusion's A4 page). A photo whose EXIF says it is turned (a phone held
 * upright) is drawn upright by this matrix, its JPEG left as it is: a PDF reader ignores EXIF, and draws a
 * JPEG as stored. Whether Syncfusion turned it in the 2.15.3 is not measured.
 */
object ImagePlacement {
    /** A4 in points, as PdfBox's `PDRectangle.A4` gives it. */
    const val PAGE_WIDTH = 595.27563f
    const val PAGE_HEIGHT = 841.8898f

    /** Half an inch on every side — Text → PDF keeps the same. */
    const val MARGIN = 36f

    /**
     * The matrix `[a b c d e f]` of PDF's `cm` operator that draws an image stored [width] × [height]
     * pixels — PDF's unit square, its first row at the top — upright by [orientation], fitted and centred
     * in the margins of an A4 page.
     */
    fun onA4(width: Int, height: Int, orientation: ExifOrientation): FloatArray {
        require(width > 0 && height > 0) { "an image of $width x $height" }
        val (shownWidth, shownHeight) = if (orientation.swapsSides) height to width else width to height
        val boxWidth = PAGE_WIDTH - 2 * MARGIN
        val boxHeight = PAGE_HEIGHT - 2 * MARGIN
        val scale = minOf(boxWidth / shownWidth, boxHeight / shownHeight)
        val drawnWidth = shownWidth * scale
        val drawnHeight = shownHeight * scale
        val fit = floatArrayOf(drawnWidth, 0f, 0f, drawnHeight, (PAGE_WIDTH - drawnWidth) / 2, (PAGE_HEIGHT - drawnHeight) / 2)
        return compose(fit, compose(rotation(orientation.degrees), if (orientation.mirrored) MIRROR else IDENTITY))
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    /** The unit square's columns mirrored: (u, v) → (1 - u, v). */
    private val MIRROR = floatArrayOf(-1f, 0f, 0f, 1f, 1f, 0f)

    /** The unit square turned clockwise by [degrees], onto itself (y upwards, as PDF's). */
    @Suppress("MagicNumber") // the four quarter turns, by their degrees
    private fun rotation(degrees: Int): FloatArray = when (degrees) {
        90 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, 1f) // (u, v) → (v, 1 - u)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, 1f, 1f) // (u, v) → (1 - u, 1 - v)
        270 -> floatArrayOf(0f, 1f, -1f, 0f, 1f, 0f) // (u, v) → (1 - v, u)
        else -> IDENTITY
    }

    /** [outer] after [inner], both `[a b c d e f]`: (x, y) → (a x + c y + e, b x + d y + f). */
    @Suppress("MagicNumber") // the six places of an affine matrix
    private fun compose(outer: FloatArray, inner: FloatArray): FloatArray {
        val a = outer[0]
        val b = outer[1]
        val c = outer[2]
        val d = outer[3]
        val e = outer[4]
        val f = outer[5]
        return floatArrayOf(
            a * inner[0] + c * inner[1],
            b * inner[0] + d * inner[1],
            a * inner[2] + c * inner[3],
            b * inner[2] + d * inner[3],
            a * inner[4] + c * inner[5] + e,
            b * inner[4] + d * inner[5] + f,
        )
    }
}
