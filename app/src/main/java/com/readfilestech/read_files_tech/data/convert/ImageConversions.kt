package com.readfilestech.read_files_tech.data.convert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.images.ImageBounds
import com.readfilestech.read_files_tech.data.images.ImageDecoding
import com.readfilestech.read_files_tech.data.scanner.JpegInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/** What Image → JPG / PNG writes: the 2.15.3's two targets (its WebP branch was dead, inventory F6). */
enum class ImageFormat(val extension: String, internal val format: Bitmap.CompressFormat, internal val quality: Int) {
    /** At quality 90, as the 2.15.3's `encodeJpg(quality: 90)`. */
    JPG("jpg", Bitmap.CompressFormat.JPEG, JPEG_QUALITY),
    PNG("png", Bitmap.CompressFormat.PNG, LOSSLESS),
}

private const val JPEG_QUALITY = 90
private const val LOSSLESS = 100

/** The images chosen for one PDF weigh more than [capBytes] together. */
class SelectionTooLargeException(val capBytes: Long) : IOException("a selection over $capBytes bytes")

/** Image [name] of several could not be put in the PDF; [cause] says why. */
class ImageFailedException(val name: String, cause: Throwable) : IOException("image $name", cause)

/**
 * The conversions of images: one to JPG or PNG, several to a PDF. Every image is read through the app's
 * one decoder ([ImageDecoding]: the file's cap, the dimensions before a pixel is allocated, the EXIF
 * orientation applied) — HEIC included where Android decodes it (9 and later). The 2.15.3's tiles announced
 * HEIC, which its `image` package could not decode (inventory F6).
 */
object ImageConversions {
    /**
     * [file] as [format] on [out] (not closed): its pixels upright, nothing else — Android's encoders write
     * no EXIF, so no place, no date, no camera. A transparent image made a JPG is drawn on white: a JPEG
     * has no transparency, and its pixels would otherwise come out black.
     */
    fun write(file: File, format: ImageFormat, out: OutputStream) {
        val decoded = ImageDecoding.still(file) { _, _ -> 1 }
        var drawn = decoded
        try {
            if (format == ImageFormat.JPG && decoded.hasAlpha()) drawn = onWhite(decoded)
            if (!drawn.compress(format.format, format.quality, out)) throw IOException("not encoded as $format")
        } finally {
            if (drawn !== decoded) drawn.recycle()
            decoded.recycle()
        }
    }

    /**
     * Each of [files] within the caps of Images → PDF, before any is read: an image within
     * [FileCaps.IMAGE_FILE], all together within [FileCaps.IMAGES_TO_PDF_TOTAL] (the 2.15.3's, checked
     * there as it went, `convert_screen.dart:94-101`).
     *
     * @throws ImageFailedException an image past its cap, named.
     * @throws SelectionTooLargeException all of them past theirs.
     */
    fun checkSizes(files: List<File>) {
        var total = 0L
        for (file in files) {
            failingAs(file) { FileCaps.check(file, FileCaps.IMAGE_FILE) }
            total += file.length()
        }
        if (total > FileCaps.IMAGES_TO_PDF_TOTAL) throw SelectionTooLargeException(FileCaps.IMAGES_TO_PDF_TOTAL)
    }

    /**
     * A page of [file] in [document]: a JPEG a PDF carries is handed over as it is, its EXIF orientation
     * with it; any other image is decoded here and its pixels handed over.
     *
     * @throws ImageFailedException naming [file], with the reason.
     */
    fun addPage(document: PdfImagesDocument, file: File) = failingAs(file) {
        val jpeg = jpegInfo(file)
        if (jpeg != null) {
            ImageBounds.check(jpeg.width.toLong(), jpeg.height.toLong())
            document.addJpeg(file, ImageDecoding.orientationOf(file))
        } else {
            val bitmap = ImageDecoding.still(file) { _, _ -> 1 }
            try {
                document.addBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** The frame of [file] when it is a JPEG a PDF carries as it is (grey or colour, Huffman); null otherwise. */
    private fun jpegInfo(file: File): JpegInfo? = try {
        BufferedInputStream(FileInputStream(file)).use(JpegInfo::read)
    } catch (expected: IOException) {
        null
    }

    private fun onWhite(image: Bitmap): Bitmap {
        val white = createBitmap(image.width, image.height)
        Canvas(white).apply {
            drawColor(Color.WHITE)
            drawBitmap(image, 0f, 0f, null)
        }
        return white
    }

    /** [block]'s failure on [file], named: of several images, the screen says which one. */
    private inline fun <T> failingAs(file: File, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw ImageFailedException(file.name, e)
    }
}
