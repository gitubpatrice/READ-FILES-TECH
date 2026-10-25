package com.readfilestech.read_files_tech.data.convert

import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import java.io.OutputStream

/**
 * DOCX → TXT (`_docxToText` of the 2.15.3): the text the document viewer shows (its reader,
 * `data/viewer/document/`, never a second extractor) — with the fixes of inventory F48 the 2.15.3's
 * `extractDocxText` lacked — written as it is, in UTF-8.
 */
object DocumentToText {
    /** [text] on [out] (not closed), a few rows at a time: 64 KB at most in one piece. */
    fun write(text: TextDocument, out: OutputStream) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        var from = 0
        while (from < text.rowCount) {
            val to = minOf(from + TextDocument.ANCHOR_EVERY, text.rowCount)
            writer.write(text.text(from, to))
            from = to
        }
        writer.flush()
    }
}
