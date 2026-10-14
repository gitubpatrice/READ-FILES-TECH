package com.readfilestech.read_files_tech.data.editor

import com.readfilestech.read_files_tech.data.browse.TextPreview
import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import java.io.File

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
        val encoded = content.encode(edited)
        val current = if (file.isFile) readBytes(file) else null
        if (!overwrite && (current == null || !current.contentEquals(bytes))) return SaveOutcome.ChangedOnDisk
        current?.let { history.saveBefore(file.absolutePath, it) }
        AtomicFiles.writeBytes(file, encoded.bytes)
        return SaveOutcome.Saved(EditedFile(file, encoded.bytes, encoded.text))
    }

    /**
     * [edited], the editor's text not saved, into the file's history: before a saved version replaces it
     * in the editor, so that restoring is undone by restoring it.
     *
     * @throws UnencodableTextException a character the file's encoding has no bytes for
     */
    fun keep(edited: String, history: EditorHistory) = history.saveBefore(file.absolutePath, content.encode(edited).bytes)

    companion object {
        /**
         * The files the editor is offered for — the explorer's "Edit", the picker's, its own picker's filter:
         * `editableExts` of the 2.15.3 (`file_viewer_router.dart:23-40`), the same sixteen as the preview's.
         * Its picker offered ten of them (`code_editor_screen.dart:50-61`), and its recents' "Modify" any
         * file at all (F34). What opens is decided by the content ([EditableText.of]), never by the name.
         */
        val EXTENSIONS: Set<String> = TextPreview.EXTENSIONS

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
