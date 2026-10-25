package com.readfilestech.read_files_tech.data.viewer.json

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The JSON viewer's data layer. The 2.15.3 has no Dart test of its JSON screen (`json_viewer_screen.dart`):
 * these cover what it did — a tree, its previews, a value copied, the indented text — and what it got
 * wrong (a stack overflow, numbers rounded, duplicate keys lost, a strict UTF-8 read, the exception shown).
 */
class JsonFileTest {
    private fun content(text: String, limits: JsonLimits = JsonLimits()) = content(text.toByteArray(), limits)

    private fun content(bytes: ByteArray, limits: JsonLimits = JsonLimits()) = runBlocking {
        JsonFile.of(bytes, limits = limits)
    }

    private fun tree(text: String): JsonDocument = (content(text) as JsonContent.Tree).document

    private fun refusal(text: String): JsonRefusal = (content(text) as JsonContent.Text).refusal

    @Test
    fun `values in document order, with their kinds, depths, keys and literals`() {
        val document = tree("""{"a": [1, true, null], "b": {"c": "x"}, "d": -2.5e3}""")
        assertThat(document.valueCount).isEqualTo(8)
        assertThat((0 until 8).map(document::kind)).containsExactly(
            JsonKind.OBJECT,
            JsonKind.ARRAY,
            JsonKind.NUMBER,
            JsonKind.BOOLEAN,
            JsonKind.NULL,
            JsonKind.OBJECT,
            JsonKind.STRING,
            JsonKind.NUMBER,
        ).inOrder()
        assertThat((0 until 8).map(document::depth)).containsExactly(0, 1, 2, 2, 2, 1, 2, 1).inOrder()
        assertThat((0 until 8).map { document.keyLiteral(it, 100) })
            .containsExactly(null, "\"a\"", null, null, null, "\"b\"", "\"c\"", "\"d\"").inOrder()
        assertThat(document.literal(6, 100)).isEqualTo("\"x\"")
        assertThat(document.literal(7, 100)).isEqualTo("-2.5e3")
        assertThat(document.children(0).toList()).containsExactly(1, 5, 7).inOrder()
        assertThat(document.childCount(1)).isEqualTo(3)
        assertThat(document.next(1)).isEqualTo(5)
        assertThat((0 until 8).map(document::hasNextSibling))
            .containsExactly(false, true, true, true, false, true, false, false).inOrder()
    }

    /** Dart's json.decode made a double of every long number: 10 000 digits became Infinity. */
    @Test
    fun `numbers are shown as written, whatever their length`() {
        val long = "9".repeat(10_000)
        val document = tree("[$long, 1e400, -0.0, 1E+2, 12345678901234567890]")
        assertThat(document.copyText(1, 20_000)).isEqualTo(long)
        assertThat((2..5).map { document.literal(it, 100) })
            .containsExactly("1e400", "-0.0", "1E+2", "12345678901234567890").inOrder()
    }

    /** A Dart map kept the last of two members of one name; the viewer shows the file. */
    @Test
    fun `two members of one name are both shown`() {
        val document = tree("""{"k": 1, "k": 2}""")
        assertThat(document.children(0).map { document.keyLiteral(it, 10) to document.literal(it, 10) }.toList())
            .containsExactly("\"k\"" to "1", "\"k\"" to "2").inOrder()
    }

