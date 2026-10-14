package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.json.JsonRefusal
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

/**
 * The format tool. The oracles are not the code: org.json, an independent parser, reads every JSON case
 * formatted to the value of its input; the string literals of the CSS and JS cases, listed by hand in the
 * manifest, are found in the output byte for byte. Each output is written to `build/oracles/format/`,
 * where `tools/oracles/text_tools_oracle.py check` reads it again with Python's json and runs the JS
 * program, minified, under node.
 */
class CodeFormatTest {
    private fun prepared(action: FormatAction, bytes: ByteArray): Formattable =
        runBlocking { CodeFormat.prepare(action, bytes, bytes.size) }

    private fun format(action: FormatAction, bytes: ByteArray): String {
        val ready = prepared(action, bytes) as? Formattable.Ready ?: error("refused: $action")
        return StringBuilder().also { out -> ready.write(out) {} }.toString()
    }

    private fun format(action: FormatAction, text: String) = format(action, text.encodeToByteArray())

    private fun refusal(text: String): JsonRefusal =
        (prepared(FormatAction.JSON_MINIFIED, text.encodeToByteArray()) as Formattable.NotJson).refusal

    @Test
    fun `each case of the corpus keeps its value, or its literals, and is written for the Python oracle`() {
        val folder = File("build/oracles/format").apply { mkdirs() }
        val cases = JSONObject(resource("/tools/format/manifest.json")).getJSONArray("cases").objects()
        for (case in cases) {
            val stream = requireNotNull(javaClass.getResourceAsStream("/tools/format/" + case.getString("file")))
            val bytes = stream.use { it.readBytes() }
            val name = case.getString("name")
            when (case.getString("kind")) {
                "json" -> {
                    val indented = format(FormatAction.JSON_INDENTED, bytes)
                    val minified = format(FormatAction.JSON_MINIFIED, bytes)
                    File(folder, "$name.indented.json").writeText(indented)
                    File(folder, "$name.minified.json").writeText(minified)
                    // Minifying the indented form gives the minified one: only spaces differ.
                    assertThat(format(FormatAction.JSON_MINIFIED, indented)).isEqualTo(minified)
                    if (case.getBoolean("orgJson")) {
                        val expected = JSONTokener(ToolTexts.decode(bytes, bytes.size)).nextValue()
                        assertSameValue(JSONTokener(indented).nextValue(), expected)
                        assertSameValue(JSONTokener(minified).nextValue(), expected)
                    }
                }
                else -> {
                    val action = if (case.getString("kind") == "css") FormatAction.CSS_MINIFIED else FormatAction.JS_MINIFIED
                    val minified = format(action, bytes)
                    File(folder, "$name.min.${action.extension}").writeText(minified)
                    for (literal in case.getJSONArray("literals").strings()) assertThat(minified).contains(literal)
                    assertThat(minified.length).isLessThan(bytes.size)
                }
            }
        }
    }

