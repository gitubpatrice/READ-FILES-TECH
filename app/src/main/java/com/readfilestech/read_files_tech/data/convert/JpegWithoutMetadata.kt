package com.readfilestech.read_files_tech.data.convert

import com.readfilestech.read_files_tech.data.scanner.JpegInfo
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A JPEG as it is, its pixels untouched, without what describes the photo rather than draws it:
 * - every application segment but JFIF (APP0), an ICC profile (APP2 named `ICC_PROFILE`) and Adobe's colour
 *   transform (APP14) — so EXIF and XMP (APP1: GPS position, date, camera), MPF (APP2), IPTC (APP13), the
 *   makers' own —, and comments, wherever they stand: before the image data, or between the scans of a
 *   progressive JPEG;
 * - everything after the end of the image (EOI): a Samsung photo's SEF trailer (its capture time, its
 *   country), a motion photo's video and sound, the other images of an MPF file (Claude review R18).
 *
 * Images → PDF copies a JPEG byte for byte (docs D-005): without this, its GPS position went into the PDF —
 * the leak the 2.15.3's Compress had (inventory D13). Its orientation is applied by the page, read before.
 * One segment, or one piece of image data, held at a time; [source] is not closed: its owner closes it.
 *
 * @throws IOException (when read) not a JPEG: no start of image, a segment length under 2, a marker where none
 *   may stand, or the file ending inside a segment.
 */
internal class JpegWithoutMetadata(source: InputStream) : InputStream() {
    private val data = DataInputStream(BufferedInputStream(source, BUFFER_BYTES))
    private var pending = ByteArray(0)
    private var position = 0
    private var state = State.START

    /** The marker that ended a scan, read already: the next segment's. */
    private var held: Int? = null

    private enum class State { START, SEGMENTS, SCAN, END }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (len > 0 && position == pending.size && state != State.END) pending = next().also { position = 0 }
        return when {
            len == 0 -> 0
            position == pending.size -> -1
            else -> minOf(len, pending.size - position).also { count ->
                System.arraycopy(pending, position, b, off, count)
                position += count
            }
        }
    }

    private fun next(): ByteArray = when (state) {
        State.START -> start()
        State.SEGMENTS -> segment(held?.also { held = null } ?: JpegInfo.nextMarker(data))
        State.SCAN -> scan()
        State.END -> ByteArray(0)
    }

    private fun start(): ByteArray {
        if (data.readUnsignedByte() != JpegInfo.MARKER || data.readUnsignedByte() != JpegInfo.SOI) throw IOException("not a JPEG")
        state = State.SEGMENTS
        return markerBytes(JpegInfo.SOI)
    }

    /** The segment of [marker], whole when kept, empty when dropped; EOI ends the image, SOS starts a scan. */
    private fun segment(marker: Int): ByteArray {
        if (marker == JpegInfo.EOI) {
            state = State.END
            return markerBytes(marker)
        }
        // A marker without a length where a segment must stand (after a scan, not checked by nextMarker).
        if (marker == TEM || marker == JpegInfo.SOI) throw IOException("unexpected marker")
        val length = data.readUnsignedShort()
        if (length < LENGTH_BYTES) throw IOException("bad segment length")
        val content = ByteArray(length - LENGTH_BYTES).also { data.readFully(it) }
        if (marker == JpegInfo.SOS) state = State.SCAN
        return if (kept(marker, content)) {
            markerBytes(marker) + byteArrayOf((length ushr BYTE_BITS).toByte(), length.toByte()) + content
        } else {
            ByteArray(0)
        }
    }

    /**
     * The image data of a scan, up to [SCAN_CHUNK] bytes at a time, as it is: its stuffed bytes (FF 00) and
     * restart markers included, fill bytes (FF FF) left out. Another marker ends the scan; the file ending
     * inside it ends the image there.
     */
    private fun scan(): ByteArray {
        val out = ByteArrayOutputStream(SCAN_CHUNK)
        while (out.size() < SCAN_CHUNK && state == State.SCAN) {
            val byte = data.read()
            if (byte == JpegInfo.MARKER) {
                var marker = data.read()
                while (marker == JpegInfo.MARKER) marker = data.read()
                when (marker) {
                    -1 -> state = State.END
                    STUFFED, in RST_FIRST..RST_LAST -> out.write(byteArrayOf(JpegInfo.MARKER.toByte(), marker.toByte()))
                    else -> {
                        held = marker
                        state = State.SEGMENTS
                    }
                }
            } else if (byte == -1) {
                state = State.END
            } else {
                out.write(byte)
            }
        }
        return out.toByteArray()
    }

    private fun markerBytes(marker: Int) = byteArrayOf(JpegInfo.MARKER.toByte(), marker.toByte())

    private fun kept(marker: Int, content: ByteArray): Boolean = when (marker) {
        APP0, APP14 -> true
        APP2 -> content.size >= ICC_PROFILE.size && content.copyOf(ICC_PROFILE.size).contentEquals(ICC_PROFILE)
        in APP_FIRST..APP_LAST, COM -> false
        else -> true
    }

    private companion object {
        const val APP_FIRST = 0xE0
        const val APP_LAST = 0xEF
        const val APP0 = 0xE0
        const val APP2 = 0xE2
        const val APP14 = 0xEE
        const val COM = 0xFE
        const val TEM = 0x01
        const val STUFFED = 0x00
        const val RST_FIRST = 0xD0
        const val RST_LAST = 0xD7

        /** The identifier an ICC profile's APP2 starts with; MPF's APP2 starts otherwise. */
        val ICC_PROFILE = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)

        /** A segment's length counts its own two bytes. */
        const val LENGTH_BYTES = 2
        const val BYTE = 0xFF
        const val BYTE_BITS = 8
        const val BUFFER_BYTES = 64 * 1024
        const val SCAN_CHUNK = 64 * 1024
    }
}
