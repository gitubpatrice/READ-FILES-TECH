package com.readfilestech.read_files_tech.data.editor

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.history.EditorHistory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest

/** Opening and saving a file in the code editor: its bytes, its history, what another app did meanwhile. */
class EditedFileTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var history: EditorHistory

    @BeforeEach
    fun setUp() {
        history = EditorHistory(File(tmp, "app_flutter/history"), File(tmp, "files/editor_history"))
    }

    private fun file(name: String, bytes: ByteArray) = File(tmp, name).apply {
        parentFile?.mkdirs()
        writeBytes(bytes)
    }

    private fun open(file: File) = runBlocking { EditedFile.open(file) }

    private fun EditedFile.saved(text: String, overwrite: Boolean = false): SaveOutcome = runBlocking {
        save(text, history, overwrite)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    @Test
    fun `saved without a change, the file is the same to the byte, its version before in the history`() {
        val before = "Œuvre;12 €\r\nL’été\r\n".toByteArray(CP1252)
        val target = file("prix.csv", before)
        val opened = open(target)
        val outcome = opened.saved(opened.text)
        assertThat(outcome).isInstanceOf(SaveOutcome.Saved::class.java)
        assertThat(sha256(target.readBytes())).isEqualTo(sha256(before))
        assertThat(history.versions(target.absolutePath).map { sha256(it.file.readBytes()) }).containsExactly(sha256(before))
    }

    @Test
    fun `the history copy is the file before the save, the file the new text`() {
        val target = file("notes.txt", "one\ntwo\n".toByteArray())
        val saved = open(target).saved("one\nTWO\n") as SaveOutcome.Saved
        assertThat(target.readText()).isEqualTo("one\nTWO\n")
        assertThat(saved.file.isModified("one\nTWO\n")).isFalse()
        assertThat(history.versions(target.absolutePath).single().file.readText()).isEqualTo("one\ntwo\n")
    }

    @Test
    fun `two files of the same name in two folders keep their own history - D6`() {
        val first = file("a/notes.txt", "first\n".toByteArray())
        val second = file("b/notes.txt", "second\n".toByteArray())
        open(first).saved("first, changed\n")
        open(second).saved("second, changed\n")
        assertThat(history.versions(first.absolutePath).map { it.file.readText() }).containsExactly("first\n")
        assertThat(history.versions(second.absolutePath).map { it.file.readText() }).containsExactly("second\n")
    }

    @Test
    fun `a file another app changed since it was read is not overwritten without asking`() {
        val target = file("notes.txt", "mine\n".toByteArray())
        val opened = open(target)
        target.writeText("theirs\n")
        assertThat(opened.saved("mine, changed\n")).isEqualTo(SaveOutcome.ChangedOnDisk)
        assertThat(target.readText()).isEqualTo("theirs\n")
        assertThat(history.versions(target.absolutePath)).isEmpty()
        // Overwrite, once asked: their version is kept first.
        assertThat(opened.saved("mine, changed\n", overwrite = true)).isInstanceOf(SaveOutcome.Saved::class.java)
        assertThat(target.readText()).isEqualTo("mine, changed\n")
        assertThat(history.versions(target.absolutePath).single().file.readText()).isEqualTo("theirs\n")
    }

    @Test
    fun `a file deleted since it was read is said changed, and written again on overwrite`() {
        val target = file("notes.txt", "mine\n".toByteArray())
        val opened = open(target)
        target.delete()
        assertThat(opened.saved("mine\n")).isEqualTo(SaveOutcome.ChangedOnDisk)
        assertThat(target.exists()).isFalse()
        opened.saved("mine\n", overwrite = true)
        assertThat(target.readText()).isEqualTo("mine\n")
    }

    @Test
    fun `a file grown past the cap since it was read is not overwritten - its version could not be kept`() {
        val target = file("log.txt", "short\n".toByteArray())
        val opened = open(target)
        target.writeBytes(ByteArray((FileCaps.CODE_EDITOR + 1).toInt()) { 'a'.code.toByte() })
        assertThrows<FileTooLargeException> { opened.saved("short, changed\n", overwrite = true) }
        assertThat(target.length()).isEqualTo(FileCaps.CODE_EDITOR + 1)
    }

    @Test
    fun `a history copy that fails stops the save, the file untouched`() {
        val blocked = EditorHistory(File(tmp, "legacy"), File(tmp, "not-a-folder").apply { writeText("x") })
        val target = file("notes.txt", "one\n".toByteArray())
        val opened = open(target)
        assertThrows<IOException> { runBlocking { opened.save("two\n", blocked) } }
        assertThat(target.readText()).isEqualTo("one\n")
    }

    @Test
    fun `a character the file's encoding has no bytes for stops the save, the file untouched`() {
        val before = "café\r\n".toByteArray(CP1252)
        val target = file("notes.txt", before)
        assertThrows<UnencodableTextException> { open(target).saved("café ${String(Character.toChars(0x1F600))}\n") }
        assertThat(target.readBytes()).isEqualTo(before)
        assertThat(history.versions(target.absolutePath)).isEmpty()
    }

    @Test
    fun `the unsaved text kept before a restore is in the file's encoding`() {
        val target = file("notes.txt", "café\r\n".toByteArray(CP1252))
        open(target).keep("thé\n", history)
        assertThat(history.versions(target.absolutePath).single().file.readBytes()).isEqualTo("thé\r\n".toByteArray(CP1252))
    }

    // Hostile files: each refused in words, or opened as what it is.

    @Test
    fun `a file over the cap is refused before it is read`() {
        val target = file("big.log", ByteArray((FileCaps.CODE_EDITOR + 1).toInt()) { 'a'.code.toByte() })
        assertThat(assertThrows<FileTooLargeException> { open(target) }.capBytes).isEqualTo(FileCaps.CODE_EDITOR)
    }

    @Test
    fun `a file at the cap opens`() {
        val target = file("big.log", ByteArray(FileCaps.CODE_EDITOR.toInt()) { 'a'.code.toByte() })
        assertThat(open(target).text.length.toLong()).isEqualTo(FileCaps.CODE_EDITOR)
    }

    @Test
    fun `an empty file opens empty, and saves`() {
        val target = file("empty.txt", ByteArray(0))
        open(target).saved("now some text\n")
        assertThat(target.readText()).isEqualTo("now some text\n")
    }

    @Test
    fun `a binary file under a text name is refused as not text`() {
        val target = file("photo.txt", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46))
        assertThat(assertThrows<NotEditableException> { open(target) }.reason).isEqualTo(NotEditableException.Reason.NOT_TEXT)
    }

    @Test
    fun `a UTF-16 file cut in the middle of a character is refused, not changed`() {
        val target = file("cut.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0, 0x42))
        val error = assertThrows<NotEditableException> { open(target) }
        assertThat(error.reason).isEqualTo(NotEditableException.Reason.NOT_REVERSIBLE)
    }

    @Test
    fun `a file that is not there is refused`() {
        assertThrows<IOException> { open(File(tmp, "gone.txt")) }
    }

    private companion object {
        val CP1252: Charset = Charset.forName("windows-1252")
    }
}
