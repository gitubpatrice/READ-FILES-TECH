package com.readfilestech.read_files_tech.data.scanner

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.images.ImageBounds
import com.readfilestech.read_files_tech.data.images.ImageSize
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The scanner's import now takes what the decoder takes (`ImageToolRules.COMPRESS_EXTENSIONS`): a GIF, a HEIC
 * and a HEIF besides the 2.15.3's five. What of its pipeline runs on the JVM is what runs BEFORE Android's
 * decoder (`ImageDecoding.still`: the header read, the size checked); the decode, the straightening and the
 * JPEG of `ScanPages.render` are `android.graphics` and need a device, for every format. Files written by
 * javax.imageio, an encoder independent of the code under test.
 */
class ScanImportFormatsTest {
    private fun encoded(format: String, type: Int): ByteArray {
        val image = BufferedImage(WIDTH, HEIGHT, type).apply {
            val g = createGraphics()
            g.fillRect(WIDTH / 4, HEIGHT / 4, WIDTH / 2, HEIGHT / 2)
            g.dispose()
        }
        return ByteArrayOutputStream().also { assertThat(ImageIO.write(image, format, it)).isTrue() }.toByteArray()
    }

    private fun header(bytes: ByteArray) = bytes.copyOf(minOf(bytes.size, ImageBounds.HEADER_BYTES))

    @Test
    fun `a BMP passes the checks before the decoder, at its own size`() {
        val bmp = header(encoded("bmp", BufferedImage.TYPE_INT_RGB))
        assertThat(ImageBounds.probe(bmp)).isEqualTo(ImageSize(WIDTH.toLong(), HEIGHT.toLong()))
        assertThat(ImageBounds.refusalOf(bmp)).isNull()
    }

    @Test
    fun `a GIF passes the checks before the decoder, at its own size`() {
        val gif = header(encoded("gif", BufferedImage.TYPE_BYTE_INDEXED))
        assertThat(ImageBounds.probe(gif)).isEqualTo(ImageSize(WIDTH.toLong(), HEIGHT.toLong()))
        assertThat(ImageBounds.refusalOf(gif)).isNull()
    }

    /** No encoder of HEIC here: its header, an ISO-BMFF `ftyp` box. Its size is told by the decoder, which checks it then. */
    @Test
    fun `a HEIC or a HEIF is not refused before the decoder`() {
        listOf("heic", "mif1").forEach { brand ->
            val ftyp = byteArrayOf(0, 0, 0, FTYP_SIZE) + "ftyp$brand".toByteArray() + ByteArray(4) + "mif1heic".toByteArray()
            assertThat(ImageBounds.probe(ftyp)).isNull()
            assertThat(ImageBounds.refusalOf(ftyp)).isNull()
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FTYP_SIZE: Byte = 24
    }
}