    /** The same value, member by member: org.json's own API (Android's copy has no `similar`). */
    private fun assertSameValue(actual: Any?, expected: Any?) {
        when (expected) {
            is JSONObject -> {
                actual as JSONObject
                assertThat(actual.keys().asSequence().toSet()).isEqualTo(expected.keys().asSequence().toSet())
                for (key in expected.keys()) assertSameValue(actual.get(key), expected.get(key))
            }
            is JSONArray -> {
                actual as JSONArray
                assertThat(actual.length()).isEqualTo(expected.length())
                for (i in 0 until expected.length()) assertSameValue(actual.get(i), expected.get(i))
            }
            else -> assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun `JSON indented by two spaces a level, members as written`() {
        assertThat(format(FormatAction.JSON_INDENTED, """{"b":1,"a":[true,null,{}],"c":{"d":"x y"}}"""))
            .isEqualTo("{\n  \"b\": 1,\n  \"a\": [\n    true,\n    null,\n    {}\n  ],\n  \"c\": {\n    \"d\": \"x y\"\n  }\n}")
        // A number stays as written: the 2.15.3 printed its double, 1e400 as Infinity.
        assertThat(format(FormatAction.JSON_MINIFIED, " [ 1E400 , 0.10 , 12345678901234567890123 ] "))
            .isEqualTo("[1E400,0.10,12345678901234567890123]")
        // Of two members of one name, the 2.15.3 kept the last only.
        assertThat(format(FormatAction.JSON_MINIFIED, """{ "k" : 1 , "k" : 2 }""")).isEqualTo("""{"k":1,"k":2}""")
    }

    @Test
    fun `not JSON, too deep, cut short, binary or empty - refused, saying where`() {
        assertThat(refusal("{\n  \"a\": 1,\n  oops\n}")).isEqualTo(JsonRefusal.Syntax(3, 3))
        assertThat(refusal("[".repeat(FileCaps.JSON_DEPTH + 1) + "]".repeat(FileCaps.JSON_DEPTH + 1)))
            .isEqualTo(JsonRefusal.TooDeep(FileCaps.JSON_DEPTH))
        assertThat(refusal("""{"cut": [1, 2""")).isInstanceOf(JsonRefusal.Syntax::class.java)
        assertThat(refusal("")).isInstanceOf(JsonRefusal.Syntax::class.java)
        val binary = ByteArray(4096) { (it * 31 + 7).toByte() }
        assertThat(prepared(FormatAction.JSON_INDENTED, binary)).isInstanceOf(Formattable.NotJson::class.java)
        // A binary file "minified" as CSS or JS: written, never a crash.
        assertThat(format(FormatAction.CSS_MINIFIED, binary)).isNotEmpty()
        assertThat(format(FormatAction.JS_MINIFIED, binary)).isNotEmpty()
    }

    @Test
    fun `a large JSON is written whole, a line a value`() {
        val values = 200_000
        val text = (0 until values).joinToString(",", "[", "]")
        val indented = format(FormatAction.JSON_INDENTED, text)
        assertThat(indented.count { it == '\n' }).isEqualTo(values + 1)
        assertThat(format(FormatAction.JSON_MINIFIED, indented)).isEqualTo(text)
    }

    @Test
    fun `JS keeps a line break where automatic semicolons need one, and the spaces tokens need`() {
        assertThat(format(FormatAction.JS_MINIFIED, "function f() {\n  return\n    42;\n}")).isEqualTo("function f(){\nreturn\n42;\n}")
        assertThat(format(FormatAction.JS_MINIFIED, "x = a - -b + +c")).isEqualTo("x=a- -b+ +c")
        assertThat(format(FormatAction.JS_MINIFIED, "y = 1 .toString()")).isEqualTo("y=1 .toString()")
        assertThat(format(FormatAction.JS_MINIFIED, "const s = \"a // b /* c */\"; // gone")).isEqualTo("const s=\"a // b /* c */\";")
        assertThat(format(FormatAction.JS_MINIFIED, "q = x / 2 / y; r = /[/]x/g.test(z)")).isEqualTo("q=x/2/y;r=/[/]x/g.test(z)")
        assertThat(format(FormatAction.JS_MINIFIED, "s = /a  b/.source")).isEqualTo("s=/a  b/.source")
        assertThat(format(FormatAction.JS_MINIFIED, "t = typeof /a  b/")).isEqualTo("t=typeof/a  b/")
        assertThat(format(FormatAction.JS_MINIFIED, "t = `a \${ b } // c`")).isEqualTo("t=`a \${b} // c`")
    }

    // The space before a colon stays everywhere: in a selector, `div :first-child` is not `div:first-child`.
    @Test
    fun `CSS keeps calc's spaces and a descendant's, drops the rest`() {
        assertThat(format(FormatAction.CSS_MINIFIED, "a > b , c {\n  width : calc( 1px + 2px ) ;\n  margin: 0 -1px ;\n}"))
            .isEqualTo("a > b,c{width :calc(1px + 2px);margin:0 -1px}")
        assertThat(format(FormatAction.CSS_MINIFIED, "div :first-child { color : red }")).isEqualTo("div :first-child{color :red}")
        assertThat(format(FormatAction.CSS_MINIFIED, "a { b: url( x.png ) } /* end */")).isEqualTo("a{b:url( x.png )}")
        assertThat(format(FormatAction.CSS_MINIFIED, "@import \"x.css\";")).isEqualTo("@import \"x.css\";")
    }

    @Test
    fun `the preview keeps the beginning, counts the whole, never ends on half a pair`() {
        val preview = TextPreview(5)
        preview.append("abcd").append(String(Character.toChars(0x1F600))).append('z')
        assertThat(preview.length).isEqualTo(7)
        assertThat(preview.isWhole).isFalse()
        assertThat(preview.text).isEqualTo("abcd")
        val whole = TextPreview(10).apply { append("abc") }
        assertThat(whole.isWhole).isTrue()
        assertThat(whole.text).isEqualTo("abc")
    }

    @Test
    fun `a checkpoint that throws stops the writing`() {
        val values = (0 until 300_000).joinToString(",", "[", "]").encodeToByteArray()
        val ready = prepared(FormatAction.JSON_INDENTED, values) as Formattable.Ready
        var checks = 0
        assertThrows<CancellationException> {
            ready.write(StringBuilder()) { if (++checks == 2) throw CancellationException("left") }
        }
        val css = prepared(FormatAction.CSS_MINIFIED, "a{b:c}".repeat(50_000).encodeToByteArray()) as Formattable.Ready
        assertThrows<CancellationException> { css.write(StringBuilder()) { throw CancellationException("left") } }
    }

    @Test
    fun `a file over the text viewer's cap is refused before a byte is read`(@TempDir dir: File) {
        val file = File(dir, "big.json")
        RandomAccessFile(file, "rw").use { it.setLength(FileCaps.TEXT_VIEWER + 1) }
        assertThrows<FileTooLargeException> { runBlocking { ToolTexts.read(file) } }
        val small = File(dir, "small.json").apply { writeText("[1]") }
        val (bytes, size) = runBlocking { ToolTexts.read(small) }
        assertThat(String(bytes, 0, size)).isEqualTo("[1]")
    }
}
