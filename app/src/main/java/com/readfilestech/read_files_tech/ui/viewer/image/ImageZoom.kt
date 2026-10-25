package com.readfilestech.read_files_tech.ui.viewer.image

import androidx.compose.ui.geometry.Offset
import kotlin.math.max

/** A page and the image fitted in it at scale 1, in pixels: what a zoom keeps the image within. */
data class ZoomFrame(val boxWidth: Float, val boxHeight: Float, val imageWidth: Float, val imageHeight: Float)

/**
 * The zoom of the page shown (`InteractiveViewer` of the 2.15.3, from 0.5 to 6 times): [scale] around the
 * page's centre, then a shift of [x], [y] pixels — the order of a graphics layer. Pure, tested on the JVM.
 */
data class ImageZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    /** Larger than fitted: a drag moves the image, and the gallery no longer swipes. */
    val zoomed: Boolean get() = scale > 1f + SNAP

    /**
     * A pinch of [factor] around [centroid] and a drag of [pan], in the page's pixels: the point of the
     * image under the fingers stays under them.
     */
    fun transformed(centroid: Offset, pan: Offset, factor: Float, frame: ZoomFrame): ImageZoom {
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = next / scale
        val fromCentreX = centroid.x - frame.boxWidth / 2
        val fromCentreY = centroid.y - frame.boxHeight / 2
        return ImageZoom(
            scale = next,
            x = fromCentreX * (1 - ratio) + x * ratio + pan.x,
            y = fromCentreY * (1 - ratio) + y * ratio + pan.y,
        ).within(frame)
    }

    /** Back to the fitted size when zoomed; otherwise [DOUBLE_TAP_SCALE] times, around the point tapped. */
    fun doubleTapped(at: Offset, frame: ZoomFrame): ImageZoom =
        if (zoomed) NONE else NONE.transformed(at, Offset.Zero, DOUBLE_TAP_SCALE, frame)

    /** Never past the image's edges: a zoomed image covers its page, a smaller one stays centred. */
    private fun within(frame: ZoomFrame): ImageZoom {
        val maxX = max(0f, (frame.imageWidth * scale - frame.boxWidth) / 2)
        val maxY = max(0f, (frame.imageHeight * scale - frame.boxHeight) / 2)
        return copy(x = x.coerceIn(-maxX, maxX), y = y.coerceIn(-maxY, maxY))
    }

    companion object {
        val NONE = ImageZoom()

        /** The 2.15.3's `minScale` and `maxScale`. */
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.5f

        /** A pinch that ends this close to the fitted size has not zoomed. */
        private const val SNAP = 0.01f
    }
}
