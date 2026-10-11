package com.readfilestech.read_files_tech.data.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.Base64

class FlutterPrefsFormatTest {

    // ---------------------------------------------------------------------------------------------
    // Lists, written in the 2.4.27 JSON form — compared with what Dart itself writes.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("dartOracle")
    fun `a list is encoded exactly as Dart's jsonEncode writes it`(case: OracleCase) {
        val expected = String(Base64.getDecoder().decode(case.dartOutputBase64), Charsets.UTF_8)
        assertThat(FlutterPrefsFormat.dartJsonEncode(case.input)).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("dartOracle")
    fun `a list written by the port reads back identical`(case: OracleCase) {
        val stored = FlutterPrefsFormat.encodeStringList(case.input)
        assertThat(stored).startsWith(FlutterPrefsFormat.JSON_LIST_PREFIX)
        assertThat(FlutterPrefsFormat.readStringList(stored)).isEqualTo(PrefRead.Present(case.input))
    }

    @ParameterizedTest
    @MethodSource("dartOracle")
    fun `a list written by Dart reads back as its input`(case: OracleCase) {
        val dartJson = String(Base64.getDecoder().decode(case.dartOutputBase64), Charsets.UTF_8)
        val stored = FlutterPrefsFormat.JSON_LIST_PREFIX + dartJson
        assertThat(FlutterPrefsFormat.readStringList(stored)).isEqualTo(PrefRead.Present(case.input))
    }

    // ---------------------------------------------------------------------------------------------
    // Reading a stored list: accepted exactly when Dart's jsonDecode + cast<String> accepted it.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("dartDecodeOracle")
    fun `a stored list is read exactly when the Flutter app could read it`(case: DecodeCase) {
        val read = FlutterPrefsFormat.readStringList(FlutterPrefsFormat.JSON_LIST_PREFIX + case.json)
        if (case.expected == null) {
            assertThat(read).isInstanceOf(PrefRead.Unreadable::class.java)
        } else {
            assertThat(read).isEqualTo(PrefRead.Present(case.expected))
        }
    }

    @Test
    fun `the oracle covers both verdicts and the escapes it is there for`() {
        val cases = dartDecodeOracle()
        assertThat(cases.count { it.expected == null }).isAtLeast(15)
        assertThat(cases.count { it.expected != null }).isAtLeast(10)
        // An escaped lone surrogate is kept as it is: the reason the decoder is not run on UTF-8 bytes.
        assertThat(cases.mapNotNull { it.expected }.flatten()).contains(c(0xD800))
    }

    // ---------------------------------------------------------------------------------------------
    // Forms no Read Files Tech ever wrote: unreadable, never deserialised (docs D-019).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a Java-serialised list is unreadable and never deserialised`() {
        val stored = FlutterPrefsFormat.LIST_PREFIX + androidBase64(serialise(arrayListOf("a", "b")))
        val read = FlutterPrefsFormat.readStringList(stored)
        assertThat(read).isInstanceOf(PrefRead.Unreadable::class.java)
        assertThat((read as PrefRead.Unreadable).reason).contains("Java-serialised")
    }

    @Test
    fun `a serialised list declaring two billion elements is unreadable, with no allocation`() {
        // The stream of the external review: java.util.ArrayList, size 0x7fffffff. Deserialising it
        // allocates the array before any check — an OutOfMemoryError at startup.
        val hex = "aced0005737200136a6176612e7574696c2e41727261794c6973747881d21d99c7619d03000149000473697a6578707fffffff77040000000078"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val read = FlutterPrefsFormat.readStringList(FlutterPrefsFormat.LIST_PREFIX + androidBase64(bytes))
        assertThat(read).isInstanceOf(PrefRead.Unreadable::class.java)
    }

    @Test
    fun `a string set of the plugin's first versions is unreadable`() {
        assertThat(FlutterPrefsFormat.readStringList(setOf("a"))).isInstanceOf(PrefRead.Unreadable::class.java)
    }

