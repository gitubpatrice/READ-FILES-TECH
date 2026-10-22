package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.editor.NotEditableException
import com.readfilestech.read_files_tech.data.editor.UnencodableTextException
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** The text tools' statistics, search and "Replace all", on every encoding the viewer reads, and on hostile files. */
class TextBytesTest {
    @TempDir
    lateinit var tmp: File

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun of(text: String, charset: java.nio.charset.Charset = Charsets.UTF_8, bom: ByteArray = ByteArray(0)) =
        TextBytes.of(bom + text.toByteArray(charset))

    private fun replaced(bytes: TextBytes, query: String, replacement: String, cap: Long = Long.MAX_VALUE): Pair<Long, ByteArray> {
        val out = ByteArrayOutputStream()
        val count = bytes.replaceAll(query, replacement, out, cap)
        return count to out.toByteArray()
    }

    @Test
    fun `characters are code points, words split at Python's white space, lines as the 2_15_3 counted them`() {
        val stats = of("Café 😀 naïve\r\nsecond\u00A0line\u2028third\u001Cfourth\u0085fifth\u200Bstill\uFEFFone\n").stats()
        assertThat(stats.characters).isEqualTo(55)
        // No-break space, U+2028, U+001C and U+0085 split; U+200B and U+FEFF do not.
        assertThat(stats.words).isEqualTo(8)
        assertThat(stats.lines).isEqualTo(3)
    }

    @Test
    fun `an empty text counts nothing, a text without a line feed one line`() {
        assertThat(of("").stats()).isEqualTo(TextStats(0, 0, 0))
        assertThat(of("", bom = utf8Bom).stats()).isEqualTo(TextStats(0, 0, 0))
        assertThat(of("  \t ").stats()).isEqualTo(TextStats(4, 0, 1))
        assertThat(of("one").stats()).isEqualTo(TextStats(3, 1, 1))
    }

    @Test
    fun `the byte order mark is not a character, and every encoding the viewer reads is counted the same`() {
        val text = "Zoë écrit 😀\r\nfin"
        val expected = TextStats(16, 4, 2)
        assertThat(of(text).stats()).isEqualTo(expected)
        assertThat(of(text, bom = utf8Bom).stats()).isEqualTo(expected)
        assertThat(of(text, Charsets.UTF_16LE, utf16LeBom).stats()).isEqualTo(expected)
        assertThat(of(text, Charsets.UTF_16BE, utf16BeBom).stats()).isEqualTo(expected)
        assertThat(of("Zoë écrit\r\nfin", TextEncoding.WINDOWS_1252.charset).stats()).isEqualTo(TextStats(14, 3, 2))
    }

    @Test
    fun `a word and a surrogate pair across two pieces are counted once`() {
        val head = "a".repeat(TextBytes.PIECE_CHARS - 1)
        // The emoji's two halves fall on both sides of the first piece's end, in UTF-16 where nothing re-aligns them.
        val text = head + "😀b c"
        val stats = of(text, Charsets.UTF_16LE, utf16LeBom).stats()
        assertThat(stats.characters).isEqualTo(text.codePointCount(0, text.length).toLong())
        assertThat(stats.words).isEqualTo(2)
        assertThat(of(text).stats().words).isEqualTo(2)
    }

    @Test
    fun `a high surrogate at the end of a piece waits for the next one, unless it is the last`() {
        val high = "\uD83D"
        assertThat(TextBytes.heldBack("ab$high", last = false)).isEqualTo("ab" to high)
        assertThat(TextBytes.heldBack("ab$high", last = true)).isEqualTo("ab$high" to "")
        assertThat(TextBytes.heldBack("ab😀", last = false)).isEqualTo("ab😀" to "")
        assertThat(TextBytes.heldBack("", last = false)).isEqualTo("" to "")
    }

    @Test
    fun `a pair the search's carry cuts in two is written whole`() {
        // The first piece ends with an emoji; the carry keeps its low half for a match of two characters, and the
        // copy, flushed at that moment, gets the high half last.
        val text = "😀".repeat(TextBytes.PIECE_CHARS) + "ab"
        val (count, out) = replaced(of(text, Charsets.UTF_16LE, utf16LeBom), "ab", "c")
        assertThat(count).isEqualTo(1)
        assertThat(out).isEqualTo(utf16LeBom + text.replace("ab", "c").toByteArray(Charsets.UTF_16LE))
    }

