package com.readfilestech.read_files_tech.data.text

/** How a CSV is written: its field separator, and where its rows begin (after an Excel `sep=` line). */
data class CsvDialect(val separator: Char, val start: Int)

/**
 * CSV text into rows of fields (RFC 4180): fields separated by a separator, a field in double quotes
 * may hold the separator, line breaks and `""` for one quote; `\r\n`, `\n` and `\r` end a row. A quote
 * inside an unquoted field is kept as it is, and an unterminated quoted field ends with the text —
 * nothing is ever dropped. Malformed quotes are read as Excel reads them (measured through COM, Excel
 * 16, 2026-10-12): `"5" screen",x` is `5 screen"` then `x` — the 2.15.3's `csv` 8 package kept that
 * quote and stayed quoted. The scanning itself is [CsvScanner]'s, shared with the CSV viewer.
 */
object CsvRows {
    fun parse(text: String, separator: Char = ',', maxRows: Int = Int.MAX_VALUE): List<List<String>> {
        val units = CharsText(text)
        val scanner = CsvScanner(units, separator)
        val rows = mutableListOf<List<String>>()
        var position = 0
        while (position < text.length && rows.size < maxRows) {
            val row = mutableListOf<String>()
            scanner.record(position) { _, start, end ->
                row += units.field(start, end)
                true
            }
            rows += row
            position = scanner.end
        }
        return rows
    }

    /** The separator of [text] and where its rows begin, as [dialectOf] below. */
    fun dialectOf(text: CharSequence): CsvDialect = dialectOf(CharsText(text), 0)

    /**
     * The dialect of the CSV whose text begins at [from] (after its byte order mark), as the 2.15.3's
     * `csv` 8 found it (`CsvDecoder._checkBOMAndSep`, `_detectDelimiter`):
     * - an Excel `sep=` first line names the separator, and the rows begin after it;
     * - otherwise each of `,` `;` tab `|` scores, over the first [DETECT_RECORDS] lines, the separators
     *   it finds plus 2 for each line with as many as the previous one that had any; the highest wins,
     *   the first of the list on a tie, `,` when none is found.
     *
     * Three departures, each where `csv` 8 split a file wrongly: only the separators OUTSIDE quotes count,
     * and a line break inside quotes ends no line (a French file of `;` with commas in its quoted text);
     * a separator absent from the first lines never wins, where `csv` 8 took the first of the list found
     * ANYWHERE in the file (one `|` on line 500 of a file of one column split that line); and the hint of
     * `sep=` is one ASCII character other than the quote, a tab included (`csv` 8 trimmed `sep=\t` to
     * nothing) — any other hint is ignored and its line read as a row. Only the first [DETECT_UNITS] units
     * are read: a file of one endless line is not scanned four times.
     */
    internal fun dialectOf(text: CsvText, from: Int): CsvDialect = hintOf(text, from) ?: CsvDialect(detect(text, from), from)

    private fun hintOf(text: CsvText, from: Int): CsvDialect? {
        val lineEnd = sepLineEnd(text, from) ?: return null
        val crlf = text[lineEnd] == CsvScanner.CR && lineEnd + 1 < text.length && text[lineEnd + 1] == CsvScanner.LF
        return hintIn(text, from + SEP.length, lineEnd)?.let { CsvDialect(it, lineEnd + if (crlf) 2 else 1) }
    }

    /** Where the line break of a `sep=` first line is; null when the text does not begin with one. */
    private fun sepLineEnd(text: CsvText, from: Int): Int? {
        val valueStart = from + SEP.length
        if (text.length <= valueStart || SEP.indices.any { text[from + it] != SEP[it] }) return null
        val limit = minOf(text.length, valueStart + SEP_LINE_MAX)
        return (valueStart until limit).firstOrNull { text[it] == CsvScanner.CR || text[it] == CsvScanner.LF }
    }

    private fun hintIn(text: CsvText, from: Int, to: Int): Char? {
        val value = (from until to).map { text[it] }
        val hint = value.singleOrNull() ?: value.dropWhile(::isDartSpace).dropLastWhile(::isDartSpace).singleOrNull()
        return hint?.takeIf { it != CsvScanner.QUOTE && it.code < ASCII_LIMIT }
    }

    /** Dart's `String.trim` on ASCII: tab to carriage return, and the space. */
    private fun isDartSpace(c: Char): Boolean = c == ' ' || c in '\t'..'\r'

    private fun detect(text: CsvText, from: Int): Char {
        val limit = minOf(text.length.toLong(), from.toLong() + DETECT_UNITS).toInt()
        var best = CANDIDATES.first()
        var bestScore = 0
        for (candidate in CANDIDATES) {
            val score = score(CsvScanner(text, candidate, limit), from, limit)
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun score(scanner: CsvScanner, from: Int, limit: Int): Int {
        var position = from
        var total = 0
        var bonus = 0
        var last = -1
        var lines = 0
        while (lines < DETECT_RECORDS && position < limit) {
            scanner.record(position)
            val separators = scanner.fieldCount - 1
            if (separators > 0) {
                total += separators
                if (separators == last) bonus += CONSISTENCY_BONUS
                last = separators
            }
            position = scanner.end
            lines++
        }
        return total + bonus
    }

    private const val SEP = "sep="

    /** `sep=` and its value: a longer first line is a row, never a hint. */
    private const val SEP_LINE_MAX = 16
    private const val ASCII_LIMIT = 0x80
    private val CANDIDATES = charArrayOf(',', ';', '\t', '|')
    private const val CONSISTENCY_BONUS = 2

    /** The lines `csv` 8 scored. */
    const val DETECT_RECORDS = 10

    /** What the detection reads at most. */
    const val DETECT_UNITS = 1 shl 20
}
