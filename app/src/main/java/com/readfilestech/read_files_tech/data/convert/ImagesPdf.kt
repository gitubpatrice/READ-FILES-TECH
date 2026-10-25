package com.readfilestech.read_files_tech.data.convert

import com.readfilestech.read_files_tech.data.images.ExifOrientation
import com.readfilestech.read_files_tech.data.scanner.JpegInfo
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColorSpace
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.DeflaterInputStream

/** The pixels of a decoded image, one row at a time: what [ImagesPdf] embeds without loss. */
interface PixelRows {
    val width: Int
    val height: Int

    /** Whether any pixel may be transparent: the image then gets a soft mask. */
    val hasAlpha: Boolean

    /** Row [y] into [argb], `width` colours as `0xAARRGGBB`, not premultiplied (`Bitmap.getPixels`). */
    fun row(y: Int, argb: IntArray)
}

/**
 * Images → PDF in the `:pdf` process (docs D-005, D-029): a PdfBox document, one A4 page per image
 * ([ImagePlacement]). A JPEG is embedded AS IT IS — its bytes copied into the stream, `/DCTDecode`, never
 * decoded nor re-encoded (the prototype measured 827 705 bytes becoming 6 963 042 when re-encoded); any
 * other image is embedded without loss from its pixels, `/FlateDecode`, deflated row by row as it is
 * copied, with a soft mask when it has transparency. PdfBox keeps what it is given in [memory], past it
 * in its scratch files: 200 MB of photos never sit in the heap.
 *
 * No metadata is added: no producer, no date.
 */
class ImagesPdf(memory: MemoryUsageSetting) : AutoCloseable {
    private val document = PDDocument(memory)

    val pageCount: Int get() = document.numberOfPages

    /**
     * A page of the JPEG [input] gives — [info] read from its frame header — upright by [orientation]
     * (its EXIF). Its bytes are copied from [input], which is not closed, without its metadata
     * ([JpegWithoutMetadata]: no GPS position, date or camera in the PDF); its pixels are never re-encoded.
     */
    fun addJpeg(input: InputStream, info: JpegInfo, orientation: ExifOrientation) {
        val space: PDColorSpace = if (info.components == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
        val image = PDImageXObject(document, JpegWithoutMetadata(input), COSName.DCT_DECODE, info.width, info.height, BITS, space)
        page(image, ImagePlacement.onA4(info.width, info.height, orientation))
    }

    /** A page of [pixels], already upright. */
    fun addPixels(pixels: PixelRows) {
        val image = deflated(pixels, alpha = false)
        if (pixels.hasAlpha) image.cosObject.setItem(COSName.SMASK, deflated(pixels, alpha = true))
        page(image, ImagePlacement.onA4(pixels.width, pixels.height, ExifOrientation.UPRIGHT))
    }

    /** The document, on [out] (not closed). */
    fun save(out: OutputStream) {
        check(pageCount > 0) { "a PDF needs a page" }
        // Without it PdfBox makes the /ID from the time of the save, to the millisecond: the moment the PDF
        // was made, recoverable from a document that carries no date (Claude review R18).
        document.documentId = SecureRandom().nextLong()
        document.save(out)
    }

    override fun close() = document.close()

    private fun deflated(pixels: PixelRows, alpha: Boolean): PDImageXObject =
        DeflaterInputStream(RowBytes(pixels, alpha)).use { stream ->
            val space: PDColorSpace = if (alpha) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
            PDImageXObject(document, stream, COSName.FLATE_DECODE, pixels.width, pixels.height, BITS, space)
        }

    private fun page(image: PDImageXObject, matrix: FloatArray) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            @Suppress("MagicNumber") // the six places of an affine matrix
            content.drawImage(image, Matrix(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]))
        }
    }

    private companion object {
        const val BITS = 8
    }
}

/**
 * The samples of [pixels] as PDF reads an image of 8 bits a component, one row after the other: three
 * bytes a pixel (red, green, blue), or one (its alpha) for the soft mask. One row in memory at a time.
 */
private class RowBytes(private val pixels: PixelRows, private val alpha: Boolean) : InputStream() {
    private val argb = IntArray(pixels.width)
    private val row = ByteArray(pixels.width * if (alpha) 1 else RGB)
    private var y = 0
    private var at = row.size

    override fun read(): Int = if (fill()) row[at++].toInt() and BYTE else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int = when {
        len == 0 -> 0
        !fill() -> -1
        else -> {
            val count = minOf(len, row.size - at)
            System.arraycopy(row, at, b, off, count)
            at += count
            count
        }
    }

    /** The current row has bytes left, or the next one is read; false after the last. */
    private fun fill(): Boolean {
        if (at < row.size || y == pixels.height) return at < row.size
        pixels.row(y++, argb)
        for (x in argb.indices) {
            val colour = argb[x]
            if (alpha) {
                row[x] = (colour ushr ALPHA_SHIFT).toByte()
            } else {
                row[RGB * x] = (colour shr RED_SHIFT).toByte()
                row[RGB * x + 1] = (colour shr GREEN_SHIFT).toByte()
                row[RGB * x + 2] = colour.toByte()
            }
        }
        at = 0
        return true
    }

    private companion object {
        const val RGB = 3
        const val BYTE = 0xFF
        const val ALPHA_SHIFT = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
    }
}
