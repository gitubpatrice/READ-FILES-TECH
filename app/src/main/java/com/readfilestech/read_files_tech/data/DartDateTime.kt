package com.readfilestech.read_files_tech.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * A date as the Flutter app wrote and read it: `DateTime.toIso8601String()` for the recent files and
 * the trash entries, `DateTime.parse` / `tryParse` to read them back. Kotlin's ISO parsers accept
 * other texts and normalise other ways; this follows `dart:core` (sdk/lib/core/date_time.dart), every
 * rule checked against Dart 3.13.2 (tools/dart-vectors/json_object_date_oracle.dart):
 * - `[+-]?yyyy[yy]-?MM-?dd` then optionally `[ T]HH[:?mm[:?ss[[.,]fraction]]]` and an offset
 *   ` ?Z|z` or ` ?±HH[:?mm]`; one-digit fields, a lowercase `t`, a leading space are refused;
 * - fields that overflow are carried as Dart carries them: month 13, February 30, 24:00, :60;
 * - a fraction is read to the microsecond, digits beyond the sixth dropped;
 * - with an offset the date is UTC; without, it is a local time of the device's zone.
 *
 * [value] keeps Dart's view (local or UTC, fields carried), so [toIso8601String] writes back exactly
 * what Dart would.
 */
class DartDateTime private constructor(val value: LocalDateTime, val isUtc: Boolean) : Comparable<DartDateTime> {

    /** Microseconds since the epoch, a local time read in [zone] — the order of `DateTime.compareTo`. */
    fun epochMicros(zone: ZoneId = ZoneId.systemDefault()): Long {
        val instant = if (isUtc) value.toInstant(ZoneOffset.UTC) else value.atZone(zone).toInstant()
        return ChronoUnit.MICROS.between(java.time.Instant.EPOCH, instant)
    }

    override fun compareTo(other: DartDateTime): Int = epochMicros().compareTo(other.epochMicros())

    /** `DateTime.toIso8601String()`: milliseconds always, microseconds when not zero, `Z` when UTC. */
    fun toIso8601String(): String = buildString {
        val year = value.year
        append(if (year in -MAX_FOUR_DIGIT_YEAR..MAX_FOUR_DIGIT_YEAR) fourDigits(year) else sixDigits(year))
        append('-').append(two(value.monthValue)).append('-').append(two(value.dayOfMonth))
        append('T').append(two(value.hour)).append(':').append(two(value.minute)).append(':').append(two(value.second))
        val micros = value.nano / NANOS_PER_MICRO
        append('.').append(three(micros / MICROS_PER_MILLI))
        if (micros % MICROS_PER_MILLI != 0) append(three(micros % MICROS_PER_MILLI))
        if (isUtc) append('Z')
    }

    override fun equals(other: Any?) = other is DartDateTime && other.value == value && other.isUtc == isUtc

    override fun hashCode() = value.hashCode() * 31 + isUtc.hashCode()

    override fun toString() = toIso8601String()

    companion object {
        private val FORMAT = Regex(
            """^([+-]?\d{4,6})-?(\d\d)-?(\d\d)""" +
                """(?:[ T](\d\d)(?::?(\d\d)(?::?(\d\d)(?:[.,](\d+))?)?)?""" +
                """( ?[zZ]| ?([-+])(\d\d)(?::?(\d\d))?)?)?$""",
        )
        private const val MAX_FOUR_DIGIT_YEAR = 9_999
        private const val FRACTION_DIGITS = 6
        private const val MICROS_PER_MILLI = 1_000
        private const val NANOS_PER_MICRO = 1_000
        private const val MINUTES_PER_HOUR = 60L
        private const val DECIMAL = 10
        private const val TWO_DIGITS = 10
        private const val THREE_DIGITS = 100

        /** Dart's `DateTime` range: 100 000 000 days on each side of the epoch. */
        private const val MAX_EPOCH_DAYS = 100_000_000L

        /** `DateTime.tryParse`: null where Dart returns null. */
        fun tryParse(text: String): DartDateTime? {
            val g = FORMAT.matchEntire(text)?.groupValues ?: return null
            var minute = g[MINUTE].toLongOrZero()
            if (g[OFFSET_SIGN].isNotEmpty()) {
                val sign = if (g[OFFSET_SIGN] == "-") -1 else 1
                minute -= sign * (g[OFFSET_HOURS].toLong() * MINUTES_PER_HOUR + g[OFFSET_MINUTES].toLongOrZero())
            }
            // Carried field by field from the first day of the year, as Dart's broken-down arithmetic.
            val value = runCatching {
                LocalDateTime.of(g[YEAR].toInt(), 1, 1, 0, 0)
                    .plusMonths(g[MONTH].toLong() - 1)
                    .plusDays(g[DAY].toLong() - 1)
                    .plusHours(g[HOUR].toLongOrZero())
                    .plusMinutes(minute)
                    .plusSeconds(g[SECOND].toLongOrZero())
                    .plus(fractionMicros(g[FRACTION]), ChronoUnit.MICROS)
            }.getOrNull()
            // Any offset makes it UTC, `z` included.
            return value?.let { DartDateTime(it, isUtc = g[OFFSET].isNotEmpty()) }?.takeIf { inRange(it) }
        }

        // The groups of FORMAT, in order.
        private const val YEAR = 1
        private const val MONTH = 2
        private const val DAY = 3
        private const val HOUR = 4
        private const val MINUTE = 5
        private const val SECOND = 6
        private const val FRACTION = 7
        private const val OFFSET = 8
        private const val OFFSET_SIGN = 9
        private const val OFFSET_HOURS = 10
        private const val OFFSET_MINUTES = 11

        /** Now, as `DateTime.now()`: a local time, to the microsecond. */
        fun now(): DartDateTime = DartDateTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), isUtc = false)

        fun local(value: LocalDateTime) = DartDateTime(value.truncatedTo(ChronoUnit.MICROS), isUtc = false)

        /** The first six digits of [fraction], as microseconds; the others dropped, never rounded. */
        private fun fractionMicros(fraction: String): Long {
            var result = 0L
            for (i in 0 until FRACTION_DIGITS) {
                result *= DECIMAL
                if (i < fraction.length) result += fraction[i] - '0'
            }
            return result
        }

        private fun inRange(date: DartDateTime): Boolean =
            ChronoUnit.DAYS.between(EPOCH, date.value) in -MAX_EPOCH_DAYS..MAX_EPOCH_DAYS

        /** 1970-01-01T00:00 (`LocalDate.EPOCH` is Java 9: not on Android 8). */
        private const val EPOCH_YEAR = 1_970
        private val EPOCH = LocalDateTime.of(EPOCH_YEAR, 1, 1, 0, 0)
        private const val FOUR = 4
        private const val SIX = 6

        private fun String.toLongOrZero() = if (isEmpty()) 0L else toLong()

        private fun fourDigits(n: Int): String = (if (n < 0) "-" else "") + kotlin.math.abs(n).toString().padStart(FOUR, '0')

        private fun sixDigits(n: Int): String = (if (n < 0) "-" else "+") + kotlin.math.abs(n).toString().padStart(SIX, '0')

        private fun two(n: Int) = if (n < TWO_DIGITS) "0$n" else "$n"

        private fun three(n: Int) = when {
            n < TWO_DIGITS -> "00$n"
            n < THREE_DIGITS -> "0$n"
            else -> "$n"
        }
    }
}
