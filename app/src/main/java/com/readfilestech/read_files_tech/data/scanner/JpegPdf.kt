package com.readfilestech.read_files_tech.data.scanner

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** What a PDF needs to know of a JPEG to embed it as it is. */
data class JpegInfo(val width: Int, val height: Int, val components: Int) {
    companion object {
        internal const val MARKER = 0xFF
        internal const val SOI = 0xD8
        internal const val EOI = 0xD9
        internal const val SOS = 0xDA
        private const val TEM = 0x01
        private const val RST_FIRST = 0xD0
        private const val RST_LAST = 0xD7

        /** The start-of-frame markers a PDF reader decodes: baseline, extended, progressive (Huffman). */
        private val FRAMES = setOf(0xC0, 0xC1, 0xC2)
        private val CHANNELS = setOf(1, 3) // grey, or YCbCr

        /**
         * The size and channels of the JPEG [input] starts with, read from its frame header.
         *
         * @throws IOException not a JPEG a PDF can carry: no frame header before the image data, an
         *   arithmetic or lossless one, or neither grey nor YCbCr (CMYK's inverted Adobe convention is
         *   not handled: the app writes none).
         */
        fun read(input: InputStream): JpegInfo {
            val data = DataInputStream(input)
            if (data.readUnsignedByte() != MARKER || data.readUnsignedByte() != SOI) throw IOException("not a JPEG")
            var marker = nextMarker(data)
            while (marker !in FRAMES) {
                skipSegment(data, marker)
                marker = nextMarker(data)
            }
            return frame(data)
        }

        /** A segment before the frame header, skipped; the image data or its end first means there is none. */
        private fun skipSegment(data: DataInputStream, marker: Int) {
            if (marker == SOS || marker == EOI) throw IOException("no frame header")
            val length = data.readUnsignedShort()
            if (length < 2) throw IOException("bad segment length")
            skipFully(data, length - 2L)
        }

        /** The next marker, past any fill bytes; standalone markers (no length) are not expected before SOS. */
        internal fun nextMarker(data: DataInputStream): Int {
            if (data.readUnsignedByte() != MARKER) throw IOException("marker expected")
            var marker = data.readUnsignedByte()
            while (marker == MARKER) marker = data.readUnsignedByte()
            if (marker == TEM || marker in RST_FIRST..RST_LAST || marker == SOI) throw IOException("unexpected marker")
            return marker
        }

        private fun frame(data: DataInputStream): JpegInfo {
            data.readUnsignedShort() // the segment's length
            val precision = data.readUnsignedByte()
            val height = data.readUnsignedShort()
            val width = data.readUnsignedShort()
            val components = data.readUnsignedByte()
            val sized = width > 0 && height > 0
            if (precision != Byte.SIZE_BITS || !sized || components !in CHANNELS) {
                throw IOException("unsupported JPEG frame")
            }
            return JpegInfo(width, height, components)
        }

        internal fun skipFully(input: InputStream, count: Long) {
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw EOFException()
                    left--
                } else {
                    left -= skipped
                }
            }
        }
    }
}

/** One page of the PDF: a JPEG of [length] bytes, embedded as it is. */
class JpegPage(val info: JpegInfo, val length: Long, val open: () -> InputStream)

/**
 * A PDF made of JPEG pages, written by hand: each page is one image, its compressed bytes copied as they
 * are (`/DCTDecode`) — nothing decoded, nothing re-encoded, so the scan keeps its quality and the PDF its
 * size. `android.graphics.pdf.PdfDocument` would draw the bitmap and re-encode it; PdfBox would add
 * megabytes for this. A page is the image's shape, its long side the long side of A4 (842 points).
 *
 * Objects: 1 the catalogue, 2 the page tree, then three per page (page, image, content). The pages are
 * streamed: one JPEG at a time passes through, never the whole document. No metadata (no producer, no
 * date): a scan says nothing about the phone that made it.
 */
