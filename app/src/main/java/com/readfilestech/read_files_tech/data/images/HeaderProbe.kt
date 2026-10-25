// The signatures and offsets of the formats' headers, byte by byte: the numbers ARE the definition.
@file:Suppress("MagicNumber")

package com.readfilestech.read_files_tech.data.images

import kotlin.math.absoluteValue

/**
 * `ImageBounds.probeDimensions` of the 2.15.x, format by format. The minimum length is PER FORMAT, never
 * global: a single `length < 24` let a GIF of 10 bytes announcing 65 535 × 65 535 (4.3 Gpx) through
 * unchecked (GPT review, 2026-08-11) — and the 2.15.x's JPEG branch kept that very threshold, fixed here.
 */
internal object HeaderProbe {
    private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47)
    private val GIF = intArrayOf(0x47, 0x49, 0x46)
    private val BMP = intArrayOf(0x42, 0x4D)
    private val RIFF = intArrayOf(0x52, 0x49, 0x46, 0x46)
    private val WEBP = intArrayOf(0x57, 0x45, 0x42, 0x50)
    private val VP8 = intArrayOf(0x56, 0x50, 0x38, 0x20)
    private val VP8L = intArrayOf(0x56, 0x50, 0x38, 0x4C)
    private val VP8X = intArrayOf(0x56, 0x50, 0x38, 0x58)
    private val JPEG = intArrayOf(0xFF, 0xD8)

    // No global minimum: [startsWith] never reads past the end, and each format checks its own length.
    fun of(b: ByteArray): ImageSize? = when {
        // PNG: IHDR, width at 16 and height at 20, big-endian, unsigned.
        b.startsWith(PNG) -> if (b.size < 24) null else ImageSize(b.u32be(16), b.u32be(20))
        // GIF: the logical screen, at 6 and 8, little-endian. Ten bytes read them, so ten refuse them.
        b.startsWith(GIF) -> if (b.size < 10) null else ImageSize(b.u16le(6), b.u16le(8))
        b.startsWith(BMP) -> bmp(b)
        // WebP: 'RIFF' .... 'WEBP', then its kind at 12 — 16 bytes, or no kind is read.
        b.startsWith(RIFF) && b.startsWith(WEBP, at = 8) -> webp(b)
        b.startsWith(JPEG) -> jpeg(b)
        else -> null
    }

    /**
     * Width and height at 18 and 22, signed 32 bits (a negative height says the rows go downwards, not
     * that the image is absurd). Added on 2026-08-11: the decoder read BMP, no bound inspected it.
     *
     * Fixed here: an OS/2 header (`BITMAPCOREHEADER`, its size of 12 at 14) holds them on 16 bits at 18 and
     * 20; the 2.15.x read them as 32 bits and refused such a file for an absurd width.
     */
    private fun bmp(b: ByteArray): ImageSize? = when {
        b.size < 26 -> null
        b.u32le(14) == 12L -> ImageSize(b.u16le(18), b.u16le(20))
        else -> ImageSize(b.i32le(18), b.i32le(22).absoluteValue)
    }

    /**
     * Three kinds of chunk, dimensions coded differently in each — each kind with its own minimum: the
     * 2.15.x's branch once required 30 bytes where a VP8L is read from 25, so a VP8L of 25 to 29 bytes
     * announcing 16 384 × 16 384 (268 Mpx) went through unchecked (fixed 2026-08-11).
     */
    private fun webp(b: ByteArray): ImageSize? = when {
        // 'VP8 ': simple. 14 bits each at 26 and 28 (the 2 upper bits are a scale).
        b.startsWith(VP8, at = 12) -> if (b.size < 30) null else ImageSize(b.u16le(26) and 0x3FFF, b.u16le(28) and 0x3FFF)
        // 'VP8L': lossless. 14 bits each, minus one, packed at 21.
        b.startsWith(VP8L, at = 12) -> if (b.size < 25) null else vp8l(b.u32le(21))
        // 'VP8X': extended. 24 bits each, minus one, at 24 and 27.
        b.startsWith(VP8X, at = 12) -> if (b.size < 30) null else ImageSize(b.u24le(24) + 1, b.u24le(27) + 1)
        else -> null
    }

    private fun vp8l(bits: Long) = ImageSize((bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)

    /**
     * The first frame marker, segment after segment: `[FF Cx][length 2][precision 1][height 2][width 2]`.
     *
     * Fixed here: the 2.15.x required 24 bytes before looking (a JPEG of 11 bytes announcing
     * 65 535 × 65 535 passed); it read a fill byte (`FF FF`, allowed before any marker) as a marker with a
     * length; and it gave up on a frame whose last byte was the last one read.
     */
    private fun jpeg(b: ByteArray): ImageSize? {
        var i = JPEG.size
        var frame: ImageSize? = null
        while (frame == null && i + 8 < b.size) {
            val marker = b.u8(i + 1)
            when {
                b.u8(i) != 0xFF || marker == 0xFF -> i++
                isFrame(marker) -> frame = ImageSize(b.u16be(i + 7), b.u16be(i + 5))
                else -> i = segmentEnd(b, i, marker)
            }
        }
        return frame
    }

    /** SOF0 to SOF15, but DHT, JPG and DAC. */
    private fun isFrame(marker: Int) = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    /**
     * Where the segment of [marker] at [at] ends: SOI, EOI, TEM and the restarts have no length; a length
     * under 2 cannot be — the header is damaged, and the search ends at the end of [b].
     */
    private fun segmentEnd(b: ByteArray, at: Int, marker: Int): Int = when {
        marker == 0xD8 || marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7 -> at + 2
        b.u16be(at + 2) < 2 -> b.size
        else -> at + 2 + b.u16be(at + 2).toInt()
    }
}

// The bytes of a header, read as the formats store their numbers.

/** [prefix] at [at] — false, never an exception, when the bytes end before it does. */
private fun ByteArray.startsWith(prefix: IntArray, at: Int = 0): Boolean =
    size >= at + prefix.size && prefix.indices.all { u8(at + it) == prefix[it] }

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u16le(at: Int): Long = (u8(at) or (u8(at + 1) shl 8)).toLong()

private fun ByteArray.u16be(at: Int): Long = ((u8(at) shl 8) or u8(at + 1)).toLong()

private fun ByteArray.u24le(at: Int): Long = u16le(at) or (u8(at + 2).toLong() shl 16)

private fun ByteArray.u32le(at: Int): Long = u24le(at) or (u8(at + 3).toLong() shl 24)

private fun ByteArray.u32be(at: Int): Long = (u16be(at) shl 16) or u16be(at + 2)

/** Signed: a BMP's height may be negative. */
private fun ByteArray.i32le(at: Int): Long = u32le(at).toInt().toLong()
