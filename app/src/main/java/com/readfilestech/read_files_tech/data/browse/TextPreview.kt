package com.readfilestech.read_files_tech.data.browse

import com.readfilestech.read_files_tech.data.files.CappedInputStream
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.File
import java.io.FileInputStream

/**
 * The explorer's "Preview": the first lines of a text file (`file_preview_sheet.dart` of the 2.15.x), offered
 * for the [com.readfilestech.read_files_tech.data.files.TextFiles].
 */
object TextPreview {
    const val MAX_LINES = 40

    /** A binary file without a line break would otherwise be read whole as "one line". */
    const val MAX_BYTES = 512L * 1024

    /**
     * At most [MAX_LINES] lines from at most [MAX_BYTES] bytes, in the encoding the text viewer finds
     * ([TextEncoding.detect]), so that a preview never shows "Quantit�" where its viewer shows "Quantité"
     * (Excel's French CSV is Windows-1252). The cost depends on what is shown, never on the file — the
     * 2.15.x once read a 2 GB log whole for 40 lines (V-H3).
     */
    fun firstLines(file: File): List<String> {
        val bytes = CappedInputStream(FileInputStream(file), MAX_BYTES).use { it.readBytes() }
        val encoding = TextEncoding.detect(bytes, bytes.size)
        val text = String(bytes, encoding.bomLength, bytes.size - encoding.bomLength, encoding.charset)
        return text.reader().buffered().use { reader -> generateSequence { reader.readLine() }.take(MAX_LINES).toList() }
    }
}
