package com.readfilestech.read_files_tech.data.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.images.ImageDecoding
import com.readfilestech.read_files_tech.data.images.ImageScale
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo read for the crop screen: a picture to show, and the document found in it (fractions), if any. */
class AnalysedPhoto(val preview: Bitmap, val found: List<Point>?)

/**
 * The scanner's work on bitmaps, through the app's one decoder ([ImageDecoding]: caps checked before any
 * pixel, subsampled, EXIF orientation applied — every coordinate here is of the UPRIGHT picture).
 *
 * Memory, for a phone whose heap is 256 MB (an S9): the photo is decoded at most [MAX_DECODE_SIDE] pixels
 * a side (a 12 Mpx photo whole), the page drawn at most [PageGeometry.MAX_PAGE_SIDE]; both are Bitmaps,
 * whose pixels live outside the Java heap, and the filters read the page by bands of 8 rows. Only the
 * detection holds the photo as an IntArray, at most 2 × 1 600 pixels a side.
 */
object ScanPages {
    /** Decoded for the straightening: a 12 Mpx or 16 Mpx photo whole, a 50 Mpx one at half its sides. */
    private const val MAX_DECODE_SIDE = 4800

    /** Decoded for the detection: the largest power-of-two reduction still at least 1 600 pixels long. */
    private const val DETECT_DECODE_SIDE = 2 * EdgeRefiner.REFINE_SIDE - 1

    /** The crop screen's picture: as large as the refinement's. */
    const val PREVIEW_SIDE = EdgeRefiner.REFINE_SIDE

    private const val JPEG_QUALITY = 88

    /** [source] read for the crop screen, its document looked for unless [detect] is false. */
    fun analyse(source: File, detect: Boolean, checkpoint: () -> Unit = {}): AnalysedPhoto {
        val bitmap = ImageDecoding.still(source) { w, h -> ImageScale.sampleSize(w, h, DETECT_DECODE_SIDE, DETECT_DECODE_SIDE) }
        var handedOver = false
        try {
            val found = if (detect) DocumentDetector.locate(rgbOf(bitmap), checkpoint) else null
            val photo = AnalysedPhoto(scaledDown(bitmap, PREVIEW_SIDE), found)
            handedOver = true
            return photo
        } finally {
            if (!handedOver) bitmap.recycle()
        }
    }

    /** The document in [source], looked for without keeping a picture of it (an imported image). */
    fun find(source: File, checkpoint: () -> Unit = {}): List<Point>? =
        analyse(source, detect = true, checkpoint).let { photo ->
            photo.preview.recycle()
            photo.found
        }

    /** The document looked for again in a crop screen's picture. */
    fun detect(
        preview: Bitmap,
        checkpoint: () -> Unit = {
        }
    ): List<Point>? = DocumentDetector.locate(rgbOf(preview), checkpoint)

    /**
     * The page [settings] make of [source], written to [out] as a JPEG — straightened by
     * `Matrix.setPolyToPoly` from the full photo, turned, filtered.
     *
     * @throws IOException the photo cannot be read, or the page not written.
     */
    fun render(source: File, settings: PageSettings, out: File, checkpoint: () -> Unit = {}) {
        val photo = ImageDecoding.still(source) { w, h -> ImageScale.sampleSize(w, h, MAX_DECODE_SIDE, MAX_DECODE_SIDE) }
        val page = try {
            checkpoint()
            straighten(photo, settings)
        } finally {
            photo.recycle()
        }
        try {
            PageFilters.apply(settings.filter, BitmapRows(page), checkpoint)
            AtomicFiles.write(out) { stream ->
                if (!page.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) throw IOException("page not encoded")
            }
        } finally {
            page.recycle()
        }
    }

    /** A page JPEG decoded small, for the list of pages. */
    fun thumbnail(file: File, side: Int): Bitmap = ImageDecoding.still(file) { w, h -> ImageScale.sampleSize(w, h, side, side) }

    private fun straighten(photo: Bitmap, settings: PageSettings): Bitmap {
        val quad = PageGeometry.inPixels(settings.quad, PixelSize(photo.width, photo.height))
        val size = PageGeometry.pageSize(quad, settings.turns)
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(floatsOf(quad), 0, floatsOf(PageGeometry.pageCorners(size, settings.turns)), 0, PageGeometry.CORNERS)) {
            throw IOException("frame cannot be straightened")
        }
        val page = createBitmap(size.width, size.height)
        Canvas(page).apply {
            // What the frame leaves uncovered by a rounding at its edges is paper, not transparency.
            drawColor(Color.WHITE)
            drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return page
    }

    private fun floatsOf(points: List<Point>) = FloatArray(points.size * 2) { i ->
        val p = points[i / 2]
        (if (i % 2 == 0) p.x else p.y).toFloat()
    }

    private fun rgbOf(bitmap: Bitmap): RgbImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return RgbImage(bitmap.width, bitmap.height, pixels)
    }

    /** [bitmap] with its long side at most [side]; [bitmap] itself, or a copy and [bitmap] recycled. */
    private fun scaledDown(bitmap: Bitmap, side: Int): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (long <= side) return bitmap
        val scale = side.toDouble() / long
        val scaled = bitmap.scale(max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()))
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** A Bitmap's rows, for the filters. */
    private class BitmapRows(private val bitmap: Bitmap) : PixelRows {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height

        override fun read(y: Int, rows: Int, into: IntArray) = bitmap.getPixels(into, 0, width, 0, y, width, rows)

        override fun write(y: Int, rows: Int, from: IntArray) = bitmap.setPixels(from, 0, width, 0, y, width, rows)
    }
}
