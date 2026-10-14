package com.readfilestech.read_files_tech.data.viewer.text

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.ATTRIBUTE
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.BULLET
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.COMMENT
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.EMPHASIS
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.KEYWORD
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.LINK
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.LITERAL
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.META
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.NUMBER
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.PROPERTY
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.REGEXP
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.SECTION
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.SELECTOR
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.STRING
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.STRONG
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.TAG
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle.VARIABLE
import org.junit.jupiter.api.Test

/** Each language's tokens, and what a state carries from a row to the next — or does not. */
class CodeTokenizerTest {
    /** Each row's spans, as (text, style); a row ends its line unless [cut] says it is a piece of a longer one. */
    private fun colour(language: CodeLanguage, vararg rows: String, cut: Set<Int> = emptySet()): List<List<Pair<String, CodeStyle>>> {
        val tokenizer = language.tokenizer()
        var state = 0
        return rows.mapIndexed { index, row ->
            val spans = mutableListOf<CodeSpan>()
            state = tokenizer.tokenize(row, state, index !in cut, spans)
            spans.map { row.substring(it.start, it.end) to it.style }
        }
    }

    @Test
    fun `JavaScript - keywords, strings, comments, numbers, and a slash that divides`() {
        val rows = colour(CodeLanguage.JAVASCRIPT, "var s = \"a/*b\"; // c", "x = a / b / 2;", "return null")
        assertThat(rows[0]).containsExactly("var" to KEYWORD, "\"a/*b\"" to STRING, "// c" to COMMENT).inOrder()
        assertThat(rows[1]).containsExactly("2" to NUMBER)
        assertThat(rows[2]).containsExactly("return" to KEYWORD, "null" to LITERAL).inOrder()
    }

    // A comment opener inside a regular expression opens no comment, else the rest of the file is coloured as one.
    @Test
    fun `JavaScript - a regular expression where a value is expected, its slash-star no comment`() {
        val rows = colour(CodeLanguage.JAVASCRIPT, "if (/\\/*x[/]/g.test(s)) n = 1", "var y")
        assertThat(rows[0]).containsExactly("if" to KEYWORD, "/\\/*x[/]/g" to REGEXP, "1" to NUMBER).inOrder()
        assertThat(rows[1]).containsExactly("var" to KEYWORD)
    }

    @Test
    fun `JavaScript - a block comment and a template go on over lines, a quoted string ends with its line`() {
        val rows = colour(CodeLanguage.JAVASCRIPT, "a /* b", "c */ d = `e", "f ${'$'}{g} `;", "s = 'h", "var i")
        assertThat(rows[0]).containsExactly("/* b" to COMMENT)
        assertThat(rows[1]).containsExactly("c */" to COMMENT, "`e" to STRING).inOrder()
        assertThat(rows[2]).containsExactly("f ${'$'}{g} `" to STRING)
        assertThat(rows[3]).containsExactly("'h" to STRING)
        assertThat(rows[4]).containsExactly("var" to KEYWORD)
    }

    /** A line cut at ROW_BYTES goes on in the next row: its string and its line comment with it. */
    @Test
    fun `a string and a line comment go on into the next piece of a cut line, not past its end`() {
        val rows = colour(CodeLanguage.JAVASCRIPT, "s = 'ab", "c' + 1 // d", "e", "var f", cut = setOf(0, 1))
        assertThat(rows[1]).containsExactly("c'" to STRING, "1" to NUMBER, "// d" to COMMENT).inOrder()
        assertThat(rows[2]).containsExactly("e" to COMMENT)
        assertThat(rows[3]).containsExactly("var" to KEYWORD)
    }

    @Test
    fun `Dart - comments nest, triple quotes go on over lines, a raw string escapes nothing, annotations`() {
        val rows = colour(CodeLanguage.DART, "/* a /* b */ c", "d */ var s = '''e", "f''' + r'\\'; @override int g = 0x1F;")
        assertThat(rows[0]).containsExactly("/* a /* b */ c" to COMMENT)
        assertThat(rows[1]).containsExactly("d */" to COMMENT, "var" to KEYWORD, "'''e" to STRING).inOrder()
        assertThat(rows[2]).containsExactly(
            "f'''" to STRING,
            "r'\\'" to STRING,
            "@override" to META,
            "int" to CodeStyle.BUILT_IN,
            "0x1F" to NUMBER,
        ).inOrder()
    }

    /** The 2.15.3 coloured all of a PHP file as code — the words of its HTML too. */
    @Test
    fun `PHP - HTML around the code, a line comment ended by the closing tag`() {
        val rows = colour(CodeLanguage.PHP, "<p class=\"x\"><?php echo \$name; // c ?></p>", "if the text says if")
        assertThat(rows[0]).containsExactly(
            "p" to TAG, "class" to ATTRIBUTE, "\"x\"" to STRING, "<?php" to META, "echo" to KEYWORD, "\$name" to VARIABLE,
            "// c " to COMMENT, "?>" to META, "p" to TAG,
        ).inOrder()
        assertThat(rows[1]).isEmpty()
    }

    @Test
    fun `PHP - code inside an attribute's value, which goes on after it`() {
        val rows = colour(CodeLanguage.PHP, "<a href=\"<?= \$u ?>/x\">")
        assertThat(rows[0]).containsExactly(
            "a" to TAG,
            "href" to ATTRIBUTE,
            "\"" to STRING,
            "<?=" to META,
            "\$u" to VARIABLE,
            "?>" to META,
            "/x\"" to STRING,
        ).inOrder()
    }

