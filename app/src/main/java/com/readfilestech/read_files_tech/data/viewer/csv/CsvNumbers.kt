package com.readfilestech.read_files_tech.data.viewer.csv

/**
 * Whether a cell is a number, as the 2.15.3 decided it to sort a column (`num.tryParse`): the text
 * trimmed of Dart's white space, then an integer — a sign, decimal digits, or `0x` and hexadecimal ones —
 * or a double — `1.`, `.5`, `-1.e3`, `1234E+7`, `NaN`, `Infinity`, signed or not. Not Kotlin's
 * `toDoubleOrNull`, which also takes `1.5f`, `0x1p3`, other digits than ASCII and other spaces. A number
 * is kept as a double: two integers past 2^53 apart by less than their precision compare equal (Dart
 * compared them exactly).
 *
 * One addition: in a file whose separator is not the comma — every CSV Excel writes in French — one
 * comma may be the decimal point (`1234,56`, `-0,25`), the way Excel wrote it there; the 2.15.3 sorted
 * those as text, `10,5` before `9,2`. In a file of commas, a quoted `1,234` stays text: it may as well
 * be a thousand.
 */
internal object CsvNumbers {
    fun parse(text: String, decimalComma: Boolean): Double? {
        val number = dartTrim(text)
        return integer(number) ?: decimal(number) ?: if (decimalComma) commaDecimal(number) else null
    }

    /** `int.tryParse` without a radix: decimal, or `0x` hexadecimal — positive up to 2^64 - 1, read as Dart's signed 64 bits. */
    private fun integer(text: String): Double? {
        val negative = text.startsWith('-')
        val digits = if (negative || text.startsWith('+')) text.substring(1) else text
        val hex = digits.length > 2 && digits[0] == '0' && (digits[1] == 'x' || digits[1] == 'X')
        return when {
            hex -> hexadecimal(digits.substring(2), negative)
            digits.isNotEmpty() && digits.all(::isDigit) -> digits.toLongOrNull()?.let {
                if (negative) -it.toDouble() + 0.0 else it.toDouble()
            }
            else -> null
        }
    }

    private fun hexadecimal(digits: String, negative: Boolean): Double? {
        val significant = digits.trimStart('0')
        val valid = digits.isNotEmpty() && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
            significant.length <= MAX_HEX_DIGITS
        val unsigned = if (valid) java.lang.Long.parseUnsignedLong(significant.ifEmpty { "0" }, HEX) else return null
        // -0x8000000000000000 is the least; a positive value above 2^63 - 1 wraps, as Dart's does.
        return when {
            !negative -> unsigned.toDouble()
            unsigned >= 0 -> -unsigned.toDouble() + 0.0
            unsigned == Long.MIN_VALUE -> Long.MIN_VALUE.toDouble()
            else -> null
        }
    }

    /** `double.tryParse`: a sign, then `NaN`, `Infinity`, or digits with a point and an exponent, each optional. */
    private fun decimal(text: String): Double? {
        val body = if (text.startsWith('-') || text.startsWith('+')) text.substring(1) else text
        return if (body == "NaN" || body == "Infinity" || isFloatingLiteral(body)) text.toDouble() else null
    }

    private fun isFloatingLiteral(text: String): Boolean {
        val integerDigits = digitsFrom(text, 0)
        var i = integerDigits
        var fractionDigits = 0
        if (text.getOrNull(i) == '.') {
            fractionDigits = digitsFrom(text, i + 1)
            i += 1 + fractionDigits
        }
        val mantissa = integerDigits > 0 || fractionDigits > 0
        if (mantissa && text.getOrNull(i)?.lowercaseChar() == 'e') i = exponentEnd(text, i + 1)
        return mantissa && i == text.length
    }

    /** Where an exponent that begins at [from], after its `e`, ends: a sign, then digits; -1 without digits. */
    private fun exponentEnd(text: String, from: Int): Int {
        val sign = text.getOrNull(from)
        val start = if (sign == '+' || sign == '-') from + 1 else from
        val digits = digitsFrom(text, start)
        return if (digits > 0) start + digits else -1
    }

    private fun digitsFrom(text: String, from: Int): Int {
        var i = from
        while (i < text.length && isDigit(text[i])) i++
        return i - from
    }

    private fun commaDecimal(text: String): Double? {
        val comma = text.indexOf(',')
        val one = comma >= 0 && text.indexOf(',', comma + 1) < 0 && '.' !in text
        return if (one) decimal(text.replace(',', '.')) else null
    }

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    /**
     * Dart's `String.trim` (`_isTwoByteWhitespace` of its VM): Unicode's White_Space — the space
     * separators, U+2028 and U+2029 that [Character.isSpaceChar] gives, tab to carriage return, NEL — and
     * the byte order mark.
     */
    private fun dartTrim(text: String): String = text.trim {
        it in '\t'..'\r' || it.code == NEL || it.code == BOM || Character.isSpaceChar(it)
    }

    private const val NEL = 0x85
    private const val BOM = 0xFEFF
    private const val MAX_HEX_DIGITS = 16
    private const val HEX = 16
}
