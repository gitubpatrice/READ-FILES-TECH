package com.readfilestech.read_files_tech.data.editor

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** A file as it was when an editor opened it: its size and its time, to see whether another app changed it since. */
data class FileStamp(val length: Long, val modifiedMs: Long) {
    companion object {
        fun of(file: File) = FileStamp(file.length(), file.lastModified())
    }
}

/** The file is not the one the editor opened any more: another app wrote it, moved it or deleted it. */
class FileChangedException : IOException("the file changed since it was opened")

/**
 * Saving a CSV of the editor over its file (`_save` of `CsvEditorScreen`, 2.15.3), off the main thread.
 *
 * 1. The file must still be the one opened ([FileStamp]): the editor writes its rows back from the bytes
 *    it read, and would otherwise put back what another app removed, and remove what it added. The
 *    2.15.3 wrote over whatever was there.
 * 2. The version about to be overwritten goes to the editor history (docs D-021), when it is at most
 *    [FileCaps.EDITOR_HISTORY_COPY]: a copy that fails stops the save, the file untouched. The 2.15.3's
 *    CSV editor kept no copy.
 * 3. The new content is written atomically next to the file ([AtomicFiles]), then put in its place: on
 *    any failure, the file is left as it was. The stamp is read again just before: a file written by
 *    another app while the new one was being written is not replaced (Claude review R20).
 */
object CsvSaving {
    private const val BUFFER = 64 * 1024

    /**
     * Writes over [file], opened as [opened], what [write] gives ([CsvEdits.writeTo]).
     *
     * @throws IOException why nothing was written — [FileChangedException] among others.
     */
    fun save(file: File, opened: FileStamp, history: EditorHistory?, write: (OutputStream) -> Unit) {
        checkUnchanged(file, opened)
        if (history != null && opened.length <= FileCaps.EDITOR_HISTORY_COPY) {
            val current = StorageFileSource(file).open(FileCaps.EDITOR_HISTORY_COPY).use { it.readBytes() }
            history.saveBefore(file.absolutePath, current)
        }
        val publish = { from: File, to: File ->
            checkUnchanged(to, opened)
            AtomicFiles.publish(from, to)
        }
        AtomicFiles.write(file, publish) { out ->
            val buffered = BufferedOutputStream(out, BUFFER)
            write(buffered)
            buffered.flush()
        }
    }

    private fun checkUnchanged(file: File, opened: FileStamp) {
        if (!file.isFile || FileStamp.of(file) != opened) throw FileChangedException()
    }
}
