package com.readfilestech.read_files_tech.data.search

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The content search: what it finds, in which encodings, what it says it did not search. */
class ContentSearchTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var root: File

    @BeforeEach
    fun setUp() {
        root = File(tmp, "root").apply { mkdirs() }
    }

    private fun file(path: String, bytes: ByteArray) = File(root, path).apply {
        parentFile.mkdirs()
        writeBytes(bytes)
    }

    private fun text(path: String, content: String) = file(path, content.toByteArray(Charsets.UTF_8))

    private fun events(pattern: SearchPattern, maxFiles: Int = FileCaps.CONTENT_SEARCH_FILES): List<ContentEvent> =
        runBlocking { ContentSearch.run(root, pattern, maxFiles).toList() }

    private fun hits(pattern: SearchPattern) = events(pattern).filterIsInstance<ContentEvent.Found>().flatMap { it.hits }

    private fun finished(pattern: SearchPattern) = events(pattern).last() as ContentEvent.Finished

    private fun word(text: String, matchCase: Boolean = false) = SearchPattern.literal(text, matchCase)

    private fun names(pattern: SearchPattern) = hits(pattern).map { File(it.path).name }.sorted()

    @Test
    fun `the text files holding the word, through sub-folders, with their lines`() {
        text("a.txt", "one\ntwo needle\nthree\nNEEDLE four")
        text("sub/b.md", "needle")
        text("sub/c.txt", "nothing")
        text("image.png", "needle")
        val hit = hits(word("needle")).single { it.path.endsWith("a.txt") }
        assertThat(hit.lineCount).isEqualTo(2)
        assertThat(hit.lines.map { it.line to it.text }).containsExactly(2 to "two needle", 4 to "NEEDLE four").inOrder()
        assertThat(names(word("needle"))).containsExactly("a.txt", "b.md")
        assertThat(names(word("NEEDLE", matchCase = true))).containsExactly("a.txt")
    }

    /** D35: the 2.15.3 decoded strict UTF-8, and a Windows-1252 text threw — never found, without a word. */
    @Test
    fun `an accented word is found in Windows-1252, UTF-8 and UTF-16 texts`() {
        file("latin.txt", "contrat signé\r\n".toByteArray(charset("windows-1252")))
        text("utf8.txt", "contrat signé")
        file("utf16.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "contrat signé".toByteArray(Charsets.UTF_16LE))
        assertThat(names(word("signé"))).containsExactly("latin.txt", "utf16.txt", "utf8.txt")
        assertThat(names(word("SIGNÉ"))).containsExactly("latin.txt", "utf16.txt", "utf8.txt")
    }

    @Test
    fun `a byte order mark is not part of the first line`() {
        file("bom8.txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "needle first".toByteArray())
        file("bom16.txt", byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "needle first".toByteArray(Charsets.UTF_16BE))
        val lines = hits(word("needle")).map { it.lines.single() }
        assertThat(lines.map { it.text }).containsExactly("needle first", "needle first")
        assertThat(lines.map { it.matches.single() }).containsExactly(0 until 6, 0 until 6)
    }

    @Test
    fun `a regex is matched line by line, its matches highlighted`() {
        text("dates.log", "start 2026-10-14 and 2026-10-15\nnone here\n")
        val line = hits(SearchPattern.regex("""\d{4}-\d{2}-\d{2}""", matchCase = true)).single().lines.single()
        assertThat(line.line).isEqualTo(1)
        assertThat(line.matches.map { line.text.substring(it) }).containsExactly("2026-10-14", "2026-10-15").inOrder()
        assertThat(hits(SearchPattern.regex("^none", matchCase = true)).single().lines.single().line).isEqualTo(2)
    }

    /** GPT review R24: a line break ending the text made one line more, empty, that `^$` found. */
    @Test
    fun `a line break ending a file opens no line after it`() {
        text("one.txt", "x\n")
        text("crlf.txt", "x\r\n")
        text("cr.txt", "x\r")
        text("blank.txt", "x\n\n")
        val found = hits(SearchPattern.regex("^$", matchCase = true))
        assertThat(found.map { File(it.path).name }).containsExactly("blank.txt")
        assertThat(found.single().lines.single().line).isEqualTo(2)
        assertThat(ContentSearch.linesOf("").toList()).containsExactly("")
    }

    @Test
    fun `a long line is kept as a window around its first match`() {
        text("min.js", "x".repeat(5_000) + "needle" + "y".repeat(5_000))
        val line = hits(word("needle")).single().lines.single()
        assertThat(line.text.length).isEqualTo(FileCaps.CONTENT_SEARCH_LINE_CHARS)
        assertThat(line.text).contains("needle")
        assertThat(line.cutBefore).isTrue()
        assertThat(line.cutAfter).isTrue()
        assertThat(line.text.substring(line.matches.single())).isEqualTo("needle")
    }

    @Test
    fun `the lines kept of one file are bounded, all are counted`() {
        text("many.txt", "hit\n".repeat(FileCaps.CONTENT_SEARCH_LINES + 50))
        val hit = hits(word("hit")).single()
        assertThat(hit.lines).hasSize(FileCaps.CONTENT_SEARCH_LINES)
        assertThat(hit.lineCount).isEqualTo(FileCaps.CONTENT_SEARCH_LINES + 50)
    }

    @Test
    fun `a file over the cap is not read, and said`() {
        text("big.txt", "needle" + " ".repeat(FileCaps.SEARCH_CONTENT.toInt()))
        text("small.txt", "needle")
        assertThat(names(word("needle"))).containsExactly("small.txt")
        val end = finished(word("needle"))
        assertThat(end.tooLarge).isEqualTo(1)
        assertThat(end.scanned).isEqualTo(2)
    }

    @Test
    fun `the trash and Android data are not searched, hidden folders are`() {
        text(".RFT_Corbeille/items/1/old.txt", "needle")
        text("Android/data/com.app/x.txt", "needle")
        text(".config/settings.ini", "needle")
        assertThat(names(word("needle"))).containsExactly("settings.ini")
    }

    @Test
    fun `the search stops at its cap of files found, and says so`() {
        (1..5).forEach { text("f$it.txt", "needle") }
        val all = events(word("needle"), maxFiles = 3)
        assertThat(all.filterIsInstance<ContentEvent.Found>().flatMap { it.hits }).hasSize(3)
        assertThat((all.last() as ContentEvent.Finished).full).isTrue()
        assertThat(finished(word("needle")).full).isFalse()
    }

    @Test
    fun `hostile files are searched without failing - empty, binary, cut, wrong extension`() {
        file("empty.txt", ByteArray(0))
        file("binary.txt", ByteArray(4096) { (it * 37).toByte() } + "needle".toByteArray())
        file("cut.txt", "needle é".toByteArray(Charsets.UTF_8).copyOf(8))
        file("photo.jpg.txt", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + "needle".toByteArray())
        assertThat(names(word("needle"))).containsExactly("binary.txt", "cut.txt", "photo.jpg.txt")
        assertThat(finished(word("needle")).scanned).isEqualTo(4)
    }
}
