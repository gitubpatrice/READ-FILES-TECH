package com.readfilestech.read_files_tech.data.text

/**
 * CSV text into rows of fields (RFC 4180): fields separated by a separator, a field in double quotes
 * may hold the separator, line breaks and `""` for one quote; `\r\n`, `\n` and `\r` end a row. A quote
 * inside an unquoted field is kept as it is, and an unterminated quoted field ends with the text —
 * nothing is ever dropped.
 */
object CsvRows {
    fun parse(text: String, separator: Char = ',', maxRows: Int = Int.MAX_VALUE): List<List<String>> =
        Parser(text, separator, maxRows).run()

    private class Parser(private val text: String, private val separator: Char, private val maxRows: Int) {
        private val rows = mutableListOf<List<String>>()
        private val row = mutableListOf<String>()
        private val field = StringBuilder()
        private var quoted = false

        /** A field has begun, even an empty quoted one (`""`): the last row is not lost for it. */
        private var started = false
        private var i = 0

        fun run(): List<List<String>> {
            while (i < text.length && rows.size < maxRows) {
                if (quoted) inQuotes(text[i]) else outsideQuotes(text[i])
                i++
            }
            val pending = field.isNotEmpty() || row.isNotEmpty() || started
            if (rows.size < maxRows && pending) endRow()
            return rows
        }

        private fun inQuotes(c: Char) {
            when {
                c != '"' -> field.append(c)
                text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                else -> quoted = false
            }
        }

        private fun outsideQuotes(c: Char) {
            when {
                c == separator -> endField()
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    endRow()
                }
                c == '"' && !started -> {
                    quoted = true
                    started = true
                }
                else -> {
                    field.append(c)
                    started = true
                }
            }
        }

        private fun endField() {
            row += field.toString()
            field.clear()
            started = false
        }

        private fun endRow() {
            endField()
            rows += row.toList()
            row.clear()
        }
    }
}
