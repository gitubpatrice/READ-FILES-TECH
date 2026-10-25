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
    /**
     * The face a text is drawn in: the platform's proportional one at 11 pt for Convert (the 2.15.3's Helvetica
     * 11), its monospace one at 10 pt for the text tools (their Courier 10, `txt_tools_screen.dart:81`) — a
     * text file's columns stay aligned. Either way the platform's fallbacks draw what the face has no glyph for.
     */
    enum class Face(val size: Float) {
        PROSE(11f),
        MONOSPACE(10f),
        ;

        val typeface: Typeface get() = if (this == MONOSPACE) Typeface.MONOSPACE else Typeface.DEFAULT
    }

    /**
     * [text] as a PDF on [out] (not closed), in [face]. [onRow] gets the rows laid out so far after each line
     * of the file; what it throws (a cancellation) stops the work.
     *
     * @throws TooManyItemsException more than [FileCaps.TEXT_TO_PDF_PAGES] pages.
     */
    fun write(text: TextDocument, out: OutputStream, face: Face = Face.PROSE, onRow: (Int) -> Unit = {}) {
        val document = PdfDocument()
        val pages = TextPages(document, face)
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
private class TextPages(document: PdfDocument, face: TextPdf.Face) {
    private val pages = PdfPages(document, FileCaps.TEXT_TO_PDF_PAGES)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = face.size
        typeface = face.typeface
        color = Color.BLACK
        fontFeatureSettings = PdfPages.NO_LIGATURES
    }
    private val width = (PdfPages.WIDTH - 2 * ImagePlacement.MARGIN).toInt()
    private val bottom = PdfPages.HEIGHT - ImagePlacement.MARGIN
    private var y = 0f

    /** [paragraph] wrapped at the margin, its lines on this page while they fit, then on the next ones. */
    fun place(paragraph: CharSequence) {
        val layout = layout(paragraph, 0, paragraph.length)
        var first = 0
        while (first < layout.lineCount) {
            val canvas = (pages.current ?: newPage()).canvas
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
    fun finish() = pages.finish()

    /** The page left open by a stop or a failure, finished: the document can then be closed. */
    fun release() = pages.release()

    private fun newPage(): PdfDocument.Page = pages.next().also { y = ImagePlacement.MARGIN }

    /** Greedy breaks, no hyphen added: a part laid out again breaks where the whole did, and its text is the file's. */
    // WrongConstant: lint asks for LineBreaker's constants, of API 29; Layout's are the same values, from API 23.
    @SuppressLint("WrongConstant")
    private fun layout(text: CharSequence, start: Int, end: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, start, end, paint, width)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
}
