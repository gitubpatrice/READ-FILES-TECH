package com.readfilestech.read_files_tech.data.viewer.markdown

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The Markdown viewer's data layer. The 2.15.3 has no Dart test of its Markdown screen
 * (`md_viewer_screen.dart`): these cover what it rendered through gpt_markdown, the images it must never
 * load, the links a tap may open, and the caps it did not have.
 */
class MarkdownFileTest {
    private fun content(bytes: ByteArray) = runBlocking { MarkdownFile.of(bytes) }

    private fun blocks(markdown: String): List<MarkdownBlock> =
        (content(markdown.toByteArray()).rendering as MarkdownRendering.Rendered).blocks

    private fun texts(markdown: String) = blocks(markdown).filterIsInstance<MarkdownText>()

    /** The text of each run of [block], with its style and link. */
    private fun runs(block: MarkdownText) = block.runs.map {
        Triple(block.text.substring(it.start, it.end), it.style, it.link)
    }

    @Test
    fun `headings, paragraphs and their styles`() {
        val (heading, paragraph) = texts("# Title\n\nSome **bold**, *it*, `code`, ~~gone~~ and ***both***.")
        assertThat(heading.heading).isEqualTo(1)
        assertThat(heading.text).isEqualTo("Title")
        assertThat(paragraph.heading).isEqualTo(0)
        assertThat(paragraph.text).isEqualTo("Some bold, it, code, gone and both.")
        assertThat(runs(paragraph)).containsExactly(
            Triple("bold", MarkdownStyle.BOLD, null),
            Triple("it", MarkdownStyle.ITALIC, null),
            Triple("code", MarkdownStyle.CODE, null),
            Triple("gone", MarkdownStyle.STRIKE, null),
            Triple("both", MarkdownStyle.BOLD or MarkdownStyle.ITALIC, null),
        ).inOrder()
    }

    @Test
    fun `links keep their destination, line breaks are kept or joined`() {
        val (paragraph) = texts("See [the *site*](https://example.org \"t\") and <mailto:a@b.c>.\nNext  \nline")
        assertThat(paragraph.text).isEqualTo("See the site and mailto:a@b.c. Next\nline")
        assertThat(runs(paragraph)).containsExactly(
            Triple("the ", 0, "https://example.org"),
            Triple("site", MarkdownStyle.ITALIC, "https://example.org"),
            Triple("mailto:a@b.c", 0, "mailto:a@b.c"),
        ).inOrder()
    }

    /** gpt_markdown fetched `![](https://…)` on opening (V-M4); the 2.15.3 still showed a local image. */
    @Test
    fun `an image is its place and its description, its destination nowhere`() {
        val blocks = blocks(
            "![a cat](https://tracker.example/p.png) ![](file:///data/data/x/secret.png)\n\n[![badge](b.svg)](https://ci.example)"
        )
        val (images, badge) = blocks.map { it as MarkdownText }
        assertThat(images.text).isEqualTo("${MarkdownStyle.IMAGE_MARK}a cat ${MarkdownStyle.IMAGE_MARK}")
        assertThat(runs(images)).containsExactly(
            Triple(MarkdownStyle.IMAGE_MARK.toString(), MarkdownStyle.IMAGE, null),
            Triple("a cat", MarkdownStyle.ITALIC, null),
            Triple(MarkdownStyle.IMAGE_MARK.toString(), MarkdownStyle.IMAGE, null),
        ).inOrder()
        // A badge in a link leads to the link, never to the image.
        assertThat(runs(badge).map { it.third }.toSet()).containsExactly("https://ci.example")
        // Two places side by side are two icons; in bold, in another image's description, still icons.
        val (twice) = texts("![](a)![](b)")
        assertThat(twice.runs).containsExactly(
            MarkdownRun(0, 1, MarkdownStyle.IMAGE, null),
            MarkdownRun(1, 2, MarkdownStyle.IMAGE, null),
        ).inOrder()
        val (inside) = texts("**![![x](a)](b)**")
        assertThat(runs(inside).take(2)).containsExactly(
            Triple(MarkdownStyle.IMAGE_MARK.toString(), MarkdownStyle.IMAGE, null),
            Triple(MarkdownStyle.IMAGE_MARK.toString(), MarkdownStyle.IMAGE, null),
        ).inOrder()
        assertThat(blocks.toString()).doesNotContain("tracker.example")
        assertThat(blocks.toString()).doesNotContain("secret.png")
        assertThat(blocks.toString()).doesNotContain("b.svg")
    }

    @Test
    fun `lists, their markers, their depth, an empty item`() {
        val blocks = texts("- one\n  - inner\n-\n\n3) three\n4) four")
        assertThat(blocks.map { Triple(it.marker, it.text, it.lists) }).containsExactly(
            Triple("•", "one", 1),
            Triple("•", "inner", 2),
            Triple("•", "", 1),
            Triple("3)", "three", 1),
            Triple("4)", "four", 1),
        ).inOrder()
    }

