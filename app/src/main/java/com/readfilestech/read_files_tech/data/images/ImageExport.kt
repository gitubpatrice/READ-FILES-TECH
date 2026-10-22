package com.readfilestech.read_files_tech.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import java.io.File
import java.io.IOException

/**
 * The pixels of Compress and Remove EXIF: decoded by the app's one decoder ([ImageDecoding] — caps before
 * any pixel, the EXIF orientation applied), written by the platform's encoder (`Bitmap.compress`) into a
 * file reserved by [OutputStorage], atomically. The copy is UPRIGHT and carries no metadata: the encoder
 * writes no EXIF and no XMP, and the one XMP it could write — an Ultra HDR gain map's, Android 14+ — is not
 * given to it. What it may write is an ICC profile: the description of the picture's colour space, needed to
 * show a wide-gamut photo's colours, which says nothing of the person or the place.
 *
 * The 2.15.3 copied the EXIF, GPS included, into a compressed JPEG (inventory D13), and lost the orientation
 * of a copy without EXIF (F12). Nothing passes through the cache here: the 2.15.3's `cache/<base>_no_exif`
 * stayed there on an error or a kill (§5.2).
 *
 * @param ensureActive called before the output is reserved and once it is encoded, before it is published:
 *   throwing there (a cancellation) leaves no output — nor the temporary file beside it.
 */
object ImageExport {
    /** Remove EXIF: the whole picture, upright, into `Sans-EXIF/<base>_no_exif_<stamp>.jpg|png`. */
    fun strip(source: File, outputs: OutputStorage, ensureActive: () -> Unit): File {
        val picture = ImageDecoding.still(source) { _, _ -> 1 }
        return try {
            val format = ImageToolRules.strippedFormat(source.extension, picture.hasAlpha())
            val name = "${ImageToolRules.baseName(source.name)}_no_exif"
            write(picture, format, ImageToolRules.STRIP_QUALITY, Output(outputs, OutputCategory.NO_EXIF, name), ensureActive)
        } finally {
            picture.recycle()
        }
    }

    /**
     * Compress: decoded at no less than [maxWidth] (a power-of-two subsampling, [ImageScale]), brought to it
     * exactly, then a JPEG at [quality] into `Compressions/<base>_compressed_<stamp>.jpg`. A transparent
     * picture is laid on white: a JPEG has no transparency, and the 2.15.3's showed black there.
     */
    fun compress(source: File, quality: Int, maxWidth: Int, outputs: OutputStorage, ensureActive: () -> Unit): File {
        // "Shown" at maxWidth, decoded at between once and twice that: never below the width asked.
        val decoded = ImageDecoding.still(source) { width, height -> ImageScale.fittingSample(width, height, maxWidth, Int.MAX_VALUE) }
        val picture = opaque(scaled(decoded, maxWidth))
        return try {
            val name = "${ImageToolRules.baseName(source.name)}_compressed"
            write(picture, ImageFormat.JPEG, quality, Output(outputs, OutputCategory.COMPRESSIONS, name), ensureActive)
        } finally {
            picture.recycle()
        }
    }

    /** The size [file] is shown at, read from its header alone: the stored size, turned by [orientation]. */
    fun uprightSize(file: File, orientation: Int): ImageSize {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnreadableFileException("no image header")
        val width = bounds.outWidth.toLong()
        val height = bounds.outHeight.toLong()
        return if (ExifOrientation.of(orientation).swapsSides) ImageSize(height, width) else ImageSize(width, height)
    }

    private class Output(val storage: OutputStorage, val category: OutputCategory, val name: String)

    private fun write(picture: Bitmap, format: ImageFormat, quality: Int, output: Output, ensureActive: () -> Unit): File {
        withoutGainmap(picture)
        ensureActive()
        val file = output.storage.reserve(output.category, output.name, format.extension)
        var written = false
        try {
            output.storage.write(file) { stream ->
                if (!picture.compress(format.platform(), quality, stream)) throw IOException("image not encoded")
                ensureActive()
            }
            written = true
        } finally {
            if (!written) output.storage.discard(file)
        }
        return file
    }

    private fun ImageFormat.platform(): Bitmap.CompressFormat = when (this) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
    }

    /** [decoded] brought down to [maxWidth] (bilinear, from at most twice that), or itself when narrower. */
    private fun scaled(decoded: Bitmap, maxWidth: Int): Bitmap {
        val size = ImageToolRules.fitWidth(decoded.width, decoded.height, maxWidth)
        if (size.width == decoded.width.toLong()) return decoded
        return replaced(decoded) { it.scale(size.width.toInt(), size.height.toInt(), filter = true) }
    }

    /** [picture] on white where it is transparent, [picture] itself when it has no alpha — the OCR's too. */
    internal fun opaque(picture: Bitmap): Bitmap {
        if (!picture.hasAlpha()) return picture
        return replaced(picture) { source ->
            createBitmap(source.width, source.height).also { flat ->
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(source, 0f, 0f, null)
                }
            }
        }
    }

    /**
     * What [make] makes of [bitmap]; [bitmap] is recycled either way when it is not the result — made, or
     * failed on the way (an OOM allocating the new one: it was left to the GC, megabytes of native memory
     * in a batch, Claude review R19).
     */
    internal inline fun replaced(bitmap: Bitmap, make: (Bitmap) -> Bitmap): Bitmap {
        var made = bitmap
        var done = false
        try {
            made = make(bitmap)
            done = true
        } finally {
            if (!done || made !== bitmap) bitmap.recycle()
        }
        return made
    }

    /**
     * Android 14 writes a picture holding a gain map (an Ultra HDR photo) as an Ultra HDR JPEG: a second
     * picture, and XMP describing it. The copy is the SDR picture alone.
     */
    private fun withoutGainmap(picture: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && picture.hasGainmap()) picture.gainmap = null
    }
}
