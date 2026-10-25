package com.readfilestech.read_files_tech.data.viewer.csv

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CsvNumbersTest {
    private fun dart(text: String) = CsvNumbers.parse(text, decimalComma = false)

    /** Invisible characters, built from their code: a literal would be one an editor can alter unseen. */
    private fun char(code: Int) = Char(code).toString()

    /** The examples of Dart's own documentation of `num.tryParse` and `double.tryParse`. */
    @Test
    fun `what Dart's num_tryParse reads as a number`() {
        assertThat(dart("2021")).isEqualTo(2021.0)
        assertThat(dart("3.14")).isEqualTo(3.14)
        assertThat(dart("  3.14 " + char(0xA0))).isEqualTo(3.14)
        assertThat(dart("0.")).isEqualTo(0.0)
        assertThat(dart(".0")).isEqualTo(0.0)
        assertThat(dart("-1.e3")).isEqualTo(-1000.0)
        assertThat(dart("1234E+7")).isEqualTo(1.234e10)
        assertThat(dart("+.12e-9")).isEqualTo(1.2e-10)
        assertThat(dart("-NaN")!!.isNaN()).isTrue()
        assertThat(dart("0xFF")).isEqualTo(255.0)
        assertThat(dart("Infinity")).isEqualTo(Double.POSITIVE_INFINITY)
        assertThat(dart("-Infinity")).isEqualTo(Double.NEGATIVE_INFINITY)
        assertThat(dart("+5")).isEqualTo(5.0)
        // Dart's white space: the figure space, the narrow no-break space, NEL and the BOM trim...
        assertThat(dart(char(0x2007) + char(0x202F) + "12" + char(0xFEFF) + char(0x85))).isEqualTo(12.0)
        // ...the zero-width space does not.
        assertThat(dart(char(0x200B) + "12")).isNull()
    }

    @Test
    fun `what it does not, though Kotlin would`() {
        val arabicDigits = char(0x661) + char(0x662)
        val kotlinWouldTake = listOf("1f", "1.5f", "1d", "0x1p3", arabicDigits)
        val malformed = listOf("", " ", ".", "e5", "1e", "1e+", "0x", "--1", "+-1", "1_000", "1 000", "NAN", "inf")
        (kotlinWouldTake + malformed).forEach { assertThat(dart(it)).isNull() }
    }

    /** Dart's integers are 64 bits: a positive hexadecimal above 2^63 - 1 wraps; past 64 bits, no number. */
    @Test
    fun `integers as Dart reads them, signed hexadecimal and minus zero included`() {
        assertThat(dart("-0x10")).isEqualTo(-16.0)
        assertThat(dart("0XfF")).isEqualTo(255.0)
        assertThat(dart("0xFFFFFFFFFFFFFFFF")).isEqualTo(-1.0)
        assertThat(dart("0x000000000000000000001")).isEqualTo(1.0)
        assertThat(dart("-0x8000000000000000")).isEqualTo(Long.MIN_VALUE.toDouble())
        assertThat(dart("-0x8000000000000001")).isNull()
        assertThat(dart("0x10000000000000000")).isNull()
        // An int: -0 is 0, not the double -0.0 that sorts before it.
        assertThat(dart("-0")!!.compareTo(0.0)).isEqualTo(0)
        assertThat(dart("-0x0")!!.compareTo(0.0)).isEqualTo(0)
        // Too long for 64 bits as a decimal: Dart reads it as a double.
        assertThat(dart("123456789012345678901234567890")).isEqualTo(1.2345678901234568e29)
    }

    /** Excel in French writes `1234,56`; read as a number only where the comma separates no field. */
    @Test
    fun `one decimal comma, in a file whose separator is not the comma`() {
        assertThat(CsvNumbers.parse("1234,56", decimalComma = true)).isEqualTo(1234.56)
        assertThat(CsvNumbers.parse("-0,25", decimalComma = true)).isEqualTo(-0.25)
        assertThat(CsvNumbers.parse(",5", decimalComma = true)).isEqualTo(0.5)
        assertThat(CsvNumbers.parse("1,5E+03", decimalComma = true)).isEqualTo(1500.0)
        assertThat(CsvNumbers.parse(" 7 ", decimalComma = true)).isEqualTo(7.0)
        listOf(
            "1,234,567",
            "1.234,56",
            "1,2.3",
            ",",
            "0x1,5",
            "NaN,"
        ).forEach { assertThat(CsvNumbers.parse(it, decimalComma = true)).isNull() }
        assertThat(CsvNumbers.parse("1234,56", decimalComma = false)).isNull()
    }
}