object JpegPdf {
    /** The long side of A4, in points. */
    const val LONG_SIDE_PT = 842.0

    private const val CATALOG = 1
    private const val PAGES = 2
    private const val FIRST_PAGE = 3
    private const val OBJECTS_PER_PAGE = 3
    private const val COPY_BUFFER = 64 * 1024
    private const val OFFSET_DIGITS = 10
    private const val GENERATION_FREE = 65_535

    /** The pages of [pages], in order, as a PDF on [out] (not closed). */
    fun write(out: OutputStream, pages: List<JpegPage>) {
        require(pages.isNotEmpty()) { "a PDF needs a page" }
        val pdf = Counting(out)
        val offsets = LongArray(FIRST_PAGE + OBJECTS_PER_PAGE * pages.size)
        pdf.ascii("%PDF-1.4\n%")
        // Four bytes above 127: tells a transfer that the file is binary.
        pdf.bytes(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
        pdf.ascii("\n")
        pages.forEachIndexed { i, page ->
            val number = FIRST_PAGE + OBJECTS_PER_PAGE * i
            writePage(pdf, offsets, number, page)
        }
        offsets[PAGES] = pdf.count
        val kids = pages.indices.joinToString(" ") { "${FIRST_PAGE + OBJECTS_PER_PAGE * it} 0 R" }
        pdf.ascii("$PAGES 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")
        offsets[CATALOG] = pdf.count
        pdf.ascii("$CATALOG 0 obj\n<< /Type /Catalog /Pages $PAGES 0 R >>\nendobj\n")
        val xref = pdf.count
        pdf.ascii("xref\n0 ${offsets.size}\n${"0".repeat(OFFSET_DIGITS)} $GENERATION_FREE f \n")
        for (n in 1 until offsets.size) pdf.ascii("${offsets[n].toString().padStart(OFFSET_DIGITS, '0')} 00000 n \n")
        pdf.ascii("trailer\n<< /Size ${offsets.size} /Root $CATALOG 0 R >>\nstartxref\n$xref\n%%EOF\n")
        pdf.flush()
    }

    private fun writePage(pdf: Counting, offsets: LongArray, number: Int, page: JpegPage) {
        val image = number + 1
        val content = number + 2
        val info = page.info
        val long = maxOf(info.width, info.height).toDouble()
        val w = points(LONG_SIDE_PT * info.width / long)
        val h = points(LONG_SIDE_PT * info.height / long)
        offsets[number] = pdf.count
        pdf.ascii(
            "$number 0 obj\n<< /Type /Page /Parent $PAGES 0 R /MediaBox [0 0 $w $h] " +
                "/Resources << /XObject << /Im0 $image 0 R >> >> /Contents $content 0 R >>\nendobj\n",
        )
        offsets[image] = pdf.count
        val space = if (info.components == 1) "/DeviceGray" else "/DeviceRGB"
        pdf.ascii(
            "$image 0 obj\n<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                "/ColorSpace $space /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.length} >>\nstream\n",
        )
        page.open().use { copyExactly(it, pdf, page.length) }
        pdf.ascii("\nendstream\nendobj\n")
        val drawing = "q\n$w 0 0 $h 0 0 cm\n/Im0 Do\nQ\n"
        offsets[content] = pdf.count
        pdf.ascii("$content 0 obj\n<< /Length ${drawing.length} >>\nstream\n${drawing}endstream\nendobj\n")
    }

    /** A length in points, two decimals at most, never in exponent notation. */
    private fun points(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

    /** Exactly [length] bytes of [input]: a file that changed size since it was measured is refused. */
    private fun copyExactly(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IOException("page shorter than announced")
            out.write(buffer, 0, n)
            left -= n
        }
        if (input.read() >= 0) throw IOException("page longer than announced")
    }

    /** Counts what passes, for the cross-reference table's offsets. */
    private class Counting(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        fun ascii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

        fun bytes(b: ByteArray) = write(b)
    }
}