    // ---------------------------------------------------------------------------------------------
    // Unreadable is never absent (docs D-019).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `absent is absent, for every type`() {
        assertThat(FlutterPrefsFormat.readLong(null)).isEqualTo(PrefRead.Absent)
        assertThat(FlutterPrefsFormat.readBoolean(null)).isEqualTo(PrefRead.Absent)
        assertThat(FlutterPrefsFormat.readString(null)).isEqualTo(PrefRead.Absent)
        assertThat(FlutterPrefsFormat.readStringList(null)).isEqualTo(PrefRead.Absent)
    }

    @Test
    fun `broken lists are unreadable, never an empty list`() {
        val broken = listOf(
            FlutterPrefsFormat.JSON_LIST_PREFIX + "[\"a\",",
            FlutterPrefsFormat.JSON_LIST_PREFIX + "[\"a\",1]",
            FlutterPrefsFormat.JSON_LIST_PREFIX + "[null]",
            FlutterPrefsFormat.LIST_PREFIX + "%%%not base64%%%",
            "just a string",
            setOf("a", 1),
            42L,
        )
        broken.forEach { raw ->
            assertThat(FlutterPrefsFormat.readStringList(raw)).isInstanceOf(PrefRead.Unreadable::class.java)
        }
    }

    @Test
    fun `an integer is read from the Long the plugin writes, and from an Int`() {
        assertThat(FlutterPrefsFormat.readLong(3_000_000_000L)).isEqualTo(PrefRead.Present(3_000_000_000L))
        assertThat(FlutterPrefsFormat.readLong(4)).isEqualTo(PrefRead.Present(4L))
    }

    @Test
    fun `an integer stored as something else is unreadable, not zero`() {
        assertThat(FlutterPrefsFormat.readLong("4")).isInstanceOf(PrefRead.Unreadable::class.java)
        assertThat(FlutterPrefsFormat.readLong(true)).isInstanceOf(PrefRead.Unreadable::class.java)
    }

    @Test
    fun `a boolean stored as something else is unreadable`() {
        assertThat(FlutterPrefsFormat.readBoolean("true")).isInstanceOf(PrefRead.Unreadable::class.java)
    }

    @Test
    fun `a list read as a string is unreadable, as Dart would not return it as a String either`() {
        val list = FlutterPrefsFormat.encodeStringList(listOf("a"))
        assertThat(FlutterPrefsFormat.readString(list)).isInstanceOf(PrefRead.Unreadable::class.java)
        assertThat(FlutterPrefsFormat.readString("dark")).isEqualTo(PrefRead.Present("dark"))
    }

    @Test
    fun `a string the plugin's setString would refuse cannot be stored`() {
        val refused = runCatching { FlutterPrefsFormat.storableString(FlutterPrefsFormat.LIST_PREFIX + "x") }
        assertThat(refused.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(FlutterPrefsFormat.storableString("/storage/emulated/0/Files Tech")).isEqualTo("/storage/emulated/0/Files Tech")
    }

    @Test
    fun `a lone surrogate is stored as U+FFFD, as it left Dart through the platform channel`() {
        val lone = "a" + c(0xD800) + "b"
        val expected = "a" + c(0xFFFD) + "b"
        assertThat(FlutterPrefsFormat.storableString(lone)).isEqualTo(expected)
        assertThat(FlutterPrefsFormat.readStringList(FlutterPrefsFormat.encodeStringList(listOf(lone))))
            .isEqualTo(PrefRead.Present(listOf(expected)))
        // A valid pair is not touched.
        assertThat(FlutterPrefsFormat.storableString(c(0x1F600))).isEqualTo(c(0x1F600))
    }

    data class OracleCase(val label: String, val input: List<String>, val dartOutputBase64: String) {
        override fun toString() = label
    }

    /** One line of the decode oracle: the stored JSON text, and the strings Dart read (null = refused). */
    data class DecodeCase(val json: String, val expected: List<String>?) {
        override fun toString() = json.map { visible(it) }.joinToString("")

        private fun visible(c: Char) = if (c < ' ' || c.code > ASCII_MAX) "<${c.code}>" else c.toString()
    }

    companion object {
        private fun c(code: Int): String = String(Character.toChars(code))

        /**
         * Outputs of Dart 3.13.2's `jsonEncode`, as Base64 of their UTF-8 bytes, for inputs built the
         * same way on both sides (character codes, no escape sequence). Generated on 2026-10-10 by the
         * script kept in tools/dart-vectors/json_list_oracle.dart.
         */
        @JvmStatic
        fun dartOracle(): List<OracleCase> = listOf(
            OracleCase("empty", emptyList(), "W10="),
            OracleCase("two plain", listOf("a", "b"), "WyJhIiwiYiJd"),
            OracleCase(
                "path with accents",
                listOf("/storage/emulated/0/Files Tech/" + c(0xE9) + "t" + c(0xE9) + ".pdf"),
                "WyIvc3RvcmFnZS9lbXVsYXRlZC8wL0ZpbGVzIFRlY2gvw6l0w6kucGRmIl0=",
            ),
            OracleCase(
                "quote, backslash, slash, tab, newline, return",
                listOf("quote " + c(34) + " backslash " + c(92) + " slash / tab " + c(9) + " nl " + c(10) + " cr " + c(13)),
                "WyJxdW90ZSBcIiBiYWNrc2xhc2ggXFwgc2xhc2ggLyB0YWIgXHQgbmwgXG4gY3IgXHIiXQ==",
            ),
            OracleCase(
                "controls, DEL, no-break space, line separator, emoji",
                listOf(
                    "ctrl " + c(1) + " " + c(0x1F) + " del " + c(0x7F) + " nbsp " + c(0xA0) +
                        " ls " + c(0x2028) + " emoji " + c(0x1F600),
                ),
                "WyJjdHJsIFx1MDAwMSBcdTAwMWYgZGVsIH8gbmJzcCDCoCBscyDigKggZW1vamkg8J+YgCJd",
            ),
            OracleCase(
                "a JSON object as an item (recent_files)",
                listOf("{" + c(34) + "path" + c(34) + ":" + c(34) + "/a/b.txt" + c(34) + "}"),
                "WyJ7XCJwYXRoXCI6XCIvYS9iLnR4dFwifSJd",
            ),
            OracleCase(
                "backspace, form feed, vertical tab, NUL, empty item",
                listOf("bs " + c(8) + " ff " + c(12) + " vt " + c(11) + " nul " + c(0), ""),
                "WyJicyBcYiBmZiBcZiB2dCBcdTAwMGIgbnVsIFx1MDAwMCIsIiJd",
            ),
        )

        private const val ASCII_MAX = 126

        /**
         * What Dart 3.13.2 made of each stored text: tools/dart-vectors/json_decode_oracle.dart, run on
         * 2026-10-11, its output kept as it is. A line is `Base64(UTF-8 of the text) ACCEPT n codes`,
         * the strings as UTF-16 code units (so a lone surrogate survives), or `… REFUSE`.
         */
        @JvmStatic
        fun dartDecodeOracle(): List<DecodeCase> {
            val resource = checkNotNull(FlutterPrefsFormatTest::class.java.getResource("/prefs/json-decode-oracle-dart-3.13.2.txt"))
            return resource.readText(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split(" ", limit = 4)
                val json = String(Base64.getDecoder().decode(parts[0]), Charsets.UTF_8)
                val expected = when (parts[1]) {
                    "REFUSE" -> null
                    "ACCEPT" -> {
                        val count = parts[2].toInt()
                        val items = if (count == 0) emptyList() else parts.getOrElse(3) { "" }.split("|")
                        check(items.size == count) { "malformed oracle line: $line" }
                        items.map { item -> item.split(",").filter { it.isNotEmpty() }.map { Char(it.toInt()) }.joinToString("") }
                    }
                    else -> error("malformed oracle line: $line")
                }
                DecodeCase(json, expected)
            }
        }

        private fun serialise(value: Any): ByteArray = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(value) }
            bytes.toByteArray()
        }

        /** What android.util.Base64.encodeToString(bytes, DEFAULT) writes: lines of 76 characters, each ended by \n. */
        private fun androidBase64(bytes: ByteArray): String =
            Base64.getMimeEncoder(76, byteArrayOf('\n'.code.toByte())).encodeToString(bytes) + "\n"
    }
}