    @Test
    fun `PHP - an attribute is no comment, words in any case, strings over lines`() {
        val rows = colour(CodeLanguage.PHP, "<?PHP #[Attr] \$a = TRUE; # note", "\$s = 'a", "b'; ?>")
        assertThat(rows[0]).containsExactly("<?PHP" to META, "\$a" to VARIABLE, "TRUE" to LITERAL, "# note" to COMMENT).inOrder()
        assertThat(rows[1]).containsExactly("\$s" to VARIABLE, "'a" to STRING).inOrder()
        assertThat(rows[2]).containsExactly("b'" to STRING, "?>" to META).inOrder()
    }

    @Test
    fun `XML - declaration, comment and value over lines, CDATA holding a less-than`() {
        val rows = colour(
            CodeLanguage.XML,
            "<?xml version=\"1.0\"?><!-- a",
            "b --><r a='1' b=\"x",
            "y\">t<![CDATA[a<b]]></r>",
        )
        assertThat(rows[0]).containsExactly("<?xml version=\"1.0\"?>" to META, "<!-- a" to COMMENT).inOrder()
        assertThat(
            rows[1]
        ).containsExactly("b -->" to COMMENT, "r" to TAG, "a" to ATTRIBUTE, "'1'" to STRING, "b" to ATTRIBUTE, "\"x" to STRING)
            .inOrder()
        assertThat(rows[2]).containsExactly("y\"" to STRING, "<![CDATA[" to META, "]]>" to META, "r" to TAG).inOrder()
    }

    @Test
    fun `CSS - an at-rule, a nested rule's selectors, its declarations, a value over lines`() {
        val rows = colour(
            CodeLanguage.CSS,
            "@media (max-width: 600px) {",
            "  a.b:hover, #c { color: #fff; margin:",
            "    1px 2em; }",
            "}",
        )
        assertThat(rows[0]).containsExactly("@media" to KEYWORD, "600px" to NUMBER).inOrder()
        assertThat(rows[1]).containsExactly(
            "a" to SELECTOR,
            ".b" to SELECTOR,
            ":hover" to SELECTOR,
            "#c" to SELECTOR,
            "color" to PROPERTY,
            "#fff" to NUMBER,
            "margin" to PROPERTY,
        ).inOrder()
        assertThat(rows[2]).containsExactly("1px" to NUMBER, "2em" to NUMBER).inOrder()
    }

    @Test
    fun `CSS - a comment over lines, an unquoted url whose semicolon ends nothing`() {
        val rows = colour(CodeLanguage.CSS, "/* a", "*/ x { background: url(data:image/png;base64,AA) !important }", "y {}")
        assertThat(rows[0]).containsExactly("/* a" to COMMENT)
        assertThat(rows[1]).containsExactly(
            "*/" to COMMENT,
            "x" to SELECTOR,
            "background" to PROPERTY,
            "data:image/png;base64,AA" to STRING,
            "!important" to KEYWORD,
        ).inOrder()
        assertThat(rows[2]).containsExactly("y" to SELECTOR)
    }

    @Test
    fun `Markdown - headings, lists, fenced code, quotes, links, rules`() {
        val rows = colour(
            CodeLanguage.MARKDOWN,
            "# Title",
            "- item *em* and **strong**, snake_case_word",
            "```kotlin",
            "val a = `x` # no heading",
            "```",
            "> quote",
            "[text](http://x) `code`",
            "---",
        )
        assertThat(rows[0]).containsExactly("# Title" to SECTION)
        assertThat(rows[1]).containsExactly("- " to BULLET, "*em*" to EMPHASIS, "**strong**" to STRONG).inOrder()
        assertThat(rows[2]).containsExactly("```kotlin" to META)
        assertThat(rows[3]).containsExactly("val a = `x` # no heading" to STRING)
        assertThat(rows[4]).containsExactly("```" to META)
        assertThat(rows[5]).containsExactly("> quote" to COMMENT)
        assertThat(rows[6]).containsExactly("[text]" to STRING, "(http://x)" to LINK, "`code`" to STRING).inOrder()
        assertThat(rows[7]).containsExactly("---" to META)
    }

    @Test
    fun `Markdown - a shorter fence closes nothing, a cut line starts no block`() {
        val rows = colour(CodeLanguage.MARKDOWN, "~~~~", "~~~", "~~~~~", "text", "# not a heading", cut = setOf(3))
        assertThat(rows[1]).containsExactly("~~~" to STRING)
        assertThat(rows[2]).containsExactly("~~~~~" to META)
        assertThat(rows[4]).isEmpty()
    }

    /** What the scan computes without spans must be what the screen computes with them. */
    @Test
    fun `a row leaves the same state whether its spans are kept or not`() {
        val text = "a /* b\n<?php \$x = '1\n<!-- c\n```\n{ d: e\n`f\n'''g\n"
        for (language in CodeLanguage.entries) {
            val with = language.tokenizer()
            val without = language.tokenizer()
            var kept = 0
            var dropped = 0
            text.lines().forEach { row ->
                kept = with.tokenize(row, kept, true, mutableListOf())
                dropped = without.tokenize(row, dropped, true, null)
                assertThat(dropped).isEqualTo(kept)
            }
        }
    }

    @Test
    fun `the text viewer colours the five extensions the Flutter app coloured, and nothing else`() {
        assertThat(listOf("css", "js", "php", "xml", "dart").map(CodeLanguage::of)).containsExactly(
            CodeLanguage.CSS,
            CodeLanguage.JAVASCRIPT,
            CodeLanguage.PHP,
            CodeLanguage.XML,
            CodeLanguage.DART,
        ).inOrder()
        assertThat(listOf("txt", "log", "yaml", "yml", "ini", "conf", "md", "html").mapNotNull(CodeLanguage::of)).isEmpty()
    }
}
