package com.readfilestech.read_files_tech.data.convert

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import java.io.OutputStream

/**
 * Text → PDF (`_generateTextPdf` of the 2.15.3, convert_screen.dart:177-199) by Android's `PdfDocument`
 * (docs D-005), in the main process: it draws a text, it parses no PDF. The platform's font and its
 * fallbacks draw and embed what the phone shows — accents, œ, €, Greek, Cyrillic, CJK, emoji — where the
 * 2.15.3's Helvetica in WinAnsi lost all but Latin-1 (inventory F6). A4, half an inch of margin
 * ([ImagePlacement.MARGIN]), 11 pt as the 2.15.3; each line of the file a paragraph, wrapped at the margin,
 * going on to the next page line by line. At most [FileCaps.TEXT_TO_PDF_PAGES] pages: the 2.15.3 had no bound.
 */
object TextPdf {
    const val FONT_SIZE = 11f

    /**
     * [text] as a PDF on [out] (not closed). [onRow] gets the rows laid out so far after each line of the
     * file; what it throws (a cancellation) stops the work.
     *
     * @throws TooManyItemsException more than [FileCaps.TEXT_TO_PDF_PAGES] pages.
     */
    fun write(text: TextDocument, out: OutputStream, onRow: (Int) -> Unit = {}) {
        val document = PdfDocument()
        val pages = TextPages(document)
        try {
            var row = 0
            while (row < text.rowCount) {
                // A line of the file, its rows joined: the viewer cuts a long line into rows of 4 KB.
                val line = StringBuilder(text.row(row))
                while (text.continues(row)) line.append(text.row(++row))
                row++
                pages.place(line)
                onRow(row)
            }
            pages.finish()
            document.writeTo(out)
        } finally {
            // PdfDocument.close() throws on a page left open: a stop or a failure in mid-page would have been
            // replaced by that IllegalStateException — the user's stop said as an error (Claude review R18).
            pages.release()
            document.close()
        }
    }
}

/** The pages of a [TextPdf] being drawn, and where the next line goes. */
private class TextPages(private val document: PdfDocument) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TextPdf.FONT_SIZE
        typeface = Typeface.DEFAULT
        color = Color.BLACK
        // No ligatures: drawn as one glyph, "ffi" came back out of the PDF as U+FB03 ("ﬃcially", seen by
        // pdftotext and pypdf on a phone's output), so the PDF's text could not be searched or copied as written.
        fontFeatureSettings = "'liga' 0, 'clig' 0"
    }
    private val width = (PAGE_WIDTH - 2 * ImagePlacement.MARGIN).toInt()
    private val bottom = PAGE_HEIGHT - ImagePlacement.MARGIN
    private var page: PdfDocument.Page? = null
    private var count = 0
    private var y = 0f

    /** [paragraph] wrapped at the margin, its lines on this page while they fit, then on the next ones. */
    fun place(paragraph: CharSequence) {
        val layout = layout(paragraph, 0, paragraph.length)
        var first = 0
        while (first < layout.lineCount) {
            val canvas = (page ?: newPage()).canvas
            val empty = y == ImagePlacement.MARGIN
            var last = first
            while (last < layout.lineCount && y + layout.getLineBottom(last) - layout.getLineTop(first) <= bottom) last++
            // Not one line fits: on a fresh page, a line taller than a page goes alone; else, the next page.
            if (last == first && empty) last++
            if (last > first) {
                // The lines of this page, laid out again on their own: the same breaks (greedy), and only
                // their text in the page — a line clipped out would still be in the PDF's text.
                val part = if (first == 0 && last == layout.lineCount) {
                    layout
                } else {
                    layout(paragraph, layout.getLineStart(first), layout.getLineEnd(last - 1))
                }
                canvas.withTranslation(ImagePlacement.MARGIN, y) { part.draw(this) }
                y += layout.getLineBottom(last - 1) - layout.getLineTop(first)
                first = last
            }
            if (first < layout.lineCount) newPage()
        }
    }

    /** The last page finished — a blank one for an empty text, as the 2.15.3 made. */
    fun finish() {
        document.finishPage(page ?: newPage())
        page = null
    }

    /** The page left open by a stop or a failure, finished: the document can then be closed. */
    fun release() {
        page?.let(document::finishPage)
        page = null
    }

    private fun newPage(): PdfDocument.Page {
        release()
        if (count == FileCaps.TEXT_TO_PDF_PAGES) throw TooManyItemsException(FileCaps.TEXT_TO_PDF_PAGES)
        count++
        y = ImagePlacement.MARGIN
        return document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, count).create()).also { page = it }
    }

    /** Greedy breaks, no hyphen added: a part laid out again breaks where the whole did, and its text is the file's. */
    // WrongConstant: lint asks for LineBreaker's constants, of API 29; Layout's are the same values, from API 23.
    @SuppressLint("WrongConstant")
    private fun layout(text: CharSequence, start: Int, end: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, start, end, paint, width)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    private companion object {
        /** A4 in points, whole: what `PdfDocument.PageInfo` takes. */
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
    }
}
