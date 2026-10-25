package com.readfilestech.read_files_tech.data.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * The PDF writer, read back by a parser of the test's own (the cross-reference table, every object at
 * its offset, the images byte for byte). The independent oracle is `tools/scanner/check_pdf.py` (pypdf):
 * this test leaves its PDFs and their manifest in `app/build/scanner-pdf/` for it.
 */
class JpegPdfTest {
    private fun jpeg(w: Int, h: Int, type: Int = BufferedImage.TYPE_INT_RGB): ByteArray {
        val image = BufferedImage(w, h, type)
        val g = image.createGraphics()
        g.color = java.awt.Color(240, 236, 228)
        g.fillRect(0, 0, w, h)
        g.color = java.awt.Color(30, 30, 80)
        for (y in 10 until h - 10 step 14) g.fillRect(12, y, w - 24, 5)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun page(bytes: ByteArray) = JpegPage(
        JpegInfo.read(ByteArrayInputStream(bytes)),
        bytes.size.toLong()
    ) { ByteArrayInputStream(bytes) }

    private fun pdf(pages: List<JpegPage>): ByteArray = ByteArrayOutputStream().also {
        JpegPdf.write(it, pages)
    }.toByteArray()

    /** The objects of [pdf], found through its cross-reference table only. */
    private fun objects(pdf: ByteArray): Map<Int, String> {
        val text = String(pdf, Charsets.ISO_8859_1)
        val startxref = Regex("startxref\\n(\\d+)\\n%%EOF\\n$").find(text)!!.groupValues[1].toInt()
        assertThat(text.substring(startxref)).startsWith("xref\n0 ")
        val count = Regex("xref\\n0 (\\d+)\\n").find(text, startxref)!!.groupValues[1].toInt()
        val table = text.substring(startxref).substringAfter("\n").substringAfter("\n")
        val entries = table.chunked(20).take(count)
        assertThat(entries[0]).isEqualTo("0000000000 65535 f \n")
        return (1 until count).associateWith { n ->
            val entry = entries[n]
            assertThat(entry).matches("\\d{10} 00000 n \\n")
            val offset = entry.take(10).toInt()
            assertThat(text.substring(offset)).startsWith("$n 0 obj\n")
            text.substring(offset, text.indexOf("endobj\n", offset))
        }
    }

    @Test
    fun `three pages, each its JPEG byte for byte, sized to its shape at A4's long side`() {
        val landscape = jpeg(400, 300)
        val portrait = jpeg(210, 297, BufferedImage.TYPE_BYTE_GRAY)
        val square = jpeg(128, 128)
        val pdf = pdf(listOf(page(landscape), page(portrait), page(square)))
        val text = String(pdf, Charsets.ISO_8859_1)
        assertThat(text).startsWith("%PDF-1.4\n%")
        val objects = objects(pdf)
        assertThat(objects).hasSize(2 + 3 * 3)
        assertThat(objects.getValue(1)).contains("/Type /Catalog /Pages 2 0 R")
        assertThat(objects.getValue(2)).contains("/Kids [3 0 R 6 0 R 9 0 R] /Count 3")
        assertThat(objects.getValue(3)).contains("/MediaBox [0 0 842 631.5]")
        assertThat(objects.getValue(6)).contains("/MediaBox [0 0 595.35 842]")
        assertThat(objects.getValue(9)).contains("/MediaBox [0 0 842 842]")
        assertThat(objects.getValue(4)).contains("/Width 400 /Height 300 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode")
        assertThat(objects.getValue(7)).contains("/Width 210 /Height 297 /ColorSpace /DeviceGray")
        listOf(4 to landscape, 7 to portrait, 10 to square).forEach { (n, jpeg) ->
            val obj = objects.getValue(n)
            assertThat(obj).contains("/Length ${jpeg.size} >>")
            val body = obj.substringAfter("stream\n").substringBeforeLast("\nendstream")
            assertThat(body.toByteArray(Charsets.ISO_8859_1).toList()).isEqualTo(jpeg.toList())
        }
        // The page draws its image over the whole page.
        assertThat(objects.getValue(5)).contains("q\n842 0 0 631.5 0 0 cm\n/Im0 Do\nQ\n")
        assertThat(objects.getValue(3)).contains("/Contents 5 0 R")
        // No metadata: no producer, no date.
        assertThat(text).doesNotContain("/Info")
        assertThat(text).doesNotContain("/Producer")
        save("three-pages", pdf, listOf(landscape, portrait, square))
    }

    @Test
    fun `a page that changed size since it was measured is refused`() {
        val bytes = jpeg(64, 48)
        val info = JpegInfo.read(ByteArrayInputStream(bytes))
        val shorter = JpegPage(info, bytes.size + 1L) { ByteArrayInputStream(bytes) }
        val longer = JpegPage(info, bytes.size - 1L) { ByteArrayInputStream(bytes) }
        assertThrows<IOException> { pdf(listOf(shorter)) }
        assertThrows<IOException> { pdf(listOf(longer)) }
    }

    @Test
    fun `a JPEG's size and channels come from its frame header`() {
        assertThat(JpegInfo.read(ByteArrayInputStream(jpeg(321, 123)))).isEqualTo(JpegInfo(321, 123, 3))
        assertThat(JpegInfo.read(ByteArrayInputStream(jpeg(9, 700, BufferedImage.TYPE_BYTE_GRAY)))).isEqualTo(JpegInfo(9, 700, 1))
    }

    @Test
    fun `what a PDF cannot carry as a JPEG is refused`() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val cmyk = bytes(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x14, 0x08, 0x00, 0x10, 0x00, 0x20, 0x04) + ByteArray(12)
        val arithmetic = bytes(0xFF, 0xD8, 0xFF, 0xC9, 0x00, 0x11, 0x08, 0x00, 0x10, 0x00, 0x20, 0x03) + ByteArray(9)
        val noFrame = bytes(0xFF, 0xD8, 0xFF, 0xDA, 0x00, 0x08, 0, 0, 0, 0, 0, 0)
        val zeroWide = bytes(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0x10, 0x00, 0x00, 0x03) + ByteArray(9)
        val truncated = jpeg(50, 50).copyOf(10)
        listOf(png, cmyk, arithmetic, noFrame, zeroWide, truncated, ByteArray(0)).forEach { bad ->
            assertThrows<IOException> { JpegInfo.read(ByteArrayInputStream(bad)) }
        }
    }

    @Test
    fun `a PDF without pages is not written`() {
        assertThrows<IllegalArgumentException> { pdf(emptyList()) }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** For tools/scanner/check_pdf.py: the PDF, and what each of its pages must hold. */
    private fun save(name: String, pdf: ByteArray, jpegs: List<ByteArray>) {
        val dir = File("build/scanner-pdf").apply { mkdirs() }
        File(dir, "$name.pdf").writeBytes(pdf)
        val pages = jpegs.joinToString(",\n") { j ->
            val info = JpegInfo.read(ByteArrayInputStream(j))
            val sha = MessageDigest.getInstance("SHA-256").digest(j).joinToString("") { "%02x".format(it) }
            """  {"width": ${info.width}, "height": ${info.height}, "components": ${info.components}, "sha256": "$sha"}"""
        }
        File(dir, "$name.json").writeText("[\n$pages\n]\n")
    }
}
