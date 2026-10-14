package com.readfilestech.read_files_tech.data.convert

import com.readfilestech.read_files_tech.data.viewer.pdf.OpenedPdf
import com.tom_roush.pdfbox.text.PDFTextStripper

/** A piece of a page's text and whether more of it follows. */
class TextChunk(val text: String, val more: Boolean)

/**
 * PDF → TXT in the `:pdf` process (docs D-005, D-029): the text of a document PdfBox opened, ONE page at a
 * time, handed out in pieces a Binder transaction carries. The prototype measured ~165 ms a page (204 pages
 * in 31 s on the S9): page by page, the main process shows progress and can stop between two pages. The
 * 2.15.3 extracted the whole file in an isolate nobody could stop (`compute(extractPdfText)`).
 *
 * One page's text is kept, the one being handed out; its trailing white space is trimmed, as the 2.15.3's
 * `trimRight()` (text_extraction_service.dart:392). Lines end in `\n` whatever the platform.
 */
class PdfPageTexts(private val opened: OpenedPdf) : AutoCloseable {
    val pageCount: Int = opened.document.numberOfPages
    private val stripper = PDFTextStripper().apply { lineSeparator = "\n" }
    private var page = -1
    private var text = ""

    /** At most [max] characters of page [index]'s text from [from] on (0 is the first page). */
    @Synchronized
    fun chunk(index: Int, from: Int, max: Int): TextChunk {
        require(index in 0 until pageCount) { "page $index of $pageCount" }
        if (index != page) {
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            text = stripper.getText(opened.document).trimEnd()
            page = index
        }
        return cut(text, from, max)
    }

    @Synchronized
    override fun close() = opened.close()

    companion object {
        /** [text] from [from], at most [max] characters, never ending on the first half of a surrogate pair. */
        fun cut(text: String, from: Int, max: Int): TextChunk {
            require(from >= 0 && max >= 2) { "from $from, at most $max" }
            if (from >= text.length) return TextChunk("", more = false)
            var end = minOf(text.length, from + max)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            return TextChunk(text.substring(from, end), more = end < text.length)
        }
    }
}
