package com.readfilestech.read_files_tech.data.convert

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.images.ExifOrientation
import com.readfilestech.read_files_tech.data.scanner.JpegInfo
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Images → PDF, PdfBox's part (what the `:pdf` process runs) and the page's geometry. A JPEG must come out
 * BYTE FOR BYTE (docs D-005); pixels without loss, their transparency in a soft mask. Each PDF made is left in
 * `build/oracles/convert/images-to-pdf/` with a manifest, for `convert_oracle.py check`: pypdf reads every
 * image stream back and compares it with the source JPEG, or with the pixels written here.
 */
class ImagesToPdfTest {
    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/image")
    private val oracle = File(System.getProperty("user.dir"), "build/oracles/convert/images-to-pdf").apply { mkdirs() }

    /** A test image of [width] × [height], each pixel's colour from its place, half of it transparent when [alpha]. */
    private class Gradient(override val width: Int, override val height: Int, override val hasAlpha: Boolean) : PixelRows {
        fun argb(x: Int, y: Int): Int {
            val a = if (hasAlpha && x < width / 2) 0x40 else 0xFF
            return (a shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or ((x + y) and 0xFF)
        }

        override fun row(y: Int, argb: IntArray) {
            for (x in 0 until width) argb[x] = argb(x, y)
        }

        fun rgb(): ByteArray = ByteArray(width * height * 3).also { bytes ->
            for (y in 0 until height) for (x in 0 until width) {
                val c = argb(x, y)
                val at = (y * width + x) * 3
                bytes[at] = (c shr 16).toByte()
                bytes[at + 1] = (c shr 8).toByte()
                bytes[at + 2] = c.toByte()
            }
        }

        fun alpha(): ByteArray = ByteArray(width * height) { (argb(it % width, it / width) ushr 24).toByte() }
    }

    private fun grayJpeg(): ByteArray {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until 80) for (x in 0 until 120) image.raster.setSample(x, y, 0, (x + y) % 256)
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    /** The six numbers before the `cm` of page [page]'s content: where its image is drawn. */
    private fun matrixOf(document: PDDocument, page: Int): DoubleArray {
        val content = document.getPage(page).contents.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        val numbers = Regex("""((?:-?[0-9.]+\s+){6})cm""").find(content)!!.groupValues[1]
        return numbers.trim().split(Regex("""\s+""")).map(String::toDouble).toDoubleArray()
    }

    private fun images(page: Int, document: PDDocument): List<PDImageXObject> {
        val resources = document.getPage(page).resources
        return resources.xObjectNames.map { resources.getXObject(it) as PDImageXObject }
    }

    @Test
    fun `JPEGs are embedded byte for byte, pixels without loss, and the oracle gets them all`() {
        val colour = File(corpus, "system-drawing.jpg").readBytes()
        val turned = File(corpus, "system-drawing-exif-orientation-6.jpg").readBytes()
        val gray = grayJpeg()
        val pixels = Gradient(64, 48, hasAlpha = true)
        val opaque = Gradient(33, 17, hasAlpha = false)
        val pdf = ByteArrayOutputStream()
        ImagesPdf(MemoryUsageSetting.setupMainMemoryOnly()).use { document ->
            document.addJpeg(colour.inputStream(), JpegInfo.read(colour.inputStream()), ExifOrientation.UPRIGHT)
            document.addJpeg(turned.inputStream(), JpegInfo.read(turned.inputStream()), ExifOrientation.of(6))
            document.addJpeg(gray.inputStream(), JpegInfo.read(gray.inputStream()), ExifOrientation.UPRIGHT)
            document.addPixels(pixels)
            document.addPixels(opaque)
            document.save(pdf)
        }

        PDDocument.load(pdf.toByteArray()).use { document ->
            assertThat(document.numberOfPages).isEqualTo(5)
            for ((page, jpeg) in listOf(0 to colour, 1 to turned, 2 to gray)) {
                val image = images(page, document).single()
                assertThat(image.cosObject.getItem(COSName.FILTER)).isEqualTo(COSName.DCT_DECODE)
                assertThat(image.cosObject.createRawInputStream().use { it.readBytes() }).isEqualTo(jpeg)
            }
            // The photo of EXIF orientation 6 drawn turned, by the page's matrix: its JPEG stays as it is.
            val info = JpegInfo.read(turned.inputStream())
            val expected = ImagePlacement.onA4(info.width, info.height, ExifOrientation.of(6)).map(Float::toDouble).toDoubleArray()
            assertThat(matrixOf(document, 1)).usingTolerance(0.01).containsExactly(expected).inOrder()
            assertThat(images(2, document).single().colorSpace.name).isEqualTo("DeviceGray")
            assertThat(images(0, document).single().colorSpace.name).isEqualTo("DeviceRGB")

            val withMask = images(3, document).single()
            assertThat(withMask.cosObject.createInputStream().use { it.readBytes() }).isEqualTo(pixels.rgb())
            assertThat(withMask.softMask.cosObject.createInputStream().use { it.readBytes() }).isEqualTo(pixels.alpha())
            assertThat(images(4, document).single().softMask).isNull()
            assertThat(images(4, document).single().cosObject.createInputStream().use { it.readBytes() }).isEqualTo(opaque.rgb())
            // Nothing says who made it, or when.
            assertThat(document.documentInformation.producer).isNull()
            assertThat(document.documentInformation.creationDate).isNull()
        }

        File(oracle, "images.pdf").writeBytes(pdf.toByteArray())
        File(oracle, "gray.jpg").writeBytes(gray)
        File(oracle, "gradient.rgb").writeBytes(pixels.rgb())
        File(oracle, "gradient.alpha").writeBytes(pixels.alpha())
        File(oracle, "opaque.rgb").writeBytes(opaque.rgb())
        val pages = JSONArray()
            .put(JSONObject().put("jpeg", File(corpus, "system-drawing.jpg").canonicalPath))
            .put(JSONObject().put("jpeg", File(corpus, "system-drawing-exif-orientation-6.jpg").canonicalPath))
            .put(JSONObject().put("jpeg", "gray.jpg"))
            .put(JSONObject().put("rgb", "gradient.rgb").put("alpha", "gradient.alpha").put("width", 64).put("height", 48))
            .put(JSONObject().put("rgb", "opaque.rgb").put("width", 33).put("height", 17))
        File(oracle, "manifest.json").writeText(JSONObject().put("pdf", "images.pdf").put("pages", pages).toString(1))
    }

