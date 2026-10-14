package com.readfilestech.read_files_tech.data.viewer.text

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.Charset
import kotlin.random.Random

/** The colours of a row computed from a checkpoint, the raw rows the scan reads, and what bounds them. */
class CodeColouringTest {
    private fun document(bytes: ByteArray) = runBlocking { TextDocument.of(bytes) }

    private fun document(text: String) = document(text.toByteArray())

    /** Every row's spans, tokenized from the first row, decoded: what the colours must be. */
    private fun fromTheStart(document: TextDocument, language: CodeLanguage): List<List<CodeSpan>> {
        val tokenizer = language.tokenizer()
        var state = 0
        return (0 until document.rowCount).map { index ->
            mutableListOf<CodeSpan>().also {
                state = tokenizer.tokenize(document.row(index), state, !document.continues(index), it)
            }
        }
    }

    /**
     * Random lines of what changes a state in some language, non-ASCII characters next to it, CRLF or LF, and
     * every 50th line long enough to be cut at ROW_BYTES — in UTF-8, between the bytes of a character too.
     */
    private fun hostileText(emoji: Boolean): String {
        val random = Random(SEED)
        val tokens = listOf(
            "/*", "*/", "//", "#", "'", "\"", "`", "'''", "\\", "<!--", "-->", "<?php", "<?=", "?>", "<a ", ">", "=", "{", "}",
            ";", ":", "```", "~~~", "# ", "- ", "*", "_", "[", "](", ")", "<![CDATA[", "]]>", "r'", "@m", "\$v", "é", "€", "x", " ", "1",
        ) + if (emoji) listOf("😀") else emptyList()
        return buildString {
            repeat(LINES) { line ->
                repeat(if (line % LONG_EVERY == 0) LONG_TOKENS else random.nextInt(SHORT_TOKENS)) { append(tokens.random(random)) }
                append(if (random.nextBoolean()) "\n" else "\r\n")
            }
        }
    }

