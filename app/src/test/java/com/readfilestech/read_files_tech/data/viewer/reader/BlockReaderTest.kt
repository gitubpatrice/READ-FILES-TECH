package com.readfilestech.read_files_tech.data.viewer.reader

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.reader.BlockKind.HEADING_1
import com.readfilestech.read_files_tech.data.viewer.reader.BlockKind.HEADING_2
import com.readfilestech.read_files_tech.data.viewer.reader.BlockKind.HEADING_3
import com.readfilestech.read_files_tech.data.viewer.reader.BlockKind.ITEM
import com.readfilestech.read_files_tech.data.viewer.reader.BlockKind.PARAGRAPH
import com.readfilestech.read_files_tech.data.viewer.reader.BlockKind.QUOTE
import com.readfilestech.read_files_tech.data.viewer.sheet.TextBudget
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.StringReader

/**
 * The reader's blocks, from HTML and XHTML: the 2.15.3's rules (`ReaderService._walk`, which had no Dart
 * test), the defects it had fixed, and what a hostile chapter cannot make it do.
 */
class BlockReaderTest {
    private fun blocks(html: String, mode: ReadingMode = ReadingMode.CHAPTER, budget: Long = 10_000_000): List<Pair<BlockKind, String>> =
        runBlocking {
            val read = BlockReader(mode, TextBudget(budget)).read(HtmlTokenizer(StringReader(html)))
            (0 until read.size).map { read.kind(it) to read.text(it) }
        }

    private fun texts(html: String, mode: ReadingMode = ReadingMode.CHAPTER) = blocks(html, mode).map { it.second }

    @Test
    fun `headings, paragraphs, quotes and list items are blocks of their whole text, white space collapsed`() {
        val html = "<html><head><title>Not shown</title></head><body><h1> The  title </h1><h2>Sub</h2><h3>Three</h3><h4>Four</h4>" +
            "<p>A\n   paragraph <em>with</em> <b>words</b>.</p>" +
            "<blockquote>Quoted</blockquote><ul><li>one</li><li>two</li></ul></body></html>"
        assertThat(blocks(html)).containsExactly(
            HEADING_1 to "The title",
            HEADING_2 to "Sub",
            HEADING_3 to "Three",
            HEADING_3 to "Four",
            PARAGRAPH to "A paragraph with words.",
            QUOTE to "Quoted",
            ITEM to "one",
            ITEM to "two",
        ).inOrder()
    }

    /** The 2.15.3 kept only h1-h4, p, blockquote and li: a `<div>`'s text, a cell's, an `<h5>`'s were lost. */
    @Test
    fun `text outside those elements is a paragraph, never dropped`() {
        val html = "<body><div>Loose <b>bold</b> text</div><table><tr><td>cell one</td><td>cell two</td></tr></table>" +
            "<h5>Five</h5>after<div class=\"h2\">by Edgar Allan Poe</div></body>"
        assertThat(blocks(html)).containsExactly(
            PARAGRAPH to "Loose bold text",
            PARAGRAPH to "cell one",
            PARAGRAPH to "cell two",
            HEADING_3 to "Five",
            PARAGRAPH to "after",
            PARAGRAPH to "by Edgar Allan Poe",
        ).inOrder()
    }

    /** The 2.15.3 took an element's text as its nodes' glued together: `one<br/>two` read "onetwo". */
    @Test
    fun `a line break and the blocks nested in one never glue words`() {
        assertThat(texts("<p>one<br/>two</p>")).containsExactly("one\ntwo")
        assertThat(texts("<blockquote><p>first</p><p>second</p></blockquote>")).containsExactly("first\nsecond")
        assertThat(texts("<ul><li>item<ul><li>sub-item</li></ul></li></ul>")).containsExactly("item\nsub-item")
        // A poem: its stanzas apart, a line break at a block's edge dropped.
        assertThat(texts("<div class=\"poem\"><br/>line one<br/>line two<br/><br/>line three<br/></div>"))
            .containsExactly("line one\nline two\n\nline three")
    }

    @Test
    fun `a preformatted block keeps its lines and spaces`() {
        assertThat(texts("<pre>\n  indented\n    more\n</pre><p>after</p>")).containsExactly("  indented\n    more", "after").inOrder()
    }

    @Test
    fun `scripts, styles, comments and declarations are never text, whatever they hold`() {
        val html = "<p>a<script>if (x < y && z) { document.write(\"</p><p>evil\") }</script>b</p>" +
            "<style>p { color: red }</style><!-- <p>hidden</p> --><?php echo 'x'; ?>" +
            "<p><![CDATA[x < y ]] ]]></p><SCRIPT type=text/javascript>alert(1)</SCRIPT ><p>c</p><!--><p>d</p><!---><p>e</p>"
        assertThat(texts(html)).containsExactly("ab", "x < y ]]", "c", "d", "e").inOrder()
    }

    /** The 2.15.3 dropped a page's clutter from a book's chapters too: an EPUB 3 chapter's title in `<header>` was lost. */
    @Test
    fun `a book's chapter keeps its header and notes, a page in reading mode drops its clutter`() {
        val html = "<body><header><h1>Chapter 1</h1></header><nav>Menu</nav><p>Text</p>" +
            "<aside epub:type=\"footnote\">A note</aside><footer>Foot</footer><form>Search</form></body>"
        assertThat(texts(html, ReadingMode.CHAPTER)).containsExactly("Chapter 1", "Menu", "Text", "A note", "Foot").inOrder()
        assertThat(texts(html, ReadingMode.PAGE)).containsExactly("Text")
    }