    @Test
    fun `quotes, code, raw HTML as written, a rule`() {
        val blocks = blocks(
            "> quoted\n> > twice\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n<div onclick=\"x()\">hi</div>\n\n---\n\n    indented"
        )
        assertThat(blocks).containsExactly(
            MarkdownText("quoted", emptyList(), quotes = 1),
            MarkdownText("twice", emptyList(), quotes = 2),
            MarkdownCode("val a = 1\n\nval b = 2"),
            MarkdownCode("<div onclick=\"x()\">hi</div>"),
            MarkdownRule(),
            MarkdownCode("indented"),
        ).inOrder()
    }

    @Test
    fun `a table, its alignments, and rows padded to the header`() {
        val rows = blocks("| a | b | c |\n|:-|:-:|-:|\n| 1 | **2** |\n\n|x|\n|-|\n|y|").filterIsInstance<MarkdownTableRow>()
        assertThat(rows.map { row -> row.cells.map { it.text } }).containsExactly(
            listOf("a", "b", "c"),
            listOf("1", "2", ""),
            listOf("x"),
            listOf("y"),
        ).inOrder()
        assertThat(rows.map { it.header }).containsExactly(true, false, true, false).inOrder()
        assertThat(rows.map { it.table }).containsExactly(1, 1, 2, 2).inOrder()
        assertThat(rows[0].cells.map { it.alignment })
            .containsExactly(MarkdownAlignment.LEFT, MarkdownAlignment.CENTER, MarkdownAlignment.RIGHT).inOrder()
        assertThat(rows[1].cells[1].runs).containsExactly(MarkdownRun(0, 1, MarkdownStyle.BOLD, null))
    }

    @Test
    fun `a long paragraph is cut into pieces, its styles with it, never inside a surrogate pair`() {
        val piece = MarkdownRenderer.PIECE
        val pieces = texts("a".repeat(piece - 1) + "😀**" + "b".repeat(piece) + "**")
        assertThat(pieces.map { it.text.length }).containsExactly(piece - 1, piece, 2).inOrder()
        assertThat(pieces.map { it.continued }).containsExactly(false, true, true).inOrder()
        assertThat(pieces[1].text).startsWith("😀")
        assertThat(pieces[1].runs).containsExactly(MarkdownRun(2, piece, MarkdownStyle.BOLD, null))
        assertThat(pieces[2].runs).containsExactly(MarkdownRun(0, 2, MarkdownStyle.BOLD, null))
        // A cell keeps its first piece only.
        val (_, row) = blocks("|h|\n|-|\n|${"c".repeat(piece * 2)}|").map { it as MarkdownTableRow }
        assertThat(row.cells.single().text).hasLength(piece)
    }

    @Test
    fun `a long code line is cut, lines are kept whole when they fit`() {
        val piece = MarkdownRenderer.PIECE
        val code = blocks("```\n" + "x".repeat(piece + 10) + "\nshort\n```").map { it as MarkdownCode }
        assertThat(
            code
        ).containsExactly(MarkdownCode("x".repeat(piece)), MarkdownCode("x".repeat(10) + "\nshort", continued = true)).inOrder()
        // Lines that do not fit together are cut between them.
        val lines = blocks("```\n" + "y".repeat(piece - 2) + "\nzzz\n```").map { it as MarkdownCode }
        assertThat(lines).containsExactly(MarkdownCode("y".repeat(piece - 2)), MarkdownCode("zzz", continued = true)).inOrder()
    }

    /** The 2.15.3 read UTF-8 strictly and showed "Erreur : FormatException" as the document. */
    @Test
    fun `a text in Windows-1252 or UTF-16 is read`() {
        for (bytes in listOf(
            "# Été".toByteArray(charset("windows-1252")),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "# Été".toByteArray(Charsets.UTF_16LE)
        )) {
            val content = content(bytes)
            assertThat(
                (content.rendering as MarkdownRendering.Rendered).blocks
            ).containsExactly(MarkdownText("Été", emptyList(), heading = 1))
            assertThat(content.source.row(0)).isEqualTo("# Été")
        }
    }

    @Test
    fun `past its cap a text is not rendered, its source is`() {
        val atCap = ByteArray(FileCaps.MARKDOWN_RENDERED.toInt()) { 'a'.code.toByte() }
        assertThat(content(atCap).rendering).isInstanceOf(MarkdownRendering.Rendered::class.java)
        val over = content(atCap + 'a'.code.toByte())
        assertThat(over.rendering).isEqualTo(MarkdownRendering.TooLong(FileCaps.MARKDOWN_RENDERED))
        assertThat(over.source.rowCount).isGreaterThan(1)
    }

