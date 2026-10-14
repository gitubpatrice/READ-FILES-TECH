package com.readfilestech.read_files_tech.data.editor

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.Charset

/**
 * The editor's text and its bytes: what was read is written back to the byte — encoding, byte order
 * mark, every line break — and a file that cannot be is not opened. The bytes are written here by hand
 * or by the JDK's charsets, never by [EditableText].
 */
class EditableTextTest {
    class Case(val label: String, val bytes: ByteArray) {
        override fun toString() = label
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    fun `a file saved without a change is the same file, to the byte`(case: Case) {
        val text = EditableText.of(case.bytes)
        assertThat(text.encode(text.text).bytes).isEqualTo(case.bytes)
    }

    @Test
    fun `the text shows every line break as a LF`() {
        assertThat(EditableText.of(bytes("a\r\nb\nc\rd")).text).isEqualTo("a\nb\nc\nd")
    }

    @Test
    fun `one line changed in a file of mixed line breaks changes that line only`() {
        val text = EditableText.of(bytes("one\r\ntwo\nthree\rfour\r\n"))
        assertThat(text.encode("one\nTWO, longer\nthree\nfour\n").bytes).isEqualTo(bytes("one\r\nTWO, longer\nthree\rfour\r\n"))
    }

    @Test
    fun `lines changed in place keep their own line breaks`() {
        val text = EditableText.of(bytes("a\nb\r\nc\rd\n"))
        assertThat(text.encode("A\nb\nC\nd\n").bytes).isEqualTo(bytes("A\nb\r\nC\rd\n"))
    }

    @Test
    fun `a line added takes the line break most of the file has`() {
        val text = EditableText.of(bytes("a\r\nb\nc\r\n"))
        assertThat(text.encode("a\nnew\nb\nc\n").bytes).isEqualTo(bytes("a\r\nnew\r\nb\nc\r\n"))
    }

    @Test
    fun `a line removed leaves the others' line breaks as they were`() {
        val text = EditableText.of(bytes("a\r\nb\nc\rd\r\n"))
        assertThat(text.encode("a\nc\nd\n").bytes).isEqualTo(bytes("a\r\nc\rd\r\n"))
    }

    @Test
    fun `a file without any line break gets a LF for a new one`() {
        assertThat(EditableText.of(bytes("one")).encode("one\ntwo").bytes).isEqualTo(bytes("one\ntwo"))
    }

    @Test
    fun `a CRLF pasted into the text is one line break, not a CR doubled`() {
        val text = EditableText.of(bytes("a\r\nb\r\n"))
        assertThat(text.encode("a\nb\r\nc\r\n").bytes).isEqualTo(bytes("a\r\nb\r\nc\r\n"))
    }

    @Test
    fun `a UTF-16 file stays UTF-16 with its byte order mark`() {
        val file = BOM_LE + "é\r\n".toByteArray(Charsets.UTF_16LE)
        val text = EditableText.of(file)
        assertThat(text.encoding).isEqualTo(TextEncoding.UTF_16LE)
        assertThat(text.encode("ü\n").bytes).isEqualTo(BOM_LE + "ü\r\n".toByteArray(Charsets.UTF_16LE))
    }

    @Test
    fun `a Windows-1252 file stays Windows-1252`() {
        val text = EditableText.of("café\r\n".toByteArray(CP1252))
        assertThat(text.encode("thé 5 €\n").bytes).isEqualTo("thé 5 €\r\n".toByteArray(CP1252))
    }

    @Test
    fun `a character the encoding has no bytes for refuses the save, never a question mark`() {
        val text = EditableText.of("café\r\n".toByteArray(CP1252))
        val error = assertThrows<UnencodableTextException> { text.encode("café $EMOJI\n") }
        assertThat(error.character).isEqualTo(EMOJI)
        assertThat(error.encoding).isEqualTo(TextEncoding.WINDOWS_1252)
    }

    @Test
    fun `a NUL is not text - a binary file under a text name`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52)
        val error = assertThrows<NotEditableException> { EditableText.of(png) }
        assertThat(error.reason).isEqualTo(NotEditableException.Reason.NOT_TEXT)
    }

    @Test
    fun `a UTF-16 text without its byte order mark is refused as not text`() {
        val error = assertThrows<NotEditableException> { EditableText.of("abc\n".toByteArray(Charsets.UTF_16LE)) }
        assertThat(error.reason).isEqualTo(NotEditableException.Reason.NOT_TEXT)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damaged")
    fun `bytes the encoding does not decode are refused, not changed at the first save`(case: Case) {
        val error = assertThrows<NotEditableException> { EditableText.of(case.bytes) }
        assertThat(error.reason).isEqualTo(NotEditableException.Reason.NOT_REVERSIBLE)
    }

    @Test
    fun `an empty file opens empty`() {
        val text = EditableText.of(ByteArray(0))
        assertThat(text.text).isEmpty()
        assertThat(text.encode("x").bytes).isEqualTo(bytes("x"))
    }

    companion object {
        private val CP1252: Charset = Charset.forName("windows-1252")
        private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val BOM_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val BOM_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        private val EMOJI = String(Character.toChars(0x1F600))

        private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

        @JvmStatic
        fun files() = listOf(
            Case("UTF-8, LF", bytes("Première\nDeuxième — €\n")),
            Case("UTF-8 with BOM, CRLF", BOM_UTF8 + bytes("# Titre\r\n\r\nFin\r\n")),
            Case("UTF-16 LE with BOM, CRLF", BOM_LE + "Ligne $EMOJI\r\nü\r\n".toByteArray(Charsets.UTF_16LE)),
            Case("UTF-16 BE with BOM, LF", BOM_BE + "{\n  \"a\": \"é\"\n}\n".toByteArray(Charsets.UTF_16BE)),
            Case("Windows-1252, CRLF", "Œuvre;12 €\r\nL’été\r\n".toByteArray(CP1252)),
            Case("Windows-1252, CR", "Première\rDeuxième\r".toByteArray(CP1252)),
            Case("no final line break", bytes("Un\r\nDeux\r\nTrois")),
            Case("mixed line breaks, CR before CRLF", bytes("a\nb\r\nc\rd\r\r\ne\n")),
            Case("a BOM in the text after the BOM", BOM_UTF8 + BOM_UTF8 + bytes("x\n")),
            Case("empty", ByteArray(0)),
        )

        @JvmStatic
        fun damaged() = listOf(
            // Valid UTF-8 for the viewer (a log cut while written), but its last character decodes as U+FFFD.
            Case("UTF-8 cut short", bytes("café été ") + byteArrayOf(0xE2.toByte(), 0x82.toByte())),
            Case("UTF-16 with an odd last byte", BOM_LE + "ab".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0x41)),
            Case("UTF-16 with a lone surrogate", BOM_LE + byteArrayOf(0x41, 0, 0x00, 0xD8.toByte(), 0x42, 0)),
        )
    }
}
