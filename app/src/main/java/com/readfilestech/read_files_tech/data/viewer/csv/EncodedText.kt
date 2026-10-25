package com.readfilestech.read_files_tech.data.viewer.csv

import com.readfilestech.read_files_tech.data.text.CsvScanner
import com.readfilestech.read_files_tech.data.text.CsvText
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.nio.ByteBuffer

/**
 * The bytes of a file as [CsvScanner] reads them, its byte order mark skipped: a unit per byte in UTF-8
 * and Windows-1252, per code unit in UTF-16. A unit is the character itself wherever it is ASCII — the
 * separator, the quote, the line breaks — which is all the scanner looks at; a field is decoded with
 * the file's charset once the scanner has found it. The file is kept as bytes: 50 MiB of Windows-1252
 * decoded at once would be 100 MB of characters.
 */
internal class EncodedText(private val bytes: ByteArray, private val size: Int, val encoding: TextEncoding) : CsvText() {
    private val offset = minOf(encoding.bomLength, size)
    private val wide = encoding.unitSize == 2
    private val bigEndian = encoding == TextEncoding.UTF_16BE

    override val length: Int = (size - offset) / encoding.unitSize

    override fun get(index: Int): Char {
        if (!wide) return Char(byte(index))
        val p = offset + index * 2
        val first = bytes[p].toInt() and BYTE
        val second = bytes[p + 1].toInt() and BYTE
        return Char(if (bigEndian) first shl BITS or second else second shl BITS or first)
    }

    /** Byte [index] after the byte order mark, unsigned — a unit of a one-byte encoding. */
    fun byte(index: Int): Int = bytes[offset + index].toInt() and BYTE

    override fun decode(from: Int, to: Int): String {
        val start = byteAt(from)
        return encoding.charset.decode(ByteBuffer.wrap(bytes, start, byteAt(to) - start)).toString()
    }

    /**
     * Where unit [index] begins; the end of the bytes for the end of the text, an odd last byte of UTF-16
     * included (decoded as U+FFFD). The CSV editor copies the bytes of what the user did not touch by it.
     */
    fun byteAt(index: Int): Int = if (index == length) size else offset + index * encoding.unitSize

    /** The first [units] units, in an array of their own: the rest of the bytes is let go. */
    fun prefix(units: Int): EncodedText {
        val end = byteAt(units)
        return EncodedText(bytes.copyOf(end), end, encoding)
    }

    private companion object {
        const val BYTE = 0xFF
        const val BITS = 8
    }
}

/**
 * The characters of one raw field read one by one, as [com.readfilestech.read_files_tech.data.text.field]
 * would give them — quotes removed, `""` as one — without building its string: the sort compares a
 * million cells this way, their keys being where they lie in the file (8 bytes a row), not their text.
 */
internal class FieldChars(private val text: EncodedText) {
    private var position = 0
    private var end = 0
    private var quoted = false

    /** The second half of a character outside the BMP, given by the next [next]; -1 when none. */
    private var pending = NONE

    fun reset(start: Int, end: Int) {
        quoted = start < end && text[start] == CsvScanner.QUOTE
        position = if (quoted) start + 1 else start
        this.end = end
        pending = NONE
    }

    /** The next UTF-16 code unit of the field's text; -1 at its end. */
    fun next(): Int {
        val waiting = pending
        pending = NONE
        return when {
            waiting != NONE -> waiting
            quoted && position < end && text[position] == CsvScanner.QUOTE -> quote()
            position < end -> decodeNext()
            else -> NONE
        }
    }

    /** At a quote inside the quotes: `""` is one quote; a lone one closes them, and what follows is kept as it is. */
    private fun quote(): Int {
        if (position + 1 < end && text[position + 1] == CsvScanner.QUOTE) {
            position += 2
            return CsvScanner.QUOTE.code
        }
        quoted = false
        position++
        return next()
    }

    private fun decodeNext(): Int = when (text.encoding) {
        TextEncoding.UTF_16LE, TextEncoding.UTF_16BE -> text[position++].code
        TextEncoding.WINDOWS_1252 -> WINDOWS_1252[text.byte(position++)].code
        TextEncoding.UTF_8, TextEncoding.UTF_8_BOM -> decodeUtf8()
    }

    /** One UTF-8 character; a malformed byte is U+FFFD, as the decoder that shows the cell reads it. */
    private fun decodeUtf8(): Int {
        val lead = text.byte(position)
        val length = SEQUENCE_LENGTHS[lead ushr NIBBLE]
        val code = if (length == 1) lead else multiByte(lead, length)
        position += if (code < 0) 1 else length
        return when {
            code < 0 -> REPLACEMENT
            code < SUPPLEMENTARY -> code
            else -> {
                pending = Character.lowSurrogate(code).code
                Character.highSurrogate(code).code
            }
        }
    }

    /** The code point of a sequence of [length] bytes from [lead]; -1 when malformed. */
    private fun multiByte(lead: Int, length: Int): Int {
        val valid = lead in LEAD_MIN..LEAD_MAX && position + length <= end
        var code = if (valid) lead and (LEAD_PAYLOAD shr length) else -1
        var i = 1
        while (code >= 0 && i < length) {
            val next = text.byte(position + i)
            code = if ((next and CONTINUATION_MASK) == CONTINUATION) code shl PAYLOAD_BITS or (next and PAYLOAD_MASK) else -1
            i++
        }
        // Overlong forms, surrogates and what lies beyond Unicode are malformed too.
        val wellFormed = code >= SHORTEST[length] && code <= Character.MAX_CODE_POINT && (code shr SURROGATE_SHIFT) != SURROGATES
        return if (wellFormed) code else -1
    }

    private companion object {
        const val NONE = -1
        const val REPLACEMENT = 0xFFFD
        const val SUPPLEMENTARY = 0x10000
        const val NIBBLE = 4
        const val LEAD_MIN = 0xC2
        const val LEAD_MAX = 0xF4
        const val LEAD_PAYLOAD = 0x7F
        const val SURROGATE_SHIFT = 11
        const val SURROGATES = 0x1B // 0xD800 shr 11: the code points of the surrogates
        const val CONTINUATION_MASK = 0xC0
        const val CONTINUATION = 0x80
        const val PAYLOAD_MASK = 0x3F
        const val PAYLOAD_BITS = 6

        /**
         * The bytes of a UTF-8 sequence by the high half of its first byte: one below 0x80; 2 for a stray
         * continuation byte too (0x8_ to 0xB_), which [multiByte] refuses as a first byte.
         */
        val SEQUENCE_LENGTHS = intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 4)

        /** The least code point a sequence of that many bytes may hold: below it, an overlong form. */
        val SHORTEST = intArrayOf(0, 0, 0x80, 0x800, SUPPLEMENTARY)

        /** Windows-1252 as the JVM decodes it, byte by byte: its five unassigned bytes are U+FFFD there too. */
        val WINDOWS_1252: CharArray = String(ByteArray(256) { it.toByte() }, TextEncoding.WINDOWS_1252.charset).toCharArray()
    }
}
