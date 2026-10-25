package com.readfilestech.read_files_tech.data.viewer

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.text.TextColor
import com.readfilestech.read_files_tech.data.viewer.text.TextColors
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile

class TextDocumentTest {
    @TempDir
    lateinit var dir: File

    private fun document(bytes: ByteArray) = runBlocking { TextDocument.of(bytes) }

    private fun rows(document: TextDocument) = (0 until document.rowCount).map(document::row)

    @Test
    fun `lines end at a line feed, a carriage return before it is dropped, no empty row after the last`() {
        val document = document("one\r\ntwo\n\nfour\n".toByteArray())
        assertThat(rows(document)).containsExactly("one", "two", "", "four").inOrder()
        assertThat(rows(document("".toByteArray()))).containsExactly("")
        assertThat(rows(document("no break".toByteArray()))).containsExactly("no break")
    }

    /** The viewer says "Empty file" for these, where it showed a blank page; a line break is a line, shown. */
    @Test
    fun `a file without a character is empty, a byte order mark alone too, a lone line break not`() {
        assertThat(document(ByteArray(0)).isEmpty).isTrue()
        assertThat(document(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())).isEmpty).isTrue()
        assertThat(document(byteArrayOf(0xFF.toByte(), 0xFE.toByte())).isEmpty).isTrue()
        assertThat(document("\n".toByteArray()).isEmpty).isFalse()
        assertThat(document(" ".toByteArray()).isEmpty).isFalse()
        assertThat(document("x".repeat(TextDocument.ROW_BYTES * 2).toByteArray()).isEmpty).isFalse()
    }

    /** The 2.15.3 read with a strict UTF-8 decoder: these three ended in "Erreur de lecture" (F37). */
    @Test
    fun `UTF-8 with or without a BOM, UTF-16 with a BOM, and Windows-1252 when it is not UTF-8`() {
        val text = "Été\nçà€"
        val utf8 = document(text.toByteArray(Charsets.UTF_8))
        assertThat(utf8.encoding).isEqualTo(TextEncoding.UTF_8)
        assertThat(rows(utf8)).containsExactly("Été", "çà€").inOrder()

        val bom = document(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8))
        assertThat(bom.encoding).isEqualTo(TextEncoding.UTF_8_BOM)
        assertThat(rows(bom)).containsExactly("Été", "çà€").inOrder()

        val le = document(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a\r\nÉ😀\n".toByteArray(Charsets.UTF_16LE))
        assertThat(le.encoding).isEqualTo(TextEncoding.UTF_16LE)
        assertThat(rows(le)).containsExactly("a", "É😀").inOrder()

        val be = document(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "x\ny".toByteArray(Charsets.UTF_16BE))
        assertThat(rows(be)).containsExactly("x", "y").inOrder()

        val windows = document(text.toByteArray(charset("windows-1252")))
        assertThat(windows.encoding).isEqualTo(TextEncoding.WINDOWS_1252)
        assertThat(rows(windows)).containsExactly("Été", "çà€").inOrder()
    }

    /** A line longer than a row is cut, at a character boundary, and the pieces carry it on. */
    @Test
    fun `a long line is cut into rows that keep every character whole`() {
        // The leading `a` puts every é and every emoji off the row size: a cut at the size itself would split one.
        val line = "a" + "é".repeat(TextDocument.ROW_BYTES) + "😀".repeat(TextDocument.ROW_BYTES)
        listOf(Charsets.UTF_8, Charsets.UTF_16LE).forEach { charset ->
            val prefix = if (charset == Charsets.UTF_16LE) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) else byteArrayOf()
            val document = document(prefix + "$line\nend".toByteArray(charset))
            val all = rows(document)
            assertThat(all.size).isGreaterThan(2)
            all.dropLast(1).forEach { assertThat(it).doesNotContain("�") }
            assertThat(all.dropLast(1).joinToString("")).isEqualTo(line)
            assertThat(all.last()).isEqualTo("end")
            (0 until document.rowCount - 2).forEach { assertThat(document.continues(it)).isTrue() }
            assertThat(document.continues(document.rowCount - 2)).isFalse()
        }
        // In Windows-1252 every byte is a character: cut at the row size exactly.
        val windows = document("é".repeat(TextDocument.ROW_BYTES * 2).toByteArray(charset("windows-1252")))
        assertThat(windows.row(0).length).isEqualTo(TextDocument.ROW_BYTES)
    }

    /** Claude review R9: a file of lone low surrogates never found a boundary — one row of 50 MiB. */
    @Test
    fun `lone UTF-16 surrogates are cut like any character, a real pair never`() {
        val loneLe = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + ByteArray(20_000) { if (it % 2 == 0) 0 else 0xDC.toByte() }
        val loneBe = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + ByteArray(20_000) { if (it % 2 == 0) 0xDC.toByte() else 0 }
        listOf(loneLe, loneBe).forEach { bytes ->
            val document = document(bytes)
            assertThat(document.rowCount).isAtLeast(4)
            rows(document).forEach { assertThat(it.length).isAtMost(TextDocument.ROW_BYTES / 2 + 1) }
        }
    }

    /** R9: a CR at the cut made the LF a row of its own — an empty line that is not in the file. */
    @Test
    fun `a line break is never split between its CR and its LF`() {
        val windows = charset("windows-1252")
        listOf(Charsets.UTF_8, windows, Charsets.UTF_16LE).forEach { charset ->
            // A line of the row size exactly, in bytes (`é` takes one in Windows-1252, two in the others):
            // its CR falls where a row would be cut.
            val line = "é".repeat(if (charset == windows) TextDocument.ROW_BYTES else TextDocument.ROW_BYTES / 2)
            val prefix = if (charset == Charsets.UTF_16LE) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) else byteArrayOf()
            val document = document(prefix + "$line\r\nnext\r\n".toByteArray(charset))
            assertThat(rows(document)).containsExactly(line, "next").inOrder()
        }
    }

    /**
     * One start kept in 16 (R9: an index of every row took 200 MB at the cap): rows, continuations and
     * [TextDocument.rowAfter] checked against a split made here, independently, on lines of every length.
     */
    @Test
    fun `the sparse index finds every row, as a plain split does`() {
        val lines = (0 until 300).map { "y".repeat((it * 37) % (TextDocument.ROW_BYTES * 2 + 50)) }
        val document = document(lines.joinToString("\n").toByteArray())
        val expected = lines.flatMap { line -> if (line.isEmpty()) listOf("") else line.chunked(TextDocument.ROW_BYTES) }
        assertThat(rows(document)).containsExactlyElementsIn(expected).inOrder()
        // The rows' starts, as bytes, from the same split: each row is its length plus a line feed when it ends a line.
        val ends = lines.flatMap { line ->
            val pieces = if (line.isEmpty()) listOf("") else line.chunked(TextDocument.ROW_BYTES)
            pieces.mapIndexed { i, piece -> piece.length + if (i == pieces.lastIndex) 1 else 0 }
        }
        val starts = ends.runningFold(0, Int::plus).dropLast(1)
        (0 until document.rowCount - 1).forEach { i ->
            assertThat(document.continues(i)).isEqualTo(ends[i] == expected[i].length)
        }
        listOf(0, 1, 15, 16, 17, 100, 299).filter { it < document.rowCount }.forEach { from ->
            listOf(0, 1, 4096, 10_000, 100_000).forEach { bytes ->
                val reference = (from until document.rowCount).firstOrNull { starts[it] >= starts[from] + bytes } ?: document.rowCount
                assertThat(document.rowAfter(from, bytes)).isEqualTo(reference)
            }
        }
    }

    /** R9: a UTF-8 log cut short in its last `é` went to Windows-1252 whole; `café` in Windows-1252 must not go the other way. */
    @Test
    fun `a last character cut short keeps a UTF-8 text in UTF-8`() {
        val cut = "été\n".repeat(100).toByteArray() + byteArrayOf(0xC3.toByte())
        assertThat(document(cut).encoding).isEqualTo(TextEncoding.UTF_8)
        assertThat(document(cut).row(0)).isEqualTo("été")
        assertThat(document("café".toByteArray(charset("windows-1252"))).encoding).isEqualTo(TextEncoding.WINDOWS_1252)
        assertThat(document("café".toByteArray(charset("windows-1252"))).row(0)).isEqualTo("café")
    }

    @Test
    fun `a source read through its cap, a file larger than the cap refused, a file that grew too`() {
        val file = File(dir, "a.txt").apply { writeText("hello\nworld") }
        val document = runBlocking { TextDocument.read(StorageFileSource(file)) }
        assertThat(rows(document)).containsExactly("hello", "world").inOrder()

        val big = File(dir, "big.log")
        RandomAccessFile(big, "rw").use { it.setLength(FileCaps.TEXT_VIEWER + 1) }
        val refused = assertThrows<FileTooLargeException> { runBlocking { TextDocument.read(StorageFileSource(big)) } }
        assertThat(refused.capBytes).isEqualTo(FileCaps.TEXT_VIEWER)

        // Announced small, then longer than the cap: the stream refuses what the size let through.
        val growing = object : ViewerSource {
            override val name = "grown.txt"
            override val shareable: File? = null
            override fun length() = 3L
            override fun open(cap: Long) = BoundedInputStream(ByteArrayInputStream(ByteArray(8) { 'a'.code.toByte() }), 4)
            override fun acquireFile(cap: Long) = error("not used")
        }
        assertThrows<FileTooLargeException> { runBlocking { TextDocument.read(growing) } }
    }

    @Test
    fun `the colours of the text, hex before rgb, each once, clamped, bounded`() {
        val document = document("a { color: #FFF; b: #12ab34 }\n#FFF #abcd #abc_ rgb(300, 0, 10) rgba(1,2,3,0.5) #zzz".toByteArray())
        val colors = runBlocking { TextColors.extract(document) }
        assertThat(colors).containsExactly(
            TextColor("#FFF", 0xFFFFFFFF.toInt()),
            TextColor("#12ab34", 0xFF12AB34.toInt()),
            TextColor("rgb(300, 0, 10)", 0xFFFF000A.toInt()),
            TextColor("rgba(1,2,3)", 0xFF010203.toInt()),
        ).inOrder()

        val many = document((0 until 1000).joinToString("\n") { "#%06x".format(it) }.toByteArray())
        assertThat(runBlocking { TextColors.extract(many) }).hasSize(TextColors.MAX_COLORS)
    }

    /**
     * The colours are searched in pieces of 256 KiB (64 rows of a long line): a code across the cut is
     * found once, whole — not as `#abc` of `#abcdef` — and so is one that a line break follows.
     */
    @Test
    fun `a colour across the cut between two pieces is found whole`() {
        val piece = 64 * TextDocument.ROW_BYTES
        // The cut falls after `#abc`: read alone, the first piece would give that code.
        val document = document(("x".repeat(piece - 4) + "#abcdef x").toByteArray())
        assertThat(document.rowAfter(0, piece)).isEqualTo(64)
        // Rows of 10 bytes: the first one 25 bytes on starts at 30; none starts 100 bytes on.
        val tens = document("123456789\n".repeat(5).toByteArray())
        assertThat(tens.rowAfter(0, 25)).isEqualTo(3)
        assertThat(tens.rowAfter(1, 10)).isEqualTo(2)
        assertThat(tens.rowAfter(0, 100)).isEqualTo(5)
        assertThat(runBlocking { TextColors.extract(document) }.map(TextColor::code)).containsExactly("#abcdef")

        // What a piece borrows from the next ends inside a code (`#abc` of `#abcdef`): the next one finds it.
        val borrowedEnd = document(("x".repeat(piece) + "y".repeat(124) + "#abcdef").toByteArray())
        assertThat(runBlocking { TextColors.extract(borrowedEnd) }.map(TextColor::code)).containsExactly("#abcdef")

        // Searched as the 2.15.3 searched its whole text: a line break ends a hex code, `\s` spans one.
        val lines = document("#abc\n#def\r\nrgb(1,\n2, 3)".toByteArray())
        assertThat(runBlocking { TextColors.extract(lines) }.map(TextColor::code))
            .containsExactly("#abc", "#def", "rgb(1,\n2, 3)").inOrder()
    }

    /** Ten million short lines: searched row by row, it took 84 s on an emulator; by pieces, a fraction. */
    @Test
    fun `ten million lines are searched quickly`() {
        val document = document(ByteArray(10_000_000) { '\n'.code.toByte() } + "#123456".toByteArray())
        val start = System.nanoTime()
        assertThat(runBlocking { TextColors.extract(document) }.map(TextColor::code)).containsExactly("#123456")
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000)
    }
}
