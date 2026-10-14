package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.files.FileCaps
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.util.LocaleUtil
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow

/**
 * A cell's text as a spreadsheet shows it, by POI's `DataFormatter`: one engine for the numbers of an .xls
 * and of an .xlsx, each from its value, its format's index and code — 347 cells of 347 as Excel shows them,
 * in both formats (prototype, docs D-006 and D-015).
 *
 * **The `java.awt.Color` trap.** `DataFormatter` hands every format of three sections or more (the
 * accounting "_-* # ##0,00 €") and every conditional one to `CellFormat`, whose `CellFormatPart.<clinit>`
 * creates a `java.awt.Color`, absent from Android: a `NoClassDefFoundError` `DataFormatter` does not catch,
 * which killed the process on a thread without a guard (measured). Such a format NEVER reaches `CellFormat`
 * here: [formatSections] picks its section as Excel does and hands `DataFormatter` that section alone.
 *
 * The differences measured between POI and Excel, corrected: the built-in formats 14 and 22 (the system's
 * short date for Excel, the American "m/d/yy" for POI); booleans (VRAI/FAUX in French); a percentage scaled
 * by thousands ("0.00,%", shown raw by POI); the time zone — POI goes through `java.util.Date` in the
 * device's zone, so an hour that does not exist there (the one skipped in spring) moved: every call runs
 * in UTC. And a format longer than Excel allows is shown as "General", never parsed.
 *
 * Not thread-safe, as `DataFormatter` is not: one per workbook, used under its lock.
 */
internal class CellText(locale: Locale) {
    private val formatter = DataFormatter(locale).apply { setUseCachedValuesForFormulaCells(true) }
    private val french = locale.language == FRENCH
    private val trueText = if (french) "VRAI" else "TRUE"
    private val falseText = if (french) "FAUX" else "FALSE"

    init {
        // Built-in formats 14 and 22: Excel shows the system's short date, POI names them "m/d/yy" and
        // "m/d/yy h:mm" and formats them the American way (10/9/26). addFormat replaces them by name.
        val shortDate = if (french) "dd/MM/yyyy" else "M/d/yyyy"
        formatter.addFormat("m/d/yy", SimpleDateFormat(shortDate, locale).apply { timeZone = UTC })
        formatter.addFormat("m/d/yy h:mm", SimpleDateFormat("$shortDate HH:mm", locale).apply { timeZone = UTC })
    }

    /** A number of a workbook in format [formatIndex] (its code [format]), dates of the 1904 calendar if [date1904]. */
    fun formatNumber(value: Double, formatIndex: Int, format: String, date1904: Boolean): String = inUtc {
        number(value, formatIndex, format, date1904) ?: formatter.formatRawCellContents(value, formatIndex, format, date1904)
    }

    /** A boolean as Excel shows it: VRAI/FAUX in French. */
    fun booleanText(value: Boolean): String = if (value) trueText else falseText

    /** A number whose format `DataFormatter` must not see whole; null when it may. */
    private fun number(value: Double, formatIndex: Int, format: String?, date1904: Boolean): String? = when {
        format == null || format.length > FileCaps.SHEET_FORMAT_CHARS ->
            formatter.formatRawCellContents(value, GENERAL_INDEX, GENERAL, date1904)
        needsCellFormat(format) -> formatSections(value, formatIndex, format, date1904)
        else -> scaledPercent(value, formatIndex, format, date1904)
    }

    /** "0.00,%": each trailing comma divides by a thousand (measured: POI shows the raw value). */
    private fun scaledPercent(value: Double, formatIndex: Int, format: String, date1904: Boolean): String? {
        val match = SCALED_PERCENT.find(format) ?: return null
        val scaled = value / THOUSAND.pow(match.groupValues[2].length)
        return formatter.formatRawCellContents(scaled, formatIndex, format.replace(match.value, match.groupValues[1] + "%"), date1904)
    }

    /**
     * A format of several sections: the section picked as Excel does (its conditions, else
     * positive;negative;zero), stripped of its colours and conditions, then handed to `DataFormatter` which,
     * without a ";", no longer goes through `CellFormat`. The 1904 calendar is passed on: a date in a format
     * of sections is a date too (the prototype dropped it there).
     */
    internal fun formatSections(value: Double, formatIndex: Int, format: String, date1904: Boolean): String {
        val numeric = splitSections(format).take(NUMERIC_SECTIONS)
        val (index, shown) = pickSection(numeric, value)
        val section = cleanSection(numeric[index])
        return when {
            section.isBlank() -> ""
            // A zero shown by a section without a "0" placeholder (the accounting zero, `"-"??`): Excel
            // shows its literals only; DataFormatter would write "0.0" (measured).
            shown == 0.0 && !hasZeroPlaceholder(section) -> literalsOnly(section)
            else -> formatter.formatRawCellContents(shown, formatIndex, section, date1904)
        }
    }

    private inline fun <T> inUtc(block: () -> T): T {
        LocaleUtil.setUserTimeZone(UTC)
        try {
            return block()
        } finally {
            LocaleUtil.resetUserTimeZone()
        }
    }

