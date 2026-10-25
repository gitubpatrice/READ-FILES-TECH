package com.readfilestech.read_files_tech.data.images

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * `test/image_bounds_test.dart` of the 2.15.3, case by case (its 21 tests, in its groups and order), then
 * what the port adds: the JPEG branch the Dart suite never tested, and the four defects fixed.
 *
 * Its `assertSafeBounds(...)` maps to [ImageBounds.refusalOf]: "isNotNull" is a refusal, "isNull" none;
 * its message "Dimensions image suspectes" is [ImageRefusal.SIDE], "Mpx" is [ImageRefusal.PIXELS].
 */
class ImageBoundsTest {
    private fun bytes(size: Int, vararg at: Pair<Int, List<Int>>) = ByteArray(size).also { b ->
        at.forEach { (offset, values) -> values.forEachIndexed { i, v -> b[offset + i] = v.toByte() } }
    }

    private val pngMagic = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Nested
    inner class ProbeDimensions {
        @Test
        fun `PNG IHDR — width and height big-endian`() {
            val png = bytes(
                28,
                0 to pngMagic,
                8 to listOf(0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52),
                16 to listOf(0x00, 0x00, 0x04, 0x00),
                20 to listOf(0x00, 0x00, 0x03, 0x00),
            )
            assertThat(ImageBounds.probe(png)).isEqualTo(ImageSize(1024, 768))
        }

        @Test
        fun `GIF LSD — width and height little-endian`() {
            val gif = bytes(24, 0 to listOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61), 6 to listOf(0x00, 0x04, 0x00, 0x03))
            assertThat(ImageBounds.probe(gif)).isEqualTo(ImageSize(1024, 768))
        }

        @Test
        fun `an unknown format gives null`() {
            assertThat(ImageBounds.probe(ByteArray(100))).isNull()
        }

