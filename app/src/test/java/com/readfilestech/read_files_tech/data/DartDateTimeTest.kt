package com.readfilestech.read_files_tech.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDateTime
import java.util.Base64

/** Dates as `DateTime.tryParse` / `toIso8601String` of Dart 3.13.2 read and wrote them. */
class DartDateTimeTest {

    @ParameterizedTest
    @MethodSource("dateOracle")
    fun `a date text is read exactly as Dart read it, and written back as Dart wrote it`(case: DateCase) {
        val parsed = DartDateTime.tryParse(case.text)
        if (case.expected == null) {
            assertThat(parsed).isNull()
            return
        }
        val e = case.expected
        assertThat(parsed).isNotNull()
        val v = parsed!!.value
        assertThat(parsed.isUtc).isEqualTo(e.utc)
        assertThat(listOf(v.year, v.monthValue, v.dayOfMonth, v.hour, v.minute, v.second, v.nano / 1_000_000, v.nano / 1_000 % 1_000))
            .isEqualTo(e.fields)
        assertThat(parsed.toIso8601String()).isEqualTo(e.iso)
    }

    @Test
    fun `the oracle covers carried fields, offsets, fractions and refusals`() {
        val cases = dateOracle()
        assertThat(cases.count { it.expected == null }).isAtLeast(7)
        assertThat(cases.mapNotNull { it.expected?.iso }).containsAtLeast("2027-01-01T00:00:00.000", "2026-10-11T15:04:56.000Z")
    }

    @Test
    fun `a time written now reads back identical, and orders by its instant`() {
        val written = DartDateTime.local(LocalDateTime.of(2026, 10, 11, 13, 5, 7, 123_456_000))
        assertThat(written.toIso8601String()).isEqualTo("2026-10-11T13:05:07.123456")
        assertThat(DartDateTime.tryParse(written.toIso8601String())).isEqualTo(written)
        val utcEarlier = DartDateTime.tryParse("1999-01-01T00:00:00Z")!!
        assertThat(utcEarlier).isLessThan(written)
    }

    data class Expected(val utc: Boolean, val fields: List<Int>, val iso: String)

    data class DateCase(val text: String, val expected: Expected?) {
        override fun toString() = "'$text'"
    }

    companion object {
        @JvmStatic
        fun dateOracle(): List<DateCase> {
            val resource = checkNotNull(DartDateTimeTest::class.java.getResource("/prefs/json-object-date-oracle-dart-3.13.2.txt"))
            val lines = resource.readText(Charsets.UTF_8).lines()
            return lines.drop(lines.indexOf("DATES") + 1).filter { it.isNotBlank() }.map { line ->
                val parts = line.split(" ")
                val text = String(Base64.getDecoder().decode(parts[0]), Charsets.UTF_8)
                val expected = if (parts[1] == "REFUSE") {
                    null
                } else {
                    // ACCEPT utc|local year month day hour minute second ms us iso
                    Expected(parts[2] == "utc", parts.subList(3, 11).map { it.toInt() }, parts[11])
                }
                DateCase(text, expected)
            }
        }
    }
}