    companion object {
        private const val FRENCH = "fr"
        private const val GENERAL = "General"
        private const val GENERAL_INDEX = 0
        private const val THOUSAND = 1000.0
        private const val NUMERIC_SECTIONS = 3
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        /** The test `DataFormatter.getFormat` (POI 5.5.1) makes to call `CellFormat`. */
        private val RANGE_CONDITIONAL = Regex(".*\\[\\s*(>|>=|<|<=|=)\\s*[0-9]*\\.*[0-9].*")
        private val CONDITION = Regex("^\\[\\s*(<=|>=|<>|<|>|=)\\s*(-?[0-9]*\\.?[0-9]+)\\s*]")
        private val CONDITION_ANYWHERE = Regex("\\[\\s*(<=|>=|<>|<|>|=)\\s*-?[0-9]*\\.?[0-9]+\\s*]")
        private val SCALED_PERCENT = Regex("([0#?])(,+)%")
        private val COLOR = Regex("\\[(?i)(black|blue|cyan|green|magenta|red|white|yellow|color\\s*[0-9]+)]")

        /** Whether `DataFormatter` would hand [format] to `CellFormat`: two ";" or more, or a condition. */
        fun needsCellFormat(format: String): Boolean {
            val first = format.indexOf(';')
            return first >= 0 && (first != format.lastIndexOf(';') || RANGE_CONDITIONAL.matches(format))
        }

        /**
         * The section of [sections] that shows [value], and the value it shows: the first whose condition
         * holds, else the last without one; without conditions positive;negative;zero. Excel drops the sign
         * of a negative shown by another section than the first.
         */
        private fun pickSection(sections: List<String>, value: Double): Pair<Int, Double> {
            val conditions = sections.map(::conditionOf)
            val index = if (conditions.any { it != null }) {
                conditions.indexOfFirst { it != null && it(value) }.takeIf { it >= 0 }
                    ?: sections.indices.lastOrNull { conditions[it] == null } ?: 0
            } else {
                when {
                    value > 0 || sections.size == 1 -> 0
                    value < 0 -> 1
                    else -> if (sections.size >= NUMERIC_SECTIONS) 2 else 0
                }
            }
            return index to if (index > 0 && value < 0) -value else value
        }

        /** Whether [section] holds a "0" outside quotes and not escaped. */
        internal fun hasZeroPlaceholder(section: String): Boolean {
            var quoted = false
            var i = 0
            while (i < section.length) {
                val c = section[i]
                when {
                    c == '"' -> quoted = !quoted
                    !quoted && c == '\\' -> i++
                    !quoted && c == '0' -> return true
                }
                i++
            }
            return false
        }

        /** The literals alone: quoted text, escaped chars; the placeholders dropped. */
        internal fun literalsOnly(section: String): String {
            val out = StringBuilder()
            var quoted = false
            var i = 0
            while (i < section.length) {
                val c = section[i]
                when {
                    c == '"' -> quoted = !quoted
                    quoted -> out.append(c)
                    c == '\\' && i + 1 < section.length -> out.append(section[++i])
                    c in PLACEHOLDERS -> Unit
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        private const val PLACEHOLDERS = "#?,."

        /** What takes the char after it, outside quotes: an escape, a fill (`*`), a space as wide as a char (`_`). */
        private const val ESCAPES = "\\*_"

        /** Cut at the ";" outside quotes and brackets, not escaped. */
        internal fun splitSections(format: String): List<String> {
            val out = mutableListOf<String>()
            var start = 0
            var quoted = false
            var bracket = false
            var i = 0
            while (i < format.length) {
                when (format[i]) {
                    '\\' -> i++
                    '"' -> quoted = !quoted
                    '[' -> bracket = bracket || !quoted
                    ']' -> bracket = bracket && quoted
                    ';' -> if (!quoted && !bracket) out += format.substring(start, i).also { start = i + 1 }
                }
                i++
            }
            out += format.substring(start)
            return out
        }

        /** The condition heading [section] (after its colours, if any), or null. */
        internal fun conditionOf(section: String): ((Double) -> Boolean)? {
            val match = CONDITION.find(COLOR.replace(section, "").trimStart()) ?: return null
            val bound = match.groupValues[2].toDouble()
            return when (match.groupValues[1]) {
                "<" -> { v -> v < bound }
                "<=" -> { v -> v <= bound }
                ">" -> { v -> v > bound }
                ">=" -> { v -> v >= bound }
                "=" -> { v -> v == bound }
                else -> { v -> v != bound }
            }
        }

        /**
         * [section] without its colours and conditions; `* x` (fill to the column's width) becomes one `x`,
         * `_x` (a space as wide as x) a space: a cell of the grid has no Excel column width.
         */
        internal fun cleanSection(section: String): String {
            val s = CONDITION_ANYWHERE.replace(COLOR.replace(section, ""), "")
            val out = StringBuilder()
            var quoted = false
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val next = s.getOrNull(i + 1)
                if (c == '"') quoted = !quoted
                when {
                    quoted || next == null || c !in ESCAPES -> out.append(c)
                    c == '\\' -> out.append(c).append(next)
                    c == '*' -> out.append('"').append(next).append('"')
                    else -> out.append("\" \"")
                }
                if (!quoted && next != null && c in ESCAPES) i++
                i++
            }
            return out.toString()
        }
    }
}