    /** The hostile text in each encoding the viewer reads, and the encoding it must be read in. */
    private fun encodings(): List<Pair<TextEncoding, ByteArray>> {
        val text = hostileText(emoji = true)
        return listOf(
            TextEncoding.UTF_8 to text.toByteArray(Charsets.UTF_8),
            TextEncoding.UTF_8_BOM to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8),
            TextEncoding.UTF_16LE to byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            TextEncoding.UTF_16BE to byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE),
            TextEncoding.WINDOWS_1252 to hostileText(emoji = false).toByteArray(Charset.forName("windows-1252")),
        )
    }

    private fun ascii(text: CharSequence): String = text.filter { it < '\u0080' }.toString()

    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/text")

    /** A file of the corpus made by real software, read as the viewer reads it, its rows coloured as the screen does. */
    private suspend fun corpusFile(name: String): Pair<TextDocument, List<List<CodeSpan>>> {
        val file = File(corpus, name)
        check(file.isFile) { "missing corpus file: $file" }
        val document = TextDocument.read(StorageFileSource(file))
        val colouring = CodeColouring(document, checkNotNull(CodeLanguage.of(file.extension)))
        colouring.scan()
        val spans = (0 until document.rowCount).map { colouring.spans(it) }
        assertThat(spans).isEqualTo(fromTheStart(document, colouring.language))
        return document to spans
    }

    /** Line [number] as an editor counts it (these files have no line long enough to be cut): (text, style). */
    private fun Pair<TextDocument, List<List<CodeSpan>>>.line(number: Int): List<Pair<String, CodeStyle>> {
        val row = first.row(number - 1)
        return second[number - 1].map { row.substring(it.start, it.end) to it.style }
    }

    @Test
    fun `real code - a regular expression, a closing tag in a string, a declaration, a raw string, comments`() = runTest {
        val js = corpusFile("ms-index.js")
        assertThat(js.line(53).map { it.second }).contains(CodeStyle.REGEXP)
        assertThat(js.line(159)).contains("function" to CodeStyle.KEYWORD)
        val php = corpusFile("php-cs-fixer-no-closing-tag.php")
        assertThat(php.line(37)).containsExactly("new" to CodeStyle.KEYWORD, "\"<?php\\nclass Sample\\n{\\n}\\n?>\\n\"" to CodeStyle.STRING)
            .inOrder()
        assertThat(php.line(41)).contains("public" to CodeStyle.KEYWORD)
        val css = corpusFile("h5bp-style.css")
        assertThat(css.line(15)).containsExactly("color" to CodeStyle.PROPERTY, "#222" to CodeStyle.NUMBER).inOrder()
        assertThat(css.line(178)).contains("@media" to CodeStyle.KEYWORD)
        val dart = corpusFile("rft-color-extract.dart")
        assertThat(dart.line(24)).contains("r'#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})\\b'" to CodeStyle.STRING)
        val xml = corpusFile("gradle-published-pom.xml")
        assertThat(xml.line(3).map { it.second }).containsExactly(CodeStyle.COMMENT)
        assertThat(xml.line(8)).containsExactly("modelVersion" to CodeStyle.TAG, "modelVersion" to CodeStyle.TAG)
    }

    @Test
    fun `a raw row is its decoded row - its ASCII, its line end - in every encoding and at every edge`() {
        val edges = listOf("", "a\n", "a\r\n\r\n", "\r", "b\rc").map { TextEncoding.UTF_8 to it.toByteArray() } +
            (TextEncoding.UTF_16LE to byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "odd\n".toByteArray(Charsets.UTF_16LE) + 0x41.toByte())
        for ((encoding, bytes) in encodings() + edges) {
            val document = document(bytes)
            assertThat(document.encoding).isEqualTo(encoding)
            val raw = document.rawRows(0)
            for (index in 0 until document.rowCount) {
                assertThat(raw.next()).isTrue()
                assertThat(ascii(raw)).isEqualTo(ascii(document.row(index)))
                assertThat(raw.endsLine).isEqualTo(!document.continues(index))
            }
            assertThat(raw.next()).isFalse()
        }
    }

    /** What lets the scan read 50 MiB without decoding it. */
    @Test
    fun `a raw row leaves the state its decoded text leaves, in every language and every encoding`() {
        for ((_, bytes) in encodings()) {
            val document = document(bytes)
            // Some rows are cut, or the test would not see a cut line carried.
            assertThat((0 until document.rowCount).count(document::continues)).isGreaterThan(0)
            for (language in CodeLanguage.entries) {
                val decoded = language.tokenizer()
                val undecoded = language.tokenizer()
                val raw = document.rawRows(0)
                var expected = 0
                var actual = 0
                for (index in 0 until document.rowCount) {
                    raw.next()
                    expected = decoded.tokenize(document.row(index), expected, !document.continues(index), null)
                    actual = undecoded.tokenize(raw, actual, raw.endsLine, null)
                    assertThat(actual).isEqualTo(expected)
                }
            }
        }
    }

    /** Rows asked for from the last up: each from its checkpoint; then from the first down: each from the row before. */
    @Test
    fun `the colours of a row from its checkpoint or the row before are those from the start, in every language`() = runTest {
        // A long line too: checkpoints by bytes, 16 rows apart, besides those by rows.
        val text = hostileText(emoji = true) + "var a = 'b'; /* c */ ".repeat(LONG_LINE_REPEATS) + "\n" + hostileText(emoji = false)
        val document = document(text)
        for (language in CodeLanguage.entries) {
            val colouring = CodeColouring(document, language)
            colouring.scan()
            assertThat(colouring.checkpoints).isGreaterThan(2)
            val expected = fromTheStart(document, language)
            for (index in document.rowCount - 1 downTo 0) assertThat(colouring.spans(index)).isEqualTo(expected[index])
            for (index in 0 until document.rowCount) assertThat(colouring.spans(index)).isEqualTo(expected[index])
        }
    }

    /** No bounded lookback would see it: the comment is thousands of rows and many checkpoints above. */
    @Test
    fun `a comment opened on the first line colours the last of thousands`() = runTest {
        val document = document("/*\n" + "var a = 1;\n".repeat(COMMENTED_LINES))
        val colouring = CodeColouring(document, CodeLanguage.JAVASCRIPT)
        colouring.scan()
        assertThat(colouring.spans(document.rowCount - 1)).containsExactly(CodeSpan(0, "var a = 1;".length, CodeStyle.COMMENT))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a row the scan has not reached waits for it`() = runTest {
        val document = document("x\n".repeat(COMMENTED_LINES) + "var")
        val colouring = CodeColouring(document, CodeLanguage.JAVASCRIPT)
        val last = async { colouring.spans(document.rowCount - 1) }
        runCurrent()
        assertThat(last.isCompleted).isFalse()
        colouring.scan()
        assertThat(last.await()).containsExactly(CodeSpan(0, 3, CodeStyle.KEYWORD))
    }

    @Test
    fun `a scan stopped before the end leaves the rows it did not pass plain, and none waits for ever`() = runTest {
        val document = document("var a;\n".repeat(COMMENTED_LINES))
        val colouring = CodeColouring(document, CodeLanguage.JAVASCRIPT)
        // Cancelled from the start: it stops at its first checkpoint.
        runCatching {
            coroutineScope {
                cancel()
                colouring.scan()
            }
        }
        assertThat(colouring.spans(document.rowCount - 1)).isEmpty()
    }

    @Test
    fun `checkpoints are bounded - by rows on short lines, by bytes on one long line, a file of comment openers too`() = runTest {
        val lineFeeds = document("\n".repeat(LINE_FEEDS))
        CodeColouring(lineFeeds, CodeLanguage.CSS).apply {
            scan()
            assertThat(checkpoints).isEqualTo(1 + (lineFeeds.rowCount - 1) / CodeColouring.CHECKPOINT_ROWS)
        }
        // One line of nothing but comment openers, which nest in Dart: one comment, its depth bounded.
        val openers = document("/*".repeat(MIB / 2))
        CodeColouring(openers, CodeLanguage.DART).apply {
            scan()
            assertThat(openers.rowCount).isEqualTo(MIB / TextDocument.ROW_BYTES)
            assertThat(checkpoints).isEqualTo(MIB / CodeColouring.CHECKPOINT_BYTES)
            assertThat(spans(openers.rowCount - 1)).containsExactly(CodeSpan(0, TextDocument.ROW_BYTES, CodeStyle.COMMENT))
        }
    }

    private companion object {
        const val SEED = 2153
        const val LINES = 600
        const val LONG_EVERY = 50
        const val LONG_TOKENS = 3000
        const val SHORT_TOKENS = 30
        const val LONG_LINE_REPEATS = 20_000
        const val COMMENTED_LINES = 5000
        const val LINE_FEEDS = 100_000
        const val MIB = 1024 * 1024
    }
}
