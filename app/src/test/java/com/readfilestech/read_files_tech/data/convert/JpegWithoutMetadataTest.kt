package com.readfilestech.read_files_tech.data.convert

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/** Images → PDF without the photo's GPS position, date or camera, and with its pixels untouched. */
class JpegWithoutMetadataTest {
    private fun segment(marker: Int, content: ByteArray): ByteArray {
        val length = content.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + content
    }

    private fun filtered(jpeg: ByteArray): ByteArray = JpegWithoutMetadata(ByteArrayInputStream(jpeg)).readBytes()

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val jfif = segment(0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))
    private val exif = segment(0xE1, "Exif\u0000\u0000GPSLatitude 48.8566".toByteArray())
    private val xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray())
    private val icc = segment(0xE2, "ICC_PROFILE\u0000\u0001\u0001".toByteArray())
    private val comment = segment(0xFE, "taken at home".toByteArray())
    private val iptc = segment(0xED, "Photoshop 3.0\u00008BIM".toByteArray())
    private val maker = segment(0xE5, "maker notes".toByteArray())
    private val adobe = segment(0xEE, "Adobe\u0000d\u0000\u0000\u0000\u0000\u0001".toByteArray())
    private val tables = segment(0xDB, ByteArray(65)) + segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
    private val scan = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0, 0x12, 0xFF.toByte(), 0, 0x34) +
        byteArrayOf(0xFF.toByte(), 0xD0.toByte(), 0x56, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `EXIF, XMP, IPTC, maker notes and comments are dropped, what draws the image is kept byte for byte`() {
        val jpeg = soi + jfif + exif + xmp + icc + comment + iptc + maker + adobe + tables + scan
        assertThat(filtered(jpeg)).isEqualTo(soi + jfif + icc + adobe + tables + scan)
    }

    /** Claude review R18: everything after the first scan was copied, end of image and trailers included. */
    @Test
    fun `a phone's trailer after the end of the image is left out — its capture time, its video`() {
        val sefTrailer = "SEFHImage_UTC_Data1728915060123MCC_Data208SEFT".toByteArray()
        val motionVideo = byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(64) { 0xFF.toByte() }
        val jpeg = soi + jfif + tables + scan
        assertThat(filtered(jpeg + sefTrailer + motionVideo)).isEqualTo(jpeg)
    }

    @Test
    fun `MPF's APP2 is left out, an ICC profile's APP2 kept`() {
        val mpf = segment(0xE2, "MPF\u0000II*\u0000".toByteArray())
        assertThat(filtered(soi + jfif + mpf + icc + tables + scan)).isEqualTo(soi + jfif + icc + tables + scan)
    }

    @Test
    fun `between the scans of a progressive JPEG, metadata is left out and the tables kept`() {
        val firstScan = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0, 0, 0x11, 0xFF.toByte(), 0, 0x22)
        val huffman = segment(0xC4, ByteArray(20) { it.toByte() })
        // Fill bytes before a marker are padding: dropped; the marker after them read as it is.
        val fill = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        val jpeg = soi + jfif + tables + firstScan + fill + exif + huffman + comment + scan
        assertThat(filtered(jpeg)).isEqualTo(soi + jfif + tables + firstScan + huffman + scan)
    }

    @Test
    fun `a marker without a length after a scan is refused`() {
        val firstScan = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0, 0, 0x11)
        // Read as a segment, TEM's next bytes would make a sound one (length 4) and the image would end well:
        // only TEM's own refusal fails it.
        val tem = byteArrayOf(0xFF.toByte(), 0x01, 0, 4, 0xAA.toByte(), 0xBB.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertThrows<IOException> { filtered(soi + jfif + tables + firstScan + tem) }
    }

    @Test
    fun `read one byte at a time, the same bytes`() {
        val jpeg = soi + jfif + exif + icc + tables + scan
        val input = JpegWithoutMetadata(ByteArrayInputStream(jpeg))
        val one = ByteArrayOutputStream()
        var b = input.read()
        while (b != -1) {
            one.write(b)
            b = input.read()
        }
        assertThat(one.toByteArray()).isEqualTo(filtered(jpeg))
    }

    @Test
    fun `a real JPEG keeps every pixel, and no trace of its EXIF`() {
        val picture = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 48) for (x in 0 until 64) picture.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or ((x + y) * 2))
        val plain = ByteArrayOutputStream().also { ImageIO.write(picture, "jpg", it) }.toByteArray()
        // ImageIO writes SOI then JFIF: the photo's metadata goes after it, as a camera's would.
        val afterJfif = 2 + 2 + ((plain[4].toInt() and 0xFF) shl 8 or (plain[5].toInt() and 0xFF))
        val photo = plain.copyOfRange(0, afterJfif) + exif + comment + plain.copyOfRange(afterJfif, plain.size)

        val out = filtered(photo)
        assertThat(String(out, Charsets.ISO_8859_1)).doesNotContain("GPSLatitude")
        assertThat(String(out, Charsets.ISO_8859_1)).doesNotContain("taken at home")
        assertThat(out).isEqualTo(plain)
        val decoded = ImageIO.read(ByteArrayInputStream(out))
        val original = ImageIO.read(ByteArrayInputStream(plain))
        for (y in 0 until 48) for (x in 0 until 64) assertThat(decoded.getRGB(x, y)).isEqualTo(original.getRGB(x, y))
    }

    @Test
    fun `not a JPEG, a length under 2, or a file cut inside a dropped segment, is refused`() {
        assertThrows<IOException> { filtered("GIF89a".toByteArray()) }
        // A length of 1, then a sound JPEG: only the length's own check can refuse it — dropped or kept.
        assertThrows<IOException> { filtered(soi + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 1) + tables + scan) }
        assertThrows<IOException> { filtered(soi + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 1) + tables + scan) }
        assertThrows<IOException> { filtered(soi + exif.copyOf(exif.size - 3)) }
    }
}
