package com.readfilestech.read_files_tech.data.browse

import com.readfilestech.read_files_tech.data.files.CappedInputStream
import java.io.File
import java.nio.file.Files

/** The explorer's "Preview": the first lines of a text file (`file_preview_sheet.dart` of the 2.15.x). */
object TextPreview {
    const val MAX_LINES = 40

    /** A binary file without a line break would otherwise be read whole as "one line". */
    const val MAX_BYTES = 512L * 1024

    /** `previewExts` of the 2.15.x. */
    val EXTENSIONS =
        setOf("txt", "md", "json", "xml", "html", "htm", "css", "js", "php", "dart", "csv", "yaml", "yml", "ini", "conf", "log")

    /**
     * At most [MAX_LINES] lines from at most [MAX_BYTES] bytes, malformed UTF-8 replaced: the cost
     * depends on what is shown, never on the file — the 2.15.x once read a 2 GB log whole for 40 lines
     * (V-H3).
     */
    fun firstLines(file: File): List<String> =
        CappedInputStream(Files.newInputStream(file.toPath()), MAX_BYTES).bufferedReader(Charsets.UTF_8).use { reader ->
            generateSequence { reader.readLine() }.take(MAX_LINES).toList()
        }
}
