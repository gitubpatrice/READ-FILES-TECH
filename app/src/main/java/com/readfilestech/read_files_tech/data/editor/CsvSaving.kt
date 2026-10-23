package com.readfilestech.read_files_tech.data.editor

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.tools.FileHashes
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A file as it was when an editor opened it — its size, its time and the SHA-256 of its content — to see
 * whether another app changed it since. Size and time tell most changes at no cost; a file rewritten with the
 * same size and the same date (a sync app keeping the date, a tool restoring it) only its content tells.
 */
data class FileStamp(val length: Long, val modifiedMs: Long, val sha256: String) {
    /** Whether [file] is still this one: its size and time first; when they match, its content, read again. */
    fun matches(file: File): Boolean =
        file.isFile && file.length() == length && file.lastModified() == modifiedMs && sha256Of(file) == sha256

    companion object {
        /** Size and time first, then the content: a change while it is read is a change. */
        fun of(file: File): FileStamp {
            val length = file.length()
            val modified = file.lastModified()
            return FileStamp(length, modified, sha256Of(file))
        }

        /**
         * Streamed by pieces, within the CSV editor's own cap ([FileCaps.CSV_FILE], what [CsvEdits.read] takes):
         * a file past it is refused ([FileTooLargeException]), as the editor refuses it.
         */
        private fun sha256Of(file: File): String {
            return BoundedInputStream(FileInputStream(file), FileCaps.CSV_FILE).use { FileHashes.sha256(it) }
        }
    }
}

/** The file is not the one the editor opened any more: another app wrote it, moved it or deleted it. */
class FileChangedException : IOException("the file changed since it was opened")

/**
 * Saving a CSV of the editor over its file (`_save` of `CsvEditorScreen`, 2.15.3), off the main thread.
 *
 * 1. The file must still be the one opened ([FileStamp], its content included): the editor writes its rows
 *    back from the bytes it read, and would otherwise put back what another app removed, and remove what it
 *    added. The 2.15.3 wrote over whatever was there.
 * 2. The version about to be overwritten goes to the editor history (docs D-021), when it is at most
 *    [FileCaps.EDITOR_HISTORY_COPY]: a copy that fails stops the save, the file untouched. The 2.15.3's
 *    CSV editor kept no copy. A larger one is not kept: the editor asks first, once per file it opens.
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
        if (history != null && keepsCopy(opened)) {
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

    /** Whether a save of the file [opened] keeps the version it replaces: at most [FileCaps.EDITOR_HISTORY_COPY]. */
    fun keepsCopy(opened: FileStamp): Boolean = opened.length <= FileCaps.EDITOR_HISTORY_COPY

    /**
     * What [write] gives — the editor's rows, not saved — into [file]'s history, before a version of the
     * history replaces them in the editor: restoring is undone by restoring them, as in the code editor.
     *
     * @throws FileTooLargeException over [FileCaps.EDITOR_HISTORY_COPY], the history's cap: kept nowhere.
     */
    fun keep(file: File, history: EditorHistory, write: (OutputStream) -> Unit) {
        val bytes = CappedBytes(FileCaps.EDITOR_HISTORY_COPY).also(write).toByteArray()
        history.saveBefore(file.absolutePath, bytes)
    }

    private fun checkUnchanged(file: File, opened: FileStamp) {
        if (!opened.matches(file)) throw FileChangedException()
    }

    /** Bytes in memory, up to [cap]: one more is refused before it is held. */
    private class CappedBytes(private val cap: Long) : ByteArrayOutputStream() {
        override fun write(b: Int) {
            room(1)
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            room(len)
            super.write(b, off, len)
        }

        private fun room(len: Int) {
            if (count + len.toLong() > cap) throw FileTooLargeException(cap)
        }
    }
}