    @Test
    fun `a JPEG cut short is embedded as it is, what is there and nothing invented`() {
        val whole = File(corpus, "system-drawing.jpg").readBytes()
        val cut = whole.copyOf(whole.size / 2)
        val pdf = ByteArrayOutputStream()
        ImagesPdf(MemoryUsageSetting.setupMainMemoryOnly()).use { document ->
            document.addJpeg(cut.inputStream(), JpegInfo.read(cut.inputStream()), ExifOrientation.UPRIGHT)
            document.save(pdf)
        }

        PDDocument.load(pdf.toByteArray()).use { document ->
            assertThat(images(0, document).single().cosObject.createRawInputStream().use { it.readBytes() }).isEqualTo(cut)
        }
    }

    /** Where the corner ([x], [y]) of an image stored [w] × [h] (pixels, y down) is shown, upright by [o]: (x, y, shown w, shown h). */
    private fun shown(x: Double, y: Double, w: Double, h: Double, o: ExifOrientation): DoubleArray {
        val mx = if (o.mirrored) w - x else x
        return when (o.degrees) {
            90 -> doubleArrayOf(h - y, mx, h, w)
            180 -> doubleArrayOf(w - mx, h - y, w, h)
            270 -> doubleArrayOf(y, w - mx, h, w)
            else -> doubleArrayOf(mx, y, w, h)
        }
    }

    @Test
    fun `each EXIF orientation draws the image upright, fitted and centred in the margins`() {
        // A wide image fills the page's width, a tall one its height: both are centred the other way.
        for ((w, h) in listOf(400 to 300, 100 to 900)) for (tag in 1..8) {
            val orientation = ExifOrientation.of(tag)
            val m = ImagePlacement.onA4(w, h, orientation)
            // The rectangle drawn: the unit square's image under the matrix.
            val xs = listOf(m[4], m[0] + m[4], m[2] + m[4], m[0] + m[2] + m[4])
            val ys = listOf(m[5], m[1] + m[5], m[3] + m[5], m[1] + m[3] + m[5])
            val left = xs.min()
            val bottom = ys.min()
            val drawnW = xs.max() - left
            val drawnH = ys.max() - bottom
            val (shownW, shownH) = if (orientation.swapsSides) h to w else w to h
            assertThat(abs(drawnW / drawnH - shownW.toFloat() / shownH)).isLessThan(1e-4f)
            assertThat(left).isAtLeast(ImagePlacement.MARGIN - 0.01f)
            assertThat(bottom).isAtLeast(ImagePlacement.MARGIN - 0.01f)
            assertThat(abs(left - (ImagePlacement.PAGE_WIDTH - left - drawnW))).isLessThan(0.01f)
            assertThat(abs(bottom - (ImagePlacement.PAGE_HEIGHT - bottom - drawnH))).isLessThan(0.01f)
            // Every stored corner where the upright image shows it. PDF's unit square: (u, v), v up, the first row at v = 1.
            for ((u, v) in listOf(0f to 1f, 1f to 1f, 0f to 0f, 1f to 0f)) {
                val at = shown(u * w.toDouble(), (1 - v) * h.toDouble(), w.toDouble(), h.toDouble(), orientation)
                val expectedX = left + at[0] / at[2] * drawnW
                val expectedY = bottom + drawnH - at[1] / at[3] * drawnH
                assertThat(abs(m[0] * u + m[2] * v + m[4] - expectedX)).isLessThan(0.01)
                assertThat(abs(m[1] * u + m[3] * v + m[5] - expectedY)).isLessThan(0.01)
            }
        }
    }
}