        @Test
        fun `too few bytes give null`() {
            assertThat(ImageBounds.probe(ByteArray(10))).isNull()
        }
    }

    @Nested
    inner class AssertSafeBounds {
        @Test
        fun `PNG 50000 x 50000 (image bomb) refused`() {
            val bomb = bytes(28, 0 to pngMagic, 16 to listOf(0x00, 0x00, 0xC3, 0x50), 20 to listOf(0x00, 0x00, 0xC3, 0x50))
            assertThat(ImageBounds.refusalOf(bomb)).isEqualTo(ImageRefusal.SIDE)
        }

        @Test
        fun `PNG 1024 x 768 (benign) accepted`() {
            val png = bytes(28, 0 to pngMagic, 16 to listOf(0x00, 0x00, 0x04, 0x00), 20 to listOf(0x00, 0x00, 0x03, 0x00))
            assertThat(ImageBounds.refusalOf(png)).isNull()
        }

        @Test
        fun `an unknown format is let through (the decoder's header is checked after)`() {
            assertThat(ImageBounds.refusalOf(ByteArray(100))).isNull()
        }
    }

    /** "trous de couverture comblés le 2026-08-11". */
    @Nested
    inner class CoverageHoles {
        private fun gif(w: Int, h: Int, size: Int = 10): ByteArray {
            val b = bytes(10, 0 to listOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61), 6 to listOf(w and 0xFF, w shr 8, h and 0xFF, h shr 8))
            // Cut AFTER writing: a file stopping short, without writing past the buffer.
            return if (size >= 10) b else b.copyOf(size)
        }

        @Test
        fun `a GIF of 10 bytes announcing 65535 x 65535 is REFUSED`() {
            // THE hole: the threshold `length < 24` was global, while a GIF says its dimensions by byte 10.
            assertThat(ImageBounds.probe(gif(65535, 65535))).isEqualTo(ImageSize(65535, 65535))
            assertThat(ImageBounds.refusalOf(gif(65535, 65535))).isNotNull()
        }

        @Test
        fun `a GIF of 10 bytes of normal dimensions passes`() {
            assertThat(ImageBounds.refusalOf(gif(800, 600))).isNull()
        }

        @Test
        fun `a GIF cut at 9 bytes cannot be read, so is not judged`() {
            // Its dimensions are not all written yet: making them up would be worse than letting it through.
            assertThat(ImageBounds.probe(gif(1, 1, size = 9))).isNull()
        }

        private fun bmp(w: Int, h: Int) = bytes(26, 0 to listOf(0x42, 0x4D), 18 to le32(w), 22 to le32(h))

        @Test
        fun `a BMP of absurd dimensions is refused`() {
            assertThat(ImageBounds.refusalOf(bmp(50000, 50000))).isNotNull()
        }

        @Test
        fun `a normal BMP passes, a negative height included`() {
            assertThat(ImageBounds.refusalOf(bmp(1024, 768))).isNull()
            // A negative height gives the order of the rows, not an anomaly.
            assertThat(ImageBounds.refusalOf(bmp(1024, -768))).isNull()
        }

        private fun webpVp8(w: Int, h: Int) = bytes(
            30,
            0 to "RIFF".codes(),
            8 to "WEBP".codes(),
            12 to "VP8 ".codes(),
            26 to listOf(w and 0xFF, (w shr 8) and 0x3F, h and 0xFF, (h shr 8) and 0x3F),
        )

        @Test
        fun `a WebP of absurd dimensions is refused`() {
            // 16383 is the most 14 bits code: 16383 x 16383 = 268 Mpx.
            assertThat(ImageBounds.refusalOf(webpVp8(16383, 16383))).isNotNull()
        }

        @Test
        fun `a normal WebP passes`() {
            assertThat(ImageBounds.refusalOf(webpVp8(1920, 1080))).isNull()
        }
    }

    /** "WebP tronqué — le seuil qui désarmait la garde". */
    @Nested
    inner class TruncatedWebp {
        /** A VP8L of [size] bytes announcing [w] x [h]: 14 bits each, minus one, packed at 21. */
        private fun webpVp8l(size: Int, w: Int, h: Int): ByteArray {
            val b = bytes(size, 0 to "RIFF".codes(), 8 to "WEBP".codes(), 12 to "VP8L".codes())
            val bits = ((w - 1) and 0x3FFF) or (((h - 1) and 0x3FFF) shl 14)
            for (i in 0 until 4) if (size > 21 + i) b[21 + i] = (bits shr (8 * i)).toByte()
            return b
        }

        @Test
        fun `a VP8L of 25 bytes of the largest dimensions is REFUSED`() {
            // The defect: the WebP branch required 30 bytes where VP8L is read from 25.
            val trap = webpVp8l(25, 16384, 16384)
            assertWithMessage("a file of 25 bytes must be inspected, not ignored").that(ImageBounds.probe(trap)).isNotNull()
            assertWithMessage("16384 x 16384 is over the cap").that(ImageBounds.refusalOf(trap)).isNotNull()
        }

        @Test
        fun `every size from 25 to 30 bytes is covered`() {
            // The hole was a RANGE: sweeping it leaves no byte of margin to a threshold fixed wrong.
            for (size in 25..30) {
                assertWithMessage("size $size").that(ImageBounds.refusalOf(webpVp8l(size, 16384, 16384))).isNotNull()
            }
        }

        @Test
        fun `a small legitimate VP8L stays accepted`() {
            // The reverse check: refusing EVERY short WebP would pass the previous test for the wrong reason.
            val sound = webpVp8l(25, 800, 600)
            assertThat(ImageBounds.probe(sound)).isEqualTo(ImageSize(800, 600))
            assertThat(ImageBounds.refusalOf(sound)).isNull()
        }

        @Test
        fun `a WebP too short to carry its kind is ignored`() {
            // Under 16 bytes the kind is not readable: there is nothing to assert.
            for (size in 4 until 16) {
                val short = bytes(size, 0 to "RIFF".codes().take(size))
                assertWithMessage("$size").that(ImageBounds.probe(short)).isNull()
            }
            // The port's addition: the Dart suite's 'RIFF' + zeros never reads as a WebP at all, so it could not
            // fail; a real WebP header cut short can — it must be ignored, never read past its end.
            for (size in 4 until 25) {
                val cut = webpVp8l(30, 16384, 16384).copyOf(size)
                assertWithMessage("cut at $size").that(ImageBounds.probe(cut)).isNull()
            }
        }
    }

    /** The port's: an animation is decoded at full size whatever the subsampling, so only up to 4K is played. */
    @Nested
    inner class Playable {
        @Test
        fun `an animation up to 4K UHD plays, one pixel more shows its first frame`() {
            assertThat(ImageBounds.playable(ImageSize(3840, 2160))).isTrue()
            assertThat(ImageBounds.playable(ImageSize(2160, 3840))).isTrue()
            assertThat(ImageBounds.playable(ImageSize(3840, 2161))).isFalse()
            // Measured on a Galaxy S9: played, this one held 256 MB of native memory at its first frame.
            assertThat(ImageBounds.playable(ImageSize(8000, 8000))).isFalse()
        }

        @Test
        fun `an animation of unknown size is not played`() {
            assertThat(ImageBounds.playable(null)).isFalse()
        }
    }

    /** "plafond en PIXELS — le contrôle par côté ne suffit pas". */
    @Nested
    inner class PixelCap {
        private fun png(w: Long, h: Long) = bytes(24, 0 to pngMagic.take(4), 16 to be32(w), 20 to be32(h))

        @Test
        fun `12000 x 12000 passes both side bounds and must be REFUSED`() {
            // 144 Mpx, 576 MB of RGBA, for a PNG of a few hundred KB.
            val trap = png(12000, 12000)
            assertThat(ImageBounds.probe(trap)).isEqualTo(ImageSize(12000, 12000))
            assertThat(ImageBounds.refusalOf(trap)).isEqualTo(ImageRefusal.PIXELS)
        }

        @Test
        fun `a 48 Mpx photo stays accepted`() {
            // The reverse check: a cap absurdly low would refuse every real photo and pass the test above.
            assertThat(ImageBounds.refusalOf(png(8000, 6000))).isNull()
        }

        @Test
        fun `the limit is sharp, on both sides`() {
            val max = 64_000_000L
            assertThat(ImageBounds.refusalOf(png(8000, max / 8000))).isNull()
            assertThat(ImageBounds.refusalOf(png(8000, max / 8000 + 1))).isNotNull()
        }
    }

    /** What the Dart suite did not cover: its JPEG branch, never tested, and the defects fixed in the port. */
    @Nested
    inner class AddedByThePort {
        /** SOI, an APP0 segment of [app0] bytes, then a SOF[marker] announcing [w] x [h]. */
        private fun jpeg(w: Int, h: Int, app0: Int = 16, marker: Int = 0xC0, fill: Int = 0): ByteArray {
            val head = listOf(0xFF, 0xD8, 0xFF, 0xE0, app0 shr 8, app0 and 0xFF) + List(app0 - 2) { 0 }
            val sof = List(fill) { 0xFF } + listOf(0xFF, marker, 0x00, 0x11, 0x08, h shr 8, h and 0xFF, w shr 8, w and 0xFF)
            return bytes(head.size + sof.size + 8, 0 to head + sof)
        }

        @Test
        fun `a JPEG gives the dimensions of its first frame, past its segments`() {
            assertThat(ImageBounds.probe(jpeg(4032, 3024))).isEqualTo(ImageSize(4032, 3024))
            // Progressive (SOF2) as baseline; a frame past an APP1 of 60 KB, as a camera's EXIF.
            assertThat(ImageBounds.probe(jpeg(640, 480, marker = 0xC2))).isEqualTo(ImageSize(640, 480))
            assertThat(ImageBounds.probe(jpeg(640, 480, app0 = 60_000))).isEqualTo(ImageSize(640, 480))
            // DHT is not a frame: the probe goes past it (here, to the end of the bytes).
            assertThat(ImageBounds.probe(jpeg(640, 480, marker = 0xC4))).isNull()
        }

        @Test
        fun `a JPEG bomb is refused, and a frame beyond the bytes read is not judged`() {
            assertThat(ImageBounds.refusalOf(jpeg(65535, 65535))).isEqualTo(ImageRefusal.SIDE)
            // Its frame after 64 KiB, behind the largest segment there is: the decoder's own reading of the
            // header decides (ImageDecoding).
            val beyond = jpeg(65535, 65535, app0 = 65_535)
            assertThat(beyond.size).isGreaterThan(ImageBounds.HEADER_BYTES)
            assertThat(ImageBounds.probe(beyond.copyOf(ImageBounds.HEADER_BYTES))).isNull()
        }

        /** Fixed: the 2.15.x looked at a JPEG of 24 bytes or more only — the global threshold its GIF lost. */
        @Test
        fun `a JPEG of 11 bytes announcing 65535 x 65535 is REFUSED`() {
            val short = bytes(11, 0 to listOf(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0xFF, 0xFF, 0xFF, 0xFF))
            assertThat(ImageBounds.refusalOf(short)).isEqualTo(ImageRefusal.SIDE)
            // One byte less, the width is not all there.
            assertThat(ImageBounds.probe(short.copyOf(10))).isNull()
        }

        /** Fixed: `FF FF` before a marker (fill bytes, allowed by the standard) was read as a segment's length. */
        @Test
        fun `fill bytes before a marker are skipped`() {
            assertThat(ImageBounds.probe(jpeg(65535, 65535, fill = 3))).isEqualTo(ImageSize(65535, 65535))
        }

        /** Fixed: an OS/2 BMP holds its dimensions on 16 bits; read on 32, a legitimate one was refused. */
        @Test
        fun `an OS-2 BMP reads its dimensions on 16 bits`() {
            val os2 = bytes(26, 0 to listOf(0x42, 0x4D), 14 to le32(12), 18 to listOf(0x00, 0x04, 0x00, 0x03))
            assertThat(ImageBounds.probe(os2)).isEqualTo(ImageSize(1024, 768))
            assertThat(ImageBounds.refusalOf(os2)).isNull()
        }

        @Test
        fun `PNG sides past 2^31 stay positive, and a BMP height of -2^31 has a size`() {
            val huge = bytes(24, 0 to pngMagic.take(4), 16 to listOf(0xFF, 0xFF, 0xFF, 0xFF), 20 to listOf(0x80, 0, 0, 0))
            assertThat(ImageBounds.probe(huge)).isEqualTo(ImageSize(4_294_967_295, 2_147_483_648))
            val lowest = bytes(26, 0 to listOf(0x42, 0x4D), 18 to le32(10), 22 to listOf(0, 0, 0, 0x80))
            assertThat(ImageBounds.probe(lowest)).isEqualTo(ImageSize(10, 2_147_483_648))
            assertThat(ImageBounds.refusalOf(lowest)).isEqualTo(ImageRefusal.SIDE)
        }

        @Test
        fun `a side of zero or negative is a damaged header, unreadable rather than too large`() {
            assertThat(ImageBounds.refusalOf(0, 10)).isEqualTo(ImageRefusal.DAMAGED)
            assertThat(ImageBounds.refusalOf(-1024, 768)).isEqualTo(ImageRefusal.DAMAGED)
            assertThrows<UnreadableFileException> { ImageBounds.check(0, 10) }
            val refused = assertThrows<ImageTooLargeException> { ImageBounds.check(12000, 12000) }
            assertThat(refused.width to refused.height).isEqualTo(12000L to 12000L)
            assertThrows<ImageTooLargeException> { ImageBounds.check(12001, 1) }
            assertDoesNotThrow { ImageBounds.check(12000, 5333) }
        }
    }

    private fun String.codes() = map { it.code }

    private fun le32(v: Int) = (0 until 4).map { (v shr (8 * it)) and 0xFF }

    private fun be32(v: Long) = (3 downTo 0).map { ((v shr (8 * it)) and 0xFF).toInt() }
}
