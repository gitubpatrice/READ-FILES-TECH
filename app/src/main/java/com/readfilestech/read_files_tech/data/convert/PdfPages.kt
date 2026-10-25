package com.readfilestech.read_files_tech.data.convert

import android.graphics.pdf.PdfDocument
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException

/**
 * The pages of a document Android's `PdfDocument` draws — Text → PDF's ([TextPdf]) and the CSV tools' table
 * ([CsvTablePdf]): A4, at most [maxPages], a page started only once the last one is finished.
 *
 * [release] finishes a page left open by a stop or a failure: `PdfDocument.close()` throws on one, and that
 * IllegalStateException would have replaced the user's stop, said as an error (Claude review R18).
 */
internal class PdfPages(private val document: PdfDocument, private val maxPages: Int) {
    /** The page being drawn; null before the first one and once it is finished. */
    var current: PdfDocument.Page? = null
        private set

    private var count = 0

    /**
     * A new page, the one open finished first.
     *
     * @throws TooManyItemsException past [maxPages]
     */
    fun next(): PdfDocument.Page {
        release()
        if (count == maxPages) throw TooManyItemsException(maxPages)
        count++
        return document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, count).create()).also { current = it }
    }

    /** The last page finished — a blank one when none was drawn. */
    fun finish() {
        document.finishPage(current ?: next())
        current = null
    }

    /** The page left open by a stop or a failure, finished: the document can then be closed. */
    fun release() {
        current?.let(document::finishPage)
        current = null
    }

    companion object {
        /** A4 in points, whole: what `PdfDocument.PageInfo` takes. */
        const val WIDTH = 595
        const val HEIGHT = 842

        /**
         * A paint's features with no ligatures: drawn as one glyph, "ffi" came back out of the PDF as U+FB03
         * ("ﬃcially", seen by pdftotext and pypdf on a phone's output), so the PDF's text could not be searched
         * or copied as written.
         */
        const val NO_LIGATURES = "'liga' 0, 'clig' 0"
    }
}
