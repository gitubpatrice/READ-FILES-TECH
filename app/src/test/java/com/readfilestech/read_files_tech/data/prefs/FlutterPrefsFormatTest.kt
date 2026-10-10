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
    // Lists in the older forms an old install may still carry.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a Java-serialised list, Base64 broken every 76 characters as Android wrote it, is read`() {
        val list = arrayListOf("/storage/emulated/0/Download/a.pdf", "/storage/emulated/0/Files Tech/été.txt", "x".repeat(200))
        val stored = FlutterPrefsFormat.LIST_PREFIX + androidBase64(serialise(list))
        assertThat(stored).contains("\n")
        assertThat(FlutterPrefsFormat.readStringList(stored)).isEqualTo(PrefRead.Present(list))
    }

    @Test
    fun `a Java-serialised Arrays-asList is read, as the plugin's allow-list permits`() {
        val list = listOf("a", "b")
        val stored = FlutterPrefsFormat.LIST_PREFIX + androidBase64(serialise(java.util.Arrays.asList("a", "b")))
        assertThat(FlutterPrefsFormat.readStringList(stored)).isEqualTo(PrefRead.Present(list))
    }

    @Test
    fun `a serialised object of a refused class is unreadable, not instantiated`() {
        val stored = FlutterPrefsFormat.LIST_PREFIX + androidBase64(serialise(java.util.HashMap(mapOf("k" to "v"))))
        val read = FlutterPrefsFormat.readStringList(stored)
        assertThat(read).isInstanceOf(PrefRead.Unreadable::class.java)
        assertThat((read as PrefRead.Unreadable).reason).contains("refused class")
    }

    @Test
    fun `the first plugin versions' string set is read`() {
        assertThat(FlutterPrefsFormat.readStringList(setOf("a"))).isEqualTo(PrefRead.Present(listOf("a")))
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
            FlutterPrefsFormat.LIST_PREFIX + androidBase64(byteArrayOf(1, 2, 3)),
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
        val refused = runCatching { FlutterPrefsFormat.requireStorableString(FlutterPrefsFormat.LIST_PREFIX + "x") }
        assertThat(refused.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        FlutterPrefsFormat.requireStorableString("/storage/emulated/0/Files Tech")
    }

    data class OracleCase(val label: String, val input: List<String>, val dartOutputBase64: String) {
        override fun toString() = label
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

        private fun serialise(value: Any): ByteArray = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(value) }
            bytes.toByteArray()
        }

        /** What android.util.Base64.encodeToString(bytes, DEFAULT) writes: lines of 76 characters, each ended by \n. */
        private fun androidBase64(bytes: ByteArray): String =
            Base64.getMimeEncoder(76, byteArrayOf('\n'.code.toByte())).encodeToString(bytes) + "\n"
    }
}
