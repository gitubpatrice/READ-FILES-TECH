package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.viewer.reader.HtmlEntities
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** The three codings of the encoding tool (`EncodeScreen` of the 2.15.3). */
enum class TextCodec { BASE64, URL, HTML }

/** Why a text cannot be decoded. */
enum class DecodeRefusal {
    /** Not Base64: a character outside its alphabet, or a length no Base64 text has. */
    BASE64_INVALID,

    /** A `%` not followed by two hexadecimal digits. */
    PERCENT_INVALID,

    /** The bytes decoded are not UTF-8 text (a picture's Base64, say). */
    NOT_UTF8,
}

/** A decoding's result: the text, or why there is none. */
sealed interface Decoded {
    data class Text(val text: String) : Decoded

    data class Refused(val reason: DecodeRefusal) : Decoded
}

/**
 * Encoding and decoding of a text, both ways, as pure functions — what `EncodeScreen` of the 2.15.3 did in
 * its widget, with three defects fixed (inventory F2): the HTML references are read in ONE pass, so
 * `&amp;lt;` gives `&lt;` and not `<` (the 2.15.3 replaced `&amp;` first, `encode_screen.dart:59`); the URL
 * coding is the percent-encoding of a query parameter (RFC 3986: every byte of the UTF-8 text but the
 * unreserved characters), where `Uri.encodeFull` (`:39`) left `&`, `=`, `?`, `/` and `#` as they were; and a
 * text that cannot be decoded is said so, not shown as the exception's text.
 */
object TextCodecs {
    fun encode(codec: TextCodec, text: String): String = when (codec) {
        TextCodec.BASE64 -> Base64.getEncoder().encodeToString(text.encodeToByteArray())
        TextCodec.URL -> PercentCoding.encode(text)
        TextCodec.HTML -> HtmlCoding.escape(text)
    }

    fun decode(codec: TextCodec, text: String): Decoded = when (codec) {
        TextCodec.BASE64 -> base64Decode(text)
        TextCodec.URL -> PercentCoding.decode(text)
        TextCodec.HTML -> Decoded.Text(HtmlCoding.unescape(text))
    }

    /**
     * Base64 in either alphabet — the standard one, or the URL one (`-` and `_`) — padded or not; the line
     * breaks and spaces a copied text carries are ignored. Anything else is refused, never skipped.
     */
    private fun base64Decode(text: String): Decoded {
        val compact = text.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        val decoder = if (compact.any { it == '-' || it == '_' }) Base64.getUrlDecoder() else Base64.getDecoder()
        val bytes = try {
            decoder.decode(compact)
        } catch (_: IllegalArgumentException) {
            return Decoded.Refused(DecodeRefusal.BASE64_INVALID)
        }
        return utf8(bytes)
    }
}

/** RFC 3986's percent-encoding of a UTF-8 text. */
private object PercentCoding {
    fun encode(text: String): String {
        val bytes = text.encodeToByteArray()
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val b = byte.toInt() and BYTE
            if (b < ASCII_END && isUnreserved(b.toChar())) {
                out.append(b.toChar())
            } else {
                out.append('%').append(HEX_DIGITS[b shr NIBBLE]).append(HEX_DIGITS[b and NIBBLE_MASK])
            }
        }
        return out.toString()
    }

    /** RFC 3986 §2.3: what a percent-encoding leaves as it is (Python's `quote(…, safe='')` too). */
    private fun isUnreserved(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~"

    /** `%XX` as the byte it names, the other characters as their UTF-8 bytes; then the bytes as UTF-8. */
    fun decode(text: String): Decoded {
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val percent = text.indexOf('%', i).let { if (it < 0) text.length else it }
            if (percent > i) bytes.write(text.substring(i, percent).encodeToByteArray())
            if (percent == text.length) break
            val high = hexValue(text.getOrNull(percent + 1))
            val low = hexValue(text.getOrNull(percent + 2))
            if (high < 0 || low < 0) return Decoded.Refused(DecodeRefusal.PERCENT_INVALID)
            bytes.write((high shl NIBBLE) or low)
            i = percent + PERCENT_LENGTH
        }
        return utf8(bytes.toByteArray())
    }

    /** The value of an ASCII hexadecimal digit, or -1 (`Character.digit` alone takes the full-width ones). */
    private fun hexValue(c: Char?): Int = if (c == null || c.code >= ASCII_END) -1 else Character.digit(c, HEX)
}

/** HTML's character references. */
private object HtmlCoding {
    /** The five characters HTML gives a meaning to; `'` as `&#39;`, as the 2.15.3 wrote it. */
    fun escape(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * Every character reference read once, from left to right: a named one among the reader's table
     * ([HtmlEntities], `;` required), a decimal or hexadecimal one (`;` optional, as the HTML standard
     * reads it). What names nothing stays as written.
     */
    fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val amp = text.indexOf('&', i)
            if (amp < 0) {
                out.append(text, i, text.length)
                break
            }
            out.append(text, i, amp)
            i = if (text.getOrNull(amp + 1) == '#') numericReference(text, amp, out) else namedReference(text, amp, out)
        }
        return out.toString()
    }

    /** At the `&` of `&name;`: appends what it names and returns the index after it — or `&`, and the next one. */
    private fun namedReference(text: String, amp: Int, out: StringBuilder): Int {
        var end = amp + 1
        while (end < text.length && end - amp - 1 < HtmlEntities.LONGEST_NAME && text[end].isAsciiLetterOrDigit()) end++
        val resolved = if (text.getOrNull(end) == ';') HtmlEntities.named(text.substring(amp + 1, end)) else null
        if (resolved == null) {
            out.append('&')
            return amp + 1
        }
        out.append(resolved)
        return end + 1
    }

    /** At the `&` of `&#…`: appends the character and returns the index after the reference — or `&`. */
    private fun numericReference(text: String, amp: Int, out: StringBuilder): Int {
        var p = amp + 2
        val hex = text.getOrNull(p) == 'x' || text.getOrNull(p) == 'X'
        if (hex) p++
        val radix = if (hex) HEX else DECIMAL
        val digitsStart = p
        var code = 0L
        while (p < text.length && Character.digit(text[p], radix) >= 0 && text[p].code < ASCII_END) {
            // Past Unicode it is U+FFFD whatever follows: the value stops growing there.
            code = minOf(code * radix + Character.digit(text[p], radix), Character.MAX_CODE_POINT + 1L)
            p++
        }
        if (p == digitsStart) {
            out.append('&')
            return amp + 1
        }
        out.append(HtmlEntities.numeric(code.toInt()))
        return if (text.getOrNull(p) == ';') p + 1 else p
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

/** [bytes] as UTF-8 text, or why they are not. */
private fun utf8(bytes: ByteArray): Decoded = try {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    Decoded.Text(decoder.decode(ByteBuffer.wrap(bytes)).toString())
} catch (_: CharacterCodingException) {
    Decoded.Refused(DecodeRefusal.NOT_UTF8)
}

private const val BYTE = 0xFF
private const val ASCII_END = 0x80
private const val NIBBLE = 4
private const val NIBBLE_MASK = 0x0F
private const val DECIMAL = 10
private const val HEX = 16
private const val PERCENT_LENGTH = 3
private const val HEX_DIGITS = "0123456789ABCDEF"
