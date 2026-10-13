package com.readfilestech.read_files_tech.data.viewer.json

import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding

/**
 * The text of a JSON file in its code units — bytes, or UTF-16 units: every character of the syntax is
 * ASCII, and a UTF-8 sequence never holds an ASCII byte. Positions are byte offsets. What it skips is known
 * valid ([JsonScanner] checked it): it never runs past the end of a token.
 */
internal class JsonText(private val bytes: ByteArray, private val size: Int, private val encoding: TextEncoding) {
    val unit: Int = encoding.unitSize

    /** The code unit at byte [p], or -1 past the end. */
    fun unitAt(p: Int): Int {
        if (p < 0 || p + unit > size) return -1
        val first = bytes[p].toInt() and BYTE
        return when (encoding) {
            TextEncoding.UTF_16LE -> first or ((bytes[p + 1].toInt() and BYTE) shl Byte.SIZE_BITS)
            TextEncoding.UTF_16BE -> (first shl Byte.SIZE_BITS) or (bytes[p + 1].toInt() and BYTE)
            else -> first
        }
    }

    /** Whether [start] is a member's key: a string followed by a colon. */
    fun isMember(start: Int): Boolean = unitAt(start) == '"'.code && unitAt(skipSpace(skipString(start))) == ':'.code

    /** Where the value recorded at [start] begins: after its key and the colon, for a member. */
    fun valueStart(start: Int): Int = if (isMember(start)) skipSpace(skipSpace(skipString(start)) + unit) else start

    /** After the scalar starting at [start]. */
    fun tokenEnd(start: Int): Int = when (unitAt(start)) {
        '"'.code -> skipString(start)
        't'.code, 'n'.code -> start + TRUE_OR_NULL_UNITS * unit
        'f'.code -> start + FALSE_UNITS * unit
        else -> {
            var p = start
            while (unitAt(p).let { it in '0'.code..'9'.code || it in NUMBER_SIGNS }) p += unit
            p
        }
    }

    /** After the closing quote of the string starting at [start]. */
    fun skipString(start: Int): Int {
        var p = start + unit
        while (true) {
            when (unitAt(p)) {
                '"'.code -> return p + unit
                '\\'.code -> p += 2 * unit
                else -> p += unit
            }
        }
    }

    fun skipSpace(from: Int): Int {
        var p = from
        while (unitAt(p) in SPACES) p += unit
        return p
    }

    /** The text from [start] until [end], decoded; past [maxChars], cut there and marked with [JsonDocument.ELLIPSIS]. */
    fun clip(start: Int, end: Int, maxChars: Int): String {
        // A character takes up to 4 bytes in UTF-8: a longer token is decoded no further than needed.
        val read = minOf(end - start, (maxChars + 1) * MAX_BYTES_PER_CHAR)
        val text = String(bytes, start, read, encoding.charset)
        if (text.length <= maxChars && read == end - start) return text
        val cut = if (Character.isHighSurrogate(text[maxChars - 1])) maxChars - 1 else maxChars
        return text.substring(0, cut) + JsonDocument.ELLIPSIS
    }

    /**
     * The characters of the string starting at [start], unescaped and without its quotes, decoded no further
     * than [maxChars] + 1 characters need.
     */
    fun string(start: Int, maxChars: Int): String {
        // An escape takes up to 6 units for one character.
        val units = minOf((skipString(start) - start) / unit - 2, (maxChars + 1) * MAX_UNITS_PER_CHAR)
        return unescape(String(bytes, start + unit, units * unit, encoding.charset), maxChars + 1)
    }

    companion object {
        private const val BYTE = 0xFF
        private const val TRUE_OR_NULL_UNITS = 4
        private const val FALSE_UNITS = 5
        private const val MAX_BYTES_PER_CHAR = 4
        private const val MAX_UNITS_PER_CHAR = 6
        private const val HEX = 16
        private const val HEX_DIGITS = 4
        private val SPACES = intArrayOf(0x20, 0x09, 0x0A, 0x0D)
        private val NUMBER_SIGNS = intArrayOf('-'.code, '+'.code, '.'.code, 'e'.code, 'E'.code)

        /** [raw] (a string's content, valid JSON) without its escapes, stopped at [maxChars] characters. */
        fun unescape(raw: String, maxChars: Int): String {
            val out = StringBuilder(minOf(raw.length, maxChars))
            var i = 0
            while (i < raw.length && out.length < maxChars) {
                val c = raw[i]
                i += when {
                    c != '\\' -> 1.also { out.append(c) }
                    // An escape cut short by the read limit: nothing more to append.
                    i + 1 >= raw.length -> raw.length
                    raw[i + 1] != 'u' -> 2.also { out.append(escaped(raw[i + 1])) }
                    i + 2 + HEX_DIGITS > raw.length -> raw.length
                    else -> (2 + HEX_DIGITS).also { out.append(raw.substring(i + 2, i + 2 + HEX_DIGITS).toInt(HEX).toChar()) }
                }
            }
            return out.toString()
        }

        /** The character a one-letter escape stands for: `\n` for `n`, `/` for `/`. */
        private fun escaped(c: Char): Char = when (c) {
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> c
        }
    }
}