    /** The 2.15.3 read UTF-8 strictly: PowerShell 5.1's UTF-16 and ANSI files were "JSON invalide". */
    @Test
    fun `UTF-8 with a BOM, UTF-16 both ways and Windows-1252 are read`() {
        val text = """{"clé": "été"}"""
        val forms = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE),
            text.toByteArray(charset("windows-1252")),
        )
        for (bytes in forms) {
            val document = (content(bytes) as JsonContent.Tree).document
            assertThat(document.keyLiteral(1, 10)).isEqualTo("\"clé\"")
            assertThat(document.copyText(1, 10)).isEqualTo("été")
        }
    }

    @Test
    fun `what is not JSON is shown as text, with where it stops being JSON`() {
        val cases = listOf(
            "[1, 2,]" to 7, // a trailing comma
            "[NaN]" to 2,
            """{"a": -Infinity}""" to 8,
            "['a']" to 2,
            "[1] // one" to 5, // a comment
            "[01]" to 3, // a leading zero
            """["a\x"]""" to 5, // an unknown escape
            """["\u12"]""" to 7, // a short unicode escape
            "[tru]" to 5,
            "{} {}" to 4, // a second value
            """{"a" 1}""" to 6, // no colon
            "[1" to 3, // not closed
            "" to 1,
        )
        for ((text, column) in cases) {
            assertWithMessage(text).that(refusal(text)).isEqualTo(JsonRefusal.Syntax(1, column))
        }
    }

    @Test
    fun `a control character in a string, an unclosed string, the line and column of a later line`() {
        assertThat(refusal("[\"a\tb\"]")).isEqualTo(JsonRefusal.Syntax(1, 4))
        assertThat(refusal("[\"abc")).isEqualTo(JsonRefusal.Syntax(1, 6))
        // Columns count characters, not bytes: "é" is two bytes of UTF-8, "😀" four, and two UTF-16 units.
        assertThat(refusal("[\n  \"é\",\n  x]")).isEqualTo(JsonRefusal.Syntax(3, 3))
        assertThat(refusal("[\"é😀\", x]")).isEqualTo(JsonRefusal.Syntax(1, 8))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "[\"é😀\", x]".toByteArray(Charsets.UTF_16LE)
        assertThat((content(utf16) as JsonContent.Text).refusal).isEqualTo(JsonRefusal.Syntax(1, 8))
    }

    /** Dart's recursive decoder overflowed its stack: the depth is a counter here, bounded. */
    @Test
    fun `nesting is read without recursion and bounded`() {
        val deepest = "[".repeat(FileCaps.JSON_DEPTH) + "]".repeat(FileCaps.JSON_DEPTH)
        val document = tree(deepest)
        assertThat(document.depth(document.valueCount - 1)).isEqualTo(FileCaps.JSON_DEPTH - 1)
        assertThat(refusal("[$deepest]")).isEqualTo(JsonRefusal.TooDeep(FileCaps.JSON_DEPTH))
        assertThat(refusal("[".repeat(1_000_000))).isEqualTo(JsonRefusal.TooDeep(FileCaps.JSON_DEPTH))
        assertThat(refusal("{\"a\":".repeat(1_000_000))).isEqualTo(JsonRefusal.TooDeep(FileCaps.JSON_DEPTH))
    }

    @Test
    fun `more values than the cap is shown as text`() {
        val limits = JsonLimits(values = 4)
        assertThat(content("[1, 2, 3]", limits)).isInstanceOf(JsonContent.Tree::class.java)
        assertThat((content("[1, 2, 3, 4]", limits) as JsonContent.Text).refusal).isEqualTo(JsonRefusal.TooManyValues(4))
        // The text is the file's, whole.
        assertThat((content("[1, 2, 3, 4]", limits) as JsonContent.Text).text.row(0)).isEqualTo("[1, 2, 3, 4]")
    }

    /** The 2.15.3 copied `"text"` — its quotes, without its escapes decoded. */
    @Test
    fun `a value copied is the string's characters, a number or a literal as written`() {
        val document = tree("""["a\"b\\c\/\n\té😀", 12.50, false, null]""")
        assertThat(document.copyText(1, 100)).isEqualTo("a\"b\\c/\n\té😀")
        assertThat(document.copyText(2, 100)).isEqualTo("12.50")
        assertThat(document.copyText(3, 100)).isEqualTo("false")
        assertThat(document.copyText(4, 100)).isEqualTo("null")
        // Eleven characters: the emoji is a surrogate pair.
        assertThat(document.copyText(1, 10)).isNull()
        assertThat(document.copyText(1, 11)).isNotNull()
        assertThat(tree("[\"${"x".repeat(50)}\"]").copyText(1, 49)).isNull()
        assertThat(tree("[${"9".repeat(50)}]").copyText(1, 49)).isNull()
    }

    @Test
    fun `a long value is shown cut and marked, never inside a surrogate pair`() {
        val document = tree("[\"${"a".repeat(9)}😀b\"]")
        assertThat(document.literal(1, 10)).isEqualTo("\"" + "a".repeat(9) + JsonDocument.ELLIPSIS)
        assertThat(document.literal(1, 11)).isEqualTo("\"" + "a".repeat(9) + JsonDocument.ELLIPSIS)
        assertThat(document.literal(1, 12)).isEqualTo("\"" + "a".repeat(9) + "😀" + JsonDocument.ELLIPSIS)
        assertThat(document.literal(1, 100)).isEqualTo("\"" + "a".repeat(9) + "😀b\"")
    }

    @Test
    fun `the tree opens the document's value, opens and closes a container, and closes what it showed`() {
        val document = tree("""{"a": [1, {"b": 2}], "c": 3}""")
        val tree = JsonTree(document)
        fun rows() = (0 until tree.rowCount).map(tree::value)
        assertThat(rows()).containsExactly(0, 1, 5).inOrder()
        tree.toggle(1)
        assertThat(rows()).containsExactly(0, 1, 2, 3, 5).inOrder()
        assertThat((0 until tree.rowCount).map(tree::ordinal)).containsExactly(0, 0, 0, 1, 1).inOrder()
        tree.toggle(3)
        assertThat(rows()).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
        assertThat(tree.childCount(1)).isEqualTo(2)
        // A scalar does nothing.
        tree.toggle(2)
        assertThat(tree.rowCount).isEqualTo(6)
        // Closing "a" closes the object inside it too: opened again, it shows its own children only.
        tree.toggle(1)
        assertThat(rows()).containsExactly(0, 1, 5).inOrder()
        assertThat(tree.isOpen(3)).isFalse()
        tree.toggle(1)
        assertThat(rows()).containsExactly(0, 1, 2, 3, 5).inOrder()
        tree.toggle(0)
        assertThat(rows()).containsExactly(0)
    }

    @Test
    fun `a document of one scalar is one row`() {
        val tree = JsonTree(tree("  \"only\"  "))
        assertThat(tree.rowCount).isEqualTo(1)
        tree.toggle(0)
        assertThat(tree.rowCount).isEqualTo(1)
    }

    @Test
    fun `the indented text, its commas, its empty containers and its closing lines`() {
        val pretty = JsonPretty(tree("""{"a":[1,2],"b":{},"c":"x\n","d":[[]]}"""))
        assertThat((0 until pretty.rowCount).map { pretty.line(it, 100) }).containsExactly(
            "{",
            "  \"a\": [",
            "    1,",
            "    2",
            "  ],",
            "  \"b\": {},",
            "  \"c\": \"x\\n\",",
            "  \"d\": [",
            "    []",
            "  ]",
            "}",
        ).inOrder()
    }

    @Test
    fun `the indent stops growing past its levels`() {
        val levels = JsonPretty.MAX_INDENT_LEVELS + 5
        val pretty = JsonPretty(tree("[".repeat(levels + 1) + "]".repeat(levels + 1)))
        assertThat(pretty.line(levels, 10)).isEqualTo("  ".repeat(JsonPretty.MAX_INDENT_LEVELS) + "[]")
    }
}