    @Test
    fun `the search counts from the left without overlapping, case and line breaks as written`() {
        val bytes = of("aaaa Aa aa\r\nab")
        assertThat(bytes.count("aa")).isEqualTo(3)
        assertThat(bytes.count("A")).isEqualTo(1)
        assertThat(bytes.count("aa\r\nab")).isEqualTo(1)
        assertThat(bytes.count("aa\nab")).isEqualTo(0)
        assertThat(bytes.count("zz")).isEqualTo(0)
    }

    @Test
    fun `a match across two pieces is counted and replaced once`() {
        val text = "x".repeat(TextBytes.PIECE_CHARS - 2) + "needle" + "y".repeat(10) + "needle"
        val bytes = of(text)
        assertThat(bytes.count("needle")).isEqualTo(2)
        val (count, out) = replaced(bytes, "needle", "pin")
        assertThat(count).isEqualTo(2)
        assertThat(String(out, Charsets.UTF_8)).isEqualTo(text.replace("needle", "pin"))
    }

    @Test
    fun `replace all writes the file back as it is written - BOM, encoding and line breaks`() {
        val text = "Prix : 10 €\r\nprix : 12 €\nPrix\r"
        for ((charset, bom) in listOf(
            Charsets.UTF_8 to ByteArray(0),
            Charsets.UTF_8 to utf8Bom,
            Charsets.UTF_16LE to utf16LeBom,
            Charsets.UTF_16BE to utf16BeBom,
            TextEncoding.WINDOWS_1252.charset to ByteArray(0),
        )) {
            val (count, out) = replaced(of(text, charset, bom), "Prix", "Coût")
            assertThat(count).isEqualTo(2)
            assertThat(out).isEqualTo(bom + text.replace("Prix", "Coût").toByteArray(charset))
        }
    }

    @Test
    fun `a replacement the encoding cannot hold is refused before anything is written`() {
        val out = ByteArrayOutputStream()
        val error = assertThrows<UnencodableTextException> {
            of("café", TextEncoding.WINDOWS_1252.charset).replaceAll("é", "😀", out, Long.MAX_VALUE)
        }
        assertThat(error.character).isEqualTo("😀")
        assertThat(out.size()).isEqualTo(0)
        assertThrows<UnencodableTextException> { replaced(of("abc"), "b", "\uD83D") }
    }

    @Test
    fun `a file that would not be written back as it was read is not replaced in`() {
        val cutShort = "é".repeat(3).toByteArray(Charsets.UTF_8) + byteArrayOf(0xC3.toByte())
        val notText = assertThrows<NotEditableException> { replaced(TextBytes.of(cutShort), "é", "e") }
        assertThat(notText.reason).isEqualTo(NotEditableException.Reason.NOT_REVERSIBLE)
        val binary = assertThrows<NotEditableException> { replaced(TextBytes.of(byteArrayOf(0x61, 0, 0x62)), "a", "b") }
        assertThat(binary.reason).isEqualTo(NotEditableException.Reason.NOT_TEXT)
        // Statistics and search read such files as the viewer shows them.
        assertThat(TextBytes.of(cutShort).count("é")).isEqualTo(3)
    }

    @Test
    fun `no match writes nothing, and a copy past its cap is refused`() {
        assertThrows<NoMatchException> { replaced(of("abc"), "z", "y") }
        val error = assertThrows<ResultTooLargeException> { replaced(of("a".repeat(1000)), "a", "bb", cap = 1500) }
        assertThat(error.capBytes).isEqualTo(1500)
    }

    @Test
    fun `a cancellation through the progress stops the work`() {
        val bytes = of("word ".repeat(TextBytes.PIECE_CHARS))
        assertThrows<CancellationException> { bytes.stats { if (it > 0f && it < 1f) throw CancellationException("stop") } }
        assertThrows<CancellationException> { bytes.count("w") { if (it > 0f && it < 1f) throw CancellationException("stop") } }
    }

    @Test
    fun `hostile files - binary, empty, over the cap`() = runBlocking {
        val noise = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val binary = TextBytes.of(noise)
        assertThat(binary.stats().characters).isGreaterThan(0L)
        assertThat(binary.count("\u0000")).isAtLeast(0L)
        val empty = File(tmp, "empty.txt").apply { writeBytes(ByteArray(0)) }
        assertThat(TextBytes.read(StorageFileSource(empty)).stats()).isEqualTo(TextStats(0, 0, 0))
        val huge = File(tmp, "huge.txt")
        RandomAccessFile(huge, "rw").use { it.setLength(51L * 1024 * 1024) }
        assertThrows<FileTooLargeException> { runBlocking { TextBytes.read(StorageFileSource(huge)) } }
        Unit
    }
}