    @Test
    fun `a page in reading mode is its first article, else its first main, else its body`() {
        val article = "<body><p>before</p><main><article><h2>In</h2><p>it</p></article><p>main only</p></main><p>after</p></body>"
        assertThat(texts(article, ReadingMode.PAGE)).containsExactly("In", "it").inOrder()
        assertThat(texts("<body><p>before</p><main><p>main</p></main><p>after</p></body>", ReadingMode.PAGE)).containsExactly("main")
        // An empty article is no content: the main is read.
        assertThat(texts("<body><article></article><main><p>main</p></main></body>", ReadingMode.PAGE)).containsExactly("main")
        assertThat(texts("<body><p>body</p></body>", ReadingMode.PAGE)).containsExactly("body")
        // A book's chapter is read whole.
        assertThat(texts(article)).containsExactly("before", "In", "it", "main only", "after").inOrder()
    }

    @Test
    fun `character references are decoded, by name from XHTML's set, by number as the HTML standard reads them`() {
        val html = "<p>caf&eacute; M.&nbsp;Dupont &mdash; &#233;&#xE9;&#XE9; &#146;&#150; &hellip;&Eacute;t&eacute;&euro;&apos;</p>"
        assertThat(texts(html)).containsExactly("café M.${NO_BREAK_SPACE}Dupont — ééé ’– …Été€'")
        assertThat(HtmlEntities.size).isEqualTo(XHTML_ENTITIES)
    }

    @Test
    fun `what names no character stays as it is written`() {
        val html = "<p>&amp;nbsp; &unknown; &copy no semicolon &#; &#x; &#xZZ; AT&T &</p><p>&#x110000;&#0;&#xD800;</p>"
        assertThat(texts(html)).containsExactly("&nbsp; &unknown; &copy no semicolon &#; &#x; &#xZZ; AT&T &", "���").inOrder()
    }

    /** What a "billion laughs" declares is never defined; the DTD a chapter names is never read. */
    @Test
    fun `a DOCTYPE is passed over, its entities never defined`() {
        val html = "<?xml version=\"1.0\" encoding=\"utf-8\"?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" " +
            "\"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\" [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;\"><!ENTITY x SYSTEM " +
            "\"file:///etc/passwd\">]><html><body><p>&lol2;&x;</p><p>&eacute;t&eacute;</p></body></html>"
        assertThat(texts(html)).containsExactly("&lol2;&x;", "été").inOrder()
    }

    @Test
    fun `HTML left open is closed as a browser closes it`() {
        assertThat(blocks("<p>one<p>two<ul><li>a<li>b</ul><dl><dt>term<dd>definition</dl><p>three<div>four</div>")).containsExactly(
            PARAGRAPH to "one",
            PARAGRAPH to "two",
            ITEM to "a",
            ITEM to "b",
            PARAGRAPH to "term",
            PARAGRAPH to "definition",
            PARAGRAPH to "three",
            PARAGRAPH to "four",
        ).inOrder()
        // A head never closed ends where the body's content starts; a stray end tag closes nothing.
        assertThat(texts("<html><head><title>T</title><meta charset=utf-8><p>body</p></span></div><p>more")).containsExactly("body", "more")
    }

    /** A browser flattens what is nested too deep; the 2.15.3's html parser built the whole tree. */
    @Test
    @Timeout(TIMEOUT_SECONDS)
    fun `a million nested elements are read in linear time, their text kept`() {
        val million = 1_000_000
        val deep = "<body>" + "<div>".repeat(million) + "<p>deep</p>" + "</div>".repeat(million) + "<p>after</p></body>"
        assertThat(texts(deep)).containsExactly("deep", "after").inOrder()
        val unclosed = "<p>" + "<b>".repeat(million) + "bold</p><p>next</p>"
        assertThat(texts(unclosed)).containsExactly("bold", "next").inOrder()
    }

    @Test
    fun `an endless tag, attribute or comment keeps nothing of itself`() {
        val long = "x".repeat(HtmlTokenizer.MAX_VALUE * 4)
        val html = "<p>a</p><a href=\"$long\" title='$long' $long>b</a><!--$long--><$long>c"
        assertThat(texts(html)).containsExactly("a", "bc").inOrder()
        val tokens = HtmlTokenizer(StringReader("<a href=\"$long\" id='short'>"), wanted = setOf("href", "id"))
        assertThat(runBlocking { tokens.next() }).isEqualTo(HtmlTokenizer.Token.START)
        assertThat(tokens.attribute("href")).isNull()
        assertThat(tokens.attribute("id")).isEqualTo("short")
    }

    @Test
    fun `a less-than sign that opens no tag is text`() {
        assertThat(texts("<p>a < b, c <3 and <= d</p>")).containsExactly("a < b, c <3 and <= d")
    }

    @Test
    fun `what a chapter keeps is spent from its budget, each block costing more than its text`() {
        val paragraphs = "<p>abc</p>".repeat(10)
        val needed = 10L * (3 + BlockReader.BLOCK_CHARGE)
        fun read(budget: Long): ReaderBlocks {
            val tokens = HtmlTokenizer(StringReader(paragraphs))
            return runBlocking { BlockReader(ReadingMode.CHAPTER, TextBudget(budget)).read(tokens) }
        }
        assertThat(read(needed).size).isEqualTo(10)
        assertThrows<FileTooLargeException> { read(needed - 1) }
        // One endless paragraph is refused as it grows, not once it is whole.
        assertThrows<FileTooLargeException> { blocks("<p>" + "a".repeat(2_000), budget = 1_000) }
        // White space and empty elements cost nothing.
        assertThat(blocks("<p></p><div>   </div>".repeat(1_000), budget = 1)).isEmpty()
    }

    private companion object {
        /** HTML 4.01's 252 named references, and XHTML's `apos`. */
        const val XHTML_ENTITIES = 253
        const val TIMEOUT_SECONDS = 30L
        const val NO_BREAK_SPACE = '\u00A0'
    }
}
