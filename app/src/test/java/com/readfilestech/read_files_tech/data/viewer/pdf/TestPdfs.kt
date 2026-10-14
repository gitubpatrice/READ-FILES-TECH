package com.readfilestech.read_files_tech.data.viewer.pdf

import java.io.ByteArrayOutputStream

/**
 * Minimal PDFs written byte by byte, for what no real software writes on request: the forms a hostile
 * file can give the encryption dictionary of a file encrypted for certificates (`/Adobe.PubSec`), which
 * the review of the plan asked to test (D-005). One page, nothing else. The device corpus has its own,
 * written by tools/corpus/gen_pdf.py.
 */
internal object TestPdfs {
    enum class PubSec {
        /** In the trailer, as is. */
        DIRECT,

        /** Its name written `/Adobe#2EPubSec`: a name decoded, `.` escaped. */
        ESCAPED_NAME,

        /** An indirect object, `/Encrypt 4 0 R`. */
        INDIRECT,

        /** Added by an incremental update: the first revision is not encrypted, the last one is. */
        INCREMENTAL,

        /** The trailer is a cross-reference stream's dictionary (PDF 1.5). */
        XREF_STREAM,
    }

    private const val PUB_SEC = "/Filter /Adobe.PubSec /SubFilter /adbe.pkcs7.s4 /V 1 /Recipients [<3080>]"

    fun pubSec(variant: PubSec): ByteArray = when (variant) {
        PubSec.DIRECT -> classic("/Encrypt << $PUB_SEC >>")
        PubSec.ESCAPED_NAME -> classic("/Encrypt << ${PUB_SEC.replace("/Adobe.PubSec", "/Adobe#2EPubSec")} >>")
        PubSec.INDIRECT -> classic("/Encrypt 4 0 R", "<< $PUB_SEC >>")
        PubSec.INCREMENTAL -> incremental()
        PubSec.XREF_STREAM -> xrefStream()
    }

    /** A file whose encryption dictionary names the handler [filter] (a name, its slash included). */
    fun encryptedBy(filter: String): ByteArray = classic("/Encrypt << /Filter $filter /V 1 >> /ID [<00> <00>]")

    /**
     * Two pages, and an outline a hostile file bends: A (page 1) with a child A.1 (page 2) whose child is A
     * again; B, on a page of a tree that is not the document's; C, whose `/Next` is itself, on a page whose
     * `/Parent` chain loops — each of its two nodes listing the other among its `/Kids`.
     */
    fun hostileOutline(): ByteArray = Writer().run {
        header("1.7")
        obj(1, "<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R 8 0 R] /Count 2 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")
        obj(4, "<< /Type /Outlines /First 5 0 R /Last 12 0 R >>")
        obj(5, "<< /Title (A) /Parent 4 0 R /Next 6 0 R /First 7 0 R /Last 7 0 R /Dest [3 0 R /Fit] >>")
        obj(6, "<< /Title (B) /Parent 4 0 R /Prev 5 0 R /Next 12 0 R /Dest [9 0 R /Fit] >>")
        obj(7, "<< /Title (A.1) /Parent 5 0 R /First 5 0 R /Last 5 0 R /Dest [8 0 R /Fit] >>")
        obj(8, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")
        obj(9, "<< /Type /Page /Parent 10 0 R /MediaBox [0 0 200 200] >>")
        obj(10, "<< /Type /Pages /Kids [9 0 R] /Count 1 /Parent 11 0 R >>")
        obj(11, "<< /Type /Pages /Kids [10 0 R] /Count 1 >>")
        obj(12, "<< /Title (C) /Parent 4 0 R /Prev 6 0 R /Next 12 0 R /Dest [13 0 R /Fit] >>")
        obj(13, "<< /Type /Page /Parent 14 0 R /MediaBox [0 0 200 200] >>")
        obj(14, "<< /Type /Pages /Kids [13 0 R 15 0 R] /Count 2 /Parent 15 0 R >>")
        obj(15, "<< /Type /Pages /Kids [14 0 R] /Count 2 /Parent 14 0 R >>")
        section(offsets.keys.toList(), offsets.size + 1, "", first = true)
        out.toByteArray()
    }

    private class Writer {
        val out = ByteArrayOutputStream()
        val offsets = sortedMapOf<Int, Int>()

        fun raw(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))

        fun header(version: String) = raw("%PDF-$version\n%âãÏÓ\n")

        fun obj(number: Int, body: String) {
            offsets[number] = out.size()
            raw("$number 0 obj\n$body\nendobj\n")
        }

        fun page() {
            obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")
        }

        /** A cross-reference section of [numbers], then its trailer; its offset. */
        fun section(numbers: List<Int>, size: Int, trailer: String, first: Boolean): Int {
            val start = out.size()
            raw("xref\n")
            if (first) raw("0 1\n0000000000 65535 f \n")
            numbers.forEach { raw("$it 1\n%010d 00000 n \n".format(offsets.getValue(it))) }
            raw("trailer\n<< /Size $size /Root 1 0 R $trailer >>\nstartxref\n$start\n%%EOF\n")
            return start
        }
    }

    private fun classic(trailer: String, encrypt: String? = null): ByteArray = Writer().run {
        header("1.7")
        page()
        encrypt?.let { obj(4, it) }
        section(offsets.keys.toList(), offsets.size + 1, trailer, first = true)
        out.toByteArray()
    }

    private fun incremental(): ByteArray = Writer().run {
        header("1.7")
        page()
        val first = section(listOf(1, 2, 3), 4, "", first = true)
        obj(4, "<< $PUB_SEC >>")
        section(listOf(4), 5, "/Encrypt 4 0 R /Prev $first", first = false)
        out.toByteArray()
    }

    private fun xrefStream(): ByteArray = Writer().run {
        header("1.7")
        page()
        obj(4, "<< $PUB_SEC >>")
        val start = out.size()
        offsets[5] = start
        // Rows of /W [1 4 2]: type, offset, generation — the free head, then objects 1 to 5.
        val rows = ByteArrayOutputStream()
        rows.write(byteArrayOf(0, 0, 0, 0, 0, -1, -1))
        for (number in 1..5) {
            val at = offsets.getValue(number)
            rows.write(byteArrayOf(1, (at ushr 24).toByte(), (at ushr 16).toByte(), (at ushr 8).toByte(), at.toByte(), 0, 0))
        }
        val data = rows.toByteArray()
        raw("5 0 obj\n<< /Type /XRef /Size 6 /W [1 4 2] /Root 1 0 R /Encrypt 4 0 R /Length ${data.size} >>\nstream\n")
        out.write(data)
        raw("\nendstream\nendobj\nstartxref\n$start\n%%EOF\n")
        out.toByteArray()
    }
}
