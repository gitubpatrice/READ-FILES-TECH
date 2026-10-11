package com.readfilestech.read_files_tech.data.prefs

/**
 * Reads a stored list the way the 2.15.x did: `jsonDecode`, then `(value as List).cast<String>()`.
 * Anything that path refused made the Flutter app throw; here it is "not a list" (null).
 *
 * Its own parser, not `org.json`: on a phone `org.json` is Android's own implementation, and both it
 * and the reference one used by JVM tests are lenient (`['a']`, trailing commas…) where Dart is strict
 * — the tests would have checked a parser that does not ship (external review of 2026-10-11). Every
 * rule below was asked to Dart 3.13.2 itself (tools/dart-vectors/json_decode_oracle.dart):
 * - whitespace between tokens is space, tab, LF or CR only — not a form feed, a no-break space or a BOM;
 * - a control character (below U+0020) inside a string must be escaped; DEL and the rest need not;
 * - escapes are `\" \\ \/ \b \f \n \r \t` and `\u` with four ASCII hexadecimal digits in either case;
 *   an escaped lone surrogate is kept as it is;
 * - the root must be an array of strings, followed by nothing but whitespace.
 */
internal object DartJsonList {
    @Suppress("SwallowedException") // "malformed" is the answer: the text is not a list Dart would read
    fun decode(json: String): List<String>? = try {
        Scanner(json).document()
    } catch (expected: Malformed) {
        null
    }

    /** Thrown at the first byte Dart's parser would refuse; never leaves this object. */
    private class Malformed : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Scanner(private val text: String) {
        private var pos = 0

        fun document(): List<String> {
            val list = stringArray()
            skipWhitespace()
            if (pos != text.length) throw Malformed()
            return list
        }

        private fun stringArray(): List<String> {
            skipWhitespace()
            expect('[')
            skipWhitespace()
            val items = mutableListOf<String>()
            if (!consume(']')) {
                do {
                    skipWhitespace()
                    items += string()
                    skipWhitespace()
                } while (consume(','))
                expect(']')
            }
            return items
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> break
                    c == '\\' -> out.append(escape())
                    c < ' ' -> throw Malformed()
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        private fun escape(): Char = when (val c = next()) {
            '"', '\\', '/' -> c
            'b' -> '\b'
            'f' -> Char(FORM_FEED_CODE)
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> hex4()
            else -> throw Malformed()
        }

        private fun hex4(): Char {
            var code = 0
            repeat(HEX_DIGITS) { code = code * HEX_RADIX + hexValue(next()) }
            return Char(code)
        }

        /** ASCII only: `digitToIntOrNull` would also take Arabic-Indic or full-width digits, which Dart refuses. */
        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + DECIMAL_DIGITS
            in 'A'..'F' -> c - 'A' + DECIMAL_DIGITS
            else -> throw Malformed()
        }

        private fun next(): Char = if (pos < text.length) text[pos++] else throw Malformed()

        private fun skipWhitespace() {
            while (pos < text.length && text[pos] in WHITESPACE) pos++
        }

        private fun consume(expected: Char): Boolean {
            val matches = pos < text.length && text[pos] == expected
            if (matches) pos++
            return matches
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) throw Malformed()
        }
    }

    private const val WHITESPACE = " \t\n\r"
    private const val FORM_FEED_CODE = 0x0C
    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16
    private const val DECIMAL_DIGITS = 10
}
