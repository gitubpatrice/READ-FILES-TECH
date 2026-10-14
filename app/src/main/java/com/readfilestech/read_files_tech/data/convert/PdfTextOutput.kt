package com.readfilestech.read_files_tech.data.convert

import java.io.Writer
import java.text.Normalizer

/**
 * The text file of PDF → TXT, written page after page as the `:pdf` process extracts them — never the
 * whole text in memory. The layout is the 2.15.3's (`extractPdfText`, text_extraction_service.dart:378-396):
 * `--- Page N ---` on a line of its own, then the page's text, its trailing white space trimmed, a line
 * break between two pages, and nothing after the last one.
 *
 * Its "scanned PDF" rule is the 2.15.3's too (`:398-409`): fewer than [SCANNED_THRESHOLD] characters
 * other than white space in all the pages' texts, and there is no text to give — [hasText] is false and
 * the caller writes nothing. The 2.15.3 counted after removing its own markers; here the markers are
 * never counted.
 */
class PdfTextOutput(private val out: Writer) {
    private var pages = 0
    private var useful = 0L

    /** The line break after the last marker, written once its page turns out to have text. */
    private var pendingBreak = false

    /** Enough characters for a text file. */
    val hasText: Boolean get() = useful >= SCANNED_THRESHOLD

    /** Page [number] (1 for the first) begins: its marker. */
    fun startPage(number: Int) {
        if (pages > 0) {
            if (pendingBreak) out.write("\n")
            out.write("\n")
        }
        out.write("--- Page $number ---")
        pendingBreak = true
        pages++
    }

    /** The next piece of the current page's text, as the `:pdf` process extracted it. */
    fun text(chunk: String) {
        if (chunk.isEmpty()) return
        if (pendingBreak) {
            out.write("\n")
            pendingBreak = false
        }
        val text = normalizeExtracted(chunk)
        useful += text.count { !it.isWhitespace() }
        out.write(text)
    }

    companion object {
        /** `usefulChars < 30` of the 2.15.3. */
        const val SCANNED_THRESHOLD = 30

        private const val RADICALS_FIRST = 0x2E80
        private const val RADICALS_LAST = 0x2FDF

        /**
         * The CJK radicals of [text] (the Radicals Supplement and Kangxi Radicals blocks) as the ideographs
         * they stand for, by NFKC, and nothing else changed. Measured by the prototype (RAPPORT-PDF): the
         * `/ToUnicode` of a PDF printed by Edge in Yu Gothic maps the glyph of 日 to the radical U+2F47 —
         * the same drawing, and a search for 日 finds nothing. PdfBox gives what the file says, pdftotext
         * normalises; NFKC on the whole text would be wrong (`nº` becomes `no`, `²` becomes `2`).
         */
        fun normalizeExtracted(text: String): String {
            if (text.none(::isRadical)) return text
            val normalized = StringBuilder(text.length)
            for (c in text) {
                if (isRadical(c)) normalized.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC)) else normalized.append(c)
            }
            return normalized.toString()
        }

        private fun isRadical(c: Char): Boolean = c.code in RADICALS_FIRST..RADICALS_LAST
    }
}
