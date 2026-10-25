package com.readfilestech.read_files_tech.data.search

import com.readfilestech.read_files_tech.data.files.CappedInputStream
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.FileInputStream
import java.nio.file.Path

/**
 * The text of a file as the searches read it: [read] at most `cap` bytes — a file grown since its size was
 * read is read up to it, never whole (GPT review R4 of the global search) — decoded as the text viewer
 * decodes it ([TextEncoding.detect]: a BOM, else UTF-8 when the bytes are valid UTF-8, else Windows-1252).
 * The 2.15.3 decoded strict UTF-8: a text saved in Latin-1 threw, and its words were never found
 * (inventory D35); replacing the bad bytes, as this port first did, still lost every accented word.
 */
internal object TextContent {
    fun read(path: Path, cap: Long): String {
        // FileInputStream, not Files.newInputStream: over a channel, a read goes through a temporary direct
        // buffer its thread keeps (StorageFileSource).
        val bytes = CappedInputStream(FileInputStream(path.toFile()), cap).use { it.readBytes() }
        val encoding = TextEncoding.detect(bytes, bytes.size)
        val start = minOf(encoding.bomLength, bytes.size)
        return String(bytes, start, bytes.size - start, encoding.charset)
    }
}