    /** GFM pads every row to the header's columns: a wide header over short lines was a cell bomb. */
    @Test
    fun `tables that could hold too many cells are not rendered`() {
        val header = "|a".repeat(1000) + "|\n" + "|-".repeat(1000) + "|\n"
        val fits = header + "|\n".repeat(FileCaps.MARKDOWN_TABLE_CELLS / 1002 - 3)
        assertThat(content(fits.toByteArray()).rendering).isInstanceOf(MarkdownRendering.Rendered::class.java)
        val bomb = header + "x\n".repeat(FileCaps.MARKDOWN_TABLE_CELLS / 1002)
        assertThat(content(bomb.toByteArray()).rendering).isEqualTo(MarkdownRendering.TooManyCells(FileCaps.MARKDOWN_TABLE_CELLS))
        // In a quote too, and a second header inside a narrow table counts with its own width.
        // Columns are counted as the pipes plus one: the outer pipes are optional.
        val quoted = header.lines().joinToString("\n") { "> $it" } + "> x\n".repeat(10)
        assertThat(MarkdownFile.tableCells(quoted)).isEqualTo(12L * 1002)
        assertThat(MarkdownFile.tableCells("|a|\n|-|\nx\n" + header + "y\n")).isEqualTo(2L * 3 + 3 + 3 + 2L * 1002 + 1002)
        // A narrower delimiter row inside a wide table leaves the wide one's count.
        assertThat(MarkdownFile.tableCells(header + "|-|\n" + "x\n".repeat(10))).isEqualTo(2L * 1002 + 2L * 1002 + 10L * 1002)
        // A blank line ends a table; a dash line under no pipe is a heading, not a table.
        assertThat(MarkdownFile.tableCells("|a|b|\n|-|-|\n\nz\nTitle\n---\nmore")).isEqualTo(8L)
    }

    /** commonmark-java takes a time in the square of nested images: 150 000 of them took 15 s on the JVM. */
    @Test
    fun `more brackets open at once than the cap is not rendered`() {
        val nested = "![".repeat(FileCaps.MARKDOWN_OPEN_BRACKETS + 1) + "x" + "](u)".repeat(FileCaps.MARKDOWN_OPEN_BRACKETS + 1)
        assertThat(content(nested.toByteArray()).rendering).isEqualTo(MarkdownRendering.TooManyBrackets(FileCaps.MARKDOWN_OPEN_BRACKETS))
        // A blank line ends a paragraph: its brackets are counted no more.
        assertThat(MarkdownFile.openBrackets("[[a\n[b\n\n[c] [d]")).isEqualTo(3)
        assertThat(MarkdownFile.openBrackets("]]][a]")).isEqualTo(1)
    }

    @Test
    fun `nesting is bounded, and hostile texts render in bounded time without recursion`() {
        val deep = blocks(">".repeat(100_000) + " deep")
        assertThat(deep.maxOf { it.quotes }).isAtMost(FileCaps.MARKDOWN_NESTING)
        val images = blocks("![".repeat(100) + "x" + "](u)".repeat(100)).single() as MarkdownText
        assertThat(images.runs.count { it.style == MarkdownStyle.IMAGE }).isIn(1..FileCaps.MARKDOWN_NESTING)
        val lists = blocks((0 until 500).joinToString("\n") { "  ".repeat(it) + "- item" })
        assertThat(lists.maxOf { it.lists }).isAtMost(FileCaps.MARKDOWN_NESTING)
        val size = FileCaps.MARKDOWN_RENDERED.toInt()
        val hostile = listOf(
            "*".repeat(size),
            "[".repeat(size),
            "*a ".repeat(size / 6) + " a*".repeat(size / 6),
            // Nested images, as deep as a paragraph may go, paragraph after paragraph.
            ("![".repeat(FileCaps.MARKDOWN_OPEN_BRACKETS) + "x" + "](u)".repeat(FileCaps.MARKDOWN_OPEN_BRACKETS) + "\n\n").let {
                it.repeat(size / it.length)
            },
            "`a``".repeat(size / 4),
            "<a ".repeat(size / 3),
        )
        for (text in hostile) {
            val start = System.nanoTime()
            content(text.toByteArray())
            assertWithMessage(text.take(8)).that((System.nanoTime() - start) / 1_000_000).isLessThan(HOSTILE_MILLIS)
        }
    }

    @Test
    fun `a tap opens the web and e-mail only`() {
        for (opened in listOf("https://example.org/a?b#c", "HTTP://example.org", "mailto:a@b.c")) {
            assertWithMessage(opened).that(MarkdownLinks.opened(opened)).isEqualTo(opened)
        }
        val refused = listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", "file:///data/data/x/files/vault", "content://x/y", "intent://x#Intent;end",
            "data:text/html,x", "docs/README.md", "#anchor", "//host/path", "", ":x", " https://example.org", "1http://x",
        )
        for (link in refused) assertWithMessage(link).that(MarkdownLinks.opened(link)).isNull()
    }

    private companion object {
        /** About ten times what the JVM takes: a hang is minutes. */
        const val HOSTILE_MILLIS = 10_000L
    }
}
