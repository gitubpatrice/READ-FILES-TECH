package com.readfilestech.read_files_tech.data.editor

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileBytes
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import java.io.File
import java.io.IOException

/** The editor's text would be more bytes than [capBytes], what the editor opens: nothing is written. */
class TextTooLargeException(val capBytes: Long) : IOException("text over $capBytes bytes")

/** How a save of the code editor ended, when it did not fail. */
sealed interface SaveOutcome {
    /** Written: [file] is the file as it is now, what the next save compares with. */
    data class Saved(val file: EditedFile) : SaveOutcome

    /** The file is not what the editor read any more — another app wrote, moved or deleted it: nothing written. */
    data object ChangedOnDisk : SaveOutcome
}

/**
 * The file the code editor has open (`CodeEditorScreen` of the 2.15.3): its bytes as read, within
 * [FileCaps.CODE_EDITOR], and its [content]. Immutable: a save gives the file as saved.
 */
class EditedFile private constructor(
    val file: File,
    /** The file's bytes when it was opened, or last saved. */
    private val bytes: ByteArray,
    val content: EditableText,
) {
    val text: String get() = content.text

    /** Whether [current], the editor's text, is not what the file holds. */
    fun isModified(current: CharSequence): Boolean = !current.contentEquals(content.text)

    /**
     * Writes [edited] over the file (`_save` of the 2.15.3):
     * 1. encoded as the file was ([EditableText.encode]) — a character its encoding has no bytes for stops
     *    the save, the file untouched;
     * 2. unless [overwrite], the file must still hold the bytes the editor read, to the byte — otherwise
     *    [SaveOutcome.ChangedOnDisk], and the user is asked. The 2.15.3 wrote over whatever was there;
     * 3. the version about to be replaced goes to the history first (docs D-021, per full path: D6) — a copy
     *    that fails stops the save; a file grown past [FileCaps.CODE_EDITOR] since, whose version could not
     *    be kept, is not overwritten;
     * 4. the new bytes are written atomically ([AtomicFiles]): on any failure, the file is left as it was.
     *
     * @throws java.io.IOException why nothing was written
     */
    suspend fun save(edited: String, history: EditorHistory, overwrite: Boolean = false): SaveOutcome {
        val encoded = encodeWithin(edited)
        val current = if (file.isFile) readBytes(file) else null
        if (!overwrite && (current == null || !current.contentEquals(bytes))) return SaveOutcome.ChangedOnDisk
        current?.let { history.saveBefore(file.absolutePath, it) }
        // Checked again just before the new file takes its place: a sync app writing it while the version
        // was kept and the new bytes synced had its own replaced, kept nowhere (Claude review R22).
        val publish = { from: File, to: File ->
            if (!holdsStill(to, current)) throw ChangedMeanwhile()
            AtomicFiles.publish(from, to)
        }
        return try {
            AtomicFiles.write(file, publish) { it.write(encoded.bytes) }
            SaveOutcome.Saved(EditedFile(file, encoded.bytes, encoded.text))
        } catch (_: ChangedMeanwhile) {
            SaveOutcome.ChangedOnDisk
        }
    }

    /**
     * [edited], the editor's text not saved, into the file's history: before a saved version replaces it
     * in the editor, so that restoring is undone by restoring it.
     *
     * @throws UnencodableTextException a character the file's encoding has no bytes for
     * @throws TextTooLargeException over [FileCaps.CODE_EDITOR]: a version the editor could not open again
     */
    suspend fun keep(edited: String, history: EditorHistory) = history.saveBefore(file.absolutePath, encodeWithin(edited).bytes)

    /**
     * [edited] as the file's bytes, within [FileCaps.CODE_EDITOR]: past it, a save went through but the file
     * opened no more, nor could be saved again — its every save read it first (Claude review R22).
     */
    private suspend fun encodeWithin(edited: String): EditableText.Encoded {
        val encoded = content.encode(edited)
        if (encoded.bytes.size > FileCaps.CODE_EDITOR) throw TextTooLargeException(FileCaps.CODE_EDITOR)
        return encoded
    }

    /** The file still holds [current], the bytes read before the save — or is still missing, when it was. */
    private fun holdsStill(file: File, current: ByteArray?): Boolean =
        if (current == null) !file.exists() else FileBytes.holds(file, current)

    /** The file changed between the check and the moment the new one took its place. */
    private class ChangedMeanwhile : IOException("the file changed during the save")

    companion object {
        /**
         * Reads [file] for the editor.
         *
         * @throws com.readfilestech.read_files_tech.data.files.FileTooLargeException over [FileCaps.CODE_EDITOR]
         * @throws NotEditableException not text, or not written back as it was read
         */
        suspend fun open(file: File): EditedFile {
            val bytes = readBytes(file)
            return EditedFile(file, bytes, EditableText.of(bytes))
        }

        /** A saved version's text, read by the editor's rules — the version's own encoding found. */
        suspend fun textOf(copy: File): String = EditableText.of(readBytes(copy)).text

        private suspend fun readBytes(file: File): ByteArray {
            val source = StorageFileSource(file)
            val cap = FileCaps.CODE_EDITOR
            val (buffer, size) = source.open(cap).use { TextDocument.readAll(it, source.length(), cap) }
            return buffer.copyOf(size)
        }
    }
}
