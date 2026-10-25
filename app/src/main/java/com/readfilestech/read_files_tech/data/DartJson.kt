package com.readfilestech.read_files_tech.data

/**
 * JSON read the way the Flutter app read it — `jsonDecode` of `dart:convert` — for what the 2.15.x
 * stored: lists of preferences, recent files, trash entries. What Dart refused made the Flutter app
 * throw or skip; here it is [Malformed].
 *
 * Its own parser, not `org.json`: on a phone `org.json` is Android's own implementation, and both it
 * and the reference one used by JVM tests are lenient (`['a']`, trailing commas…) where Dart is strict
 * — the tests would have checked a parser that does not ship (external review of 2026-10-11). Every
 * rule below was asked to Dart 3.13.2 itself (tools/dart-vectors/json_decode_oracle.dart,
 * json_object_date_oracle.dart):
 * - whitespace between tokens is space, tab, LF or CR only — not a form feed, a no-break space or a BOM;
 * - a control character (below U+0020) inside a string must be escaped; DEL and the rest need not;
 * - escapes are `\" \\ \/ \b \f \n \r \t` and `\u` with four ASCII hexadecimal digits in either case;
 *   an escaped lone surrogate is kept as it is;
 * - a number is `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`: without fraction or exponent it is an int
 *   (a [Long]) when it fits, a [Double] otherwise; `01`, `1.`, `.5`, `+1`, `NaN` are refused;
 * - in an object, a repeated key keeps its last value;
 * - after the root value, nothing but whitespace.
 *
 * Values: [Map] (insertion order), [List], [String], [Long], [Double], [Boolean], or null.
 */
object DartJson {
    /** Thrown at the first character Dart's parser would refuse. */
    class Malformed : Exception("not JSON that Dart's jsonDecode accepts") {
        override fun fillInStackTrace(): Throwable = this
    }

    /** @throws Malformed */
    fun decode(json: String): Any? = Parser(Cursor(json)).document()

    /**
     * `jsonEncode`, character for character (checked against Dart 3 in FlutterPrefsFormatTest), for what
     * the app stores: maps (in their order), lists, strings, integers, booleans, null. In a string `"`
     * and `\` are escaped, `\b \t \n \f \r` by name, the other characters below U+0020 as `\u00xx` in
     * lower case; everything else — `/`, DEL, U+2028, emoji — is written as it is.
     */
    fun encode(value: Any?): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean, is Int, is Long -> append(value.toString())
            is String -> appendJsonString(value)
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) append(',')
                    appendJson(item)
                }
                append(']')
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    appendJsonString(k as String)
                    append(':')
                    appendJson(v)
                }
                append('}')
            }
            // A double would need Dart's own number spelling (1.0, not 1.0E3): the app stores none.
            else -> throw IllegalArgumentException("not encoded as Dart does: ${value::class.java.simpleName}")
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                Char(FORM_FEED_CODE) -> append("\\f")
                '\r' -> append("\\r")
                // Dart writes the other control characters as \u00xx, hexadecimal in lower case.
                else -> if (c < ' ') append("\\u").append(c.code.toString(HEX_RADIX).padStart(HEX_DIGITS, '0')) else append(c)
            }
        }
        append('"')
    }

    /**
     * A list of strings as the plugin's `getStringList` returned it — `(value as List).cast<String>()` —
     * or null when Dart would have thrown.
     */
    @Suppress("SwallowedException") // "malformed" is the answer: the text is not a list Dart would read
    fun decodeStringList(json: String): List<String>? = try {
        (decode(json) as? List<*>)?.takeIf { list -> list.all { it is String } }?.map { it as String }
    } catch (expected: Malformed) {
        null
    }

    private class Parser(private val at: Cursor) {
        fun document(): Any? {
            val value = value()
            at.skipWhitespace()
            ensure(at.atEnd())
            return value
        }

        private fun value(): Any? {
            at.skipWhitespace()
            return when (at.peek()) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            at.expect('{')
            at.skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (!at.consume('}')) {
                do {
                    at.skipWhitespace()
                    val key = string()
                    at.skipWhitespace()
                    at.expect(':')
                    map[key] = value() // a repeated key keeps its last value, as in Dart
                    at.skipWhitespace()
                } while (at.consume(','))
                at.expect('}')
            }
            return map
        }

        private fun array(): List<Any?> {
            at.expect('[')
            at.skipWhitespace()
            val items = mutableListOf<Any?>()
            if (!at.consume(']')) {
                do {
                    items += value()
                    at.skipWhitespace()
                } while (at.consume(','))
                at.expect(']')
            }
            return items
        }

        private fun string(): String {
            at.expect('"')
            val out = StringBuilder()
            while (true) {
                val c = at.next()
                when {
                    c == '"' -> break
                    c == '\\' -> out.append(escape())
                    else -> {
                        ensure(c >= ' ')
                        out.append(c)
                    }
                }
            }
            return out.toString()
        }

        private fun escape(): Char = when (val c = at.next()) {
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
            repeat(HEX_DIGITS) { code = code * HEX_RADIX + hexValue(at.next()) }
            return Char(code)
        }

        private fun number(): Any {
            val start = at.pos
            at.consume('-')
            // An integer part: 0, or a non-zero digit followed by digits. No leading zero, no '+'.
            if (!at.consume('0')) {
                ensure(at.peek() in '1'..'9')
                at.digits()
            }
            val fraction = at.consume('.')
            if (fraction) ensure(at.digits() > 0)
            val exponent = at.consume('e') || at.consume('E')
            if (exponent) {
                if (!at.consume('+')) at.consume('-')
                ensure(at.digits() > 0)
            }
            val literal = at.since(start)
            return if (!fraction && !exponent) literal.toLongOrNull() ?: literal.toDouble() else literal.toDouble()
        }

        private fun literal(word: String, value: Boolean?): Boolean? {
            at.expectWord(word)
            return value
        }
    }

    /** Where the parser stands in the text, and the moves Dart's grammar allows. */
    private class Cursor(private val text: String) {
        var pos = 0
            private set

        fun atEnd() = pos == text.length

        fun peek(): Char? = text.getOrNull(pos)

        fun next(): Char = if (pos < text.length) text[pos++] else throw Malformed()

        fun since(start: Int): String = text.substring(start, pos)

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in WHITESPACE) pos++
        }

        fun digits(): Int {
            val start = pos
            while (peek() in '0'..'9') pos++
            return pos - start
        }

        fun consume(expected: Char): Boolean {
            val matches = pos < text.length && text[pos] == expected
            if (matches) pos++
            return matches
        }

        fun expect(expected: Char) = ensure(consume(expected))

        fun expectWord(word: String) {
            ensure(text.startsWith(word, pos))
            pos += word.length
        }
    }

    /** ASCII only: `digitToIntOrNull` would also take Arabic-Indic or full-width digits, which Dart refuses. */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + DECIMAL_DIGITS
        in 'A'..'F' -> c - 'A' + DECIMAL_DIGITS
        else -> throw Malformed()
    }

    private fun ensure(condition: Boolean) {
        if (!condition) throw Malformed()
    }

    private const val WHITESPACE = " \t\n\r"
    private const val FORM_FEED_CODE = 0x0C
    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16
    private const val DECIMAL_DIGITS = 10
}
