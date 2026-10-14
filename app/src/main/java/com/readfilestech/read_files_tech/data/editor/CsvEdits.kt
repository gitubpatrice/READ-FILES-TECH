package com.readfilestech.read_files_tech.data.editor

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.text.CsvSafe
import com.readfilestech.read_files_tech.data.text.CsvScanner
import com.readfilestech.read_files_tech.data.text.quotedField
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.OutputStream
import java.nio.CharBuffer

/** What became of a cell the user typed ([CsvEdits.set]). */
enum class CellChange {
    /** The cell holds it now. */
    CHANGED,

    /** The cell held it already: nothing to save. */
    UNCHANGED,

    /** A character of it has no place in the file's encoding (Windows-1252): the cell is left as it was. */
    UNWRITABLE,

    /** Longer than a cell may be ([FileCaps.CSV_CELL_CHARS]), or the cell's text in the file is: writing it back would cut it. */
    TOO_LONG,
}

/**
 * A CSV opened in the CSV editor (`CsvEditorScreen` of the 2.15.3): the CSV viewer's table ([CsvTable] —
 * the same encoding, separator, header, rows and caps), and the user's changes kept APART from it: the
 * cells typed, the rows and the columns added or deleted. [writeTo] writes the file back from its own
 * bytes, so that what the user did not touch is copied as it was.
 *
 * The 2.15.3 parsed the file into lists and wrote them all back through `CsvSafe.encodeSafe` (D5): the
 * separator forced to `,`, the line breaks to CRLF, the empty lines lost, a `'` before every `-5` and
 * `+33…` of the file, the encoding of a Windows-1252 file changed to UTF-8. Here a file saved without a
 * change is the same file, byte for byte; a cell changed changes that cell's bytes only — its quotes kept
 * when it had some, added when its new text needs them — in the file's encoding. A row is written again
 * only when a cell of it was typed or the columns changed: its untouched fields are copied as they were,
 * its line break too; the empty lines, the rows of empty fields (`;;;`), an Excel `sep=` line, the
 * byte order mark, and whatever lies past the viewer's caps (rows, columns) are copied as they were.
 *
 * Rows of any length (D4: the 2.15.3 threw on a short row, and after adding then deleting a column): a
 * row is not padded to the table's width; a column added in its middle adds an empty field to it, one
 * added past its end adds nothing but to the header — which keeps the table's width once saved.
 *
 * Not thread-safe: changed on one thread, written while it does not change.
 */
class CsvEdits private constructor(private val bytes: ByteArray, private val table: CsvTable) {
    private val text = table.text

    val encoding: TextEncoding get() = table.encoding
    val separator: Char get() = table.separator

    /** No row to edit: an empty file, or nothing but empty lines. */
    val isEmpty: Boolean get() = table.isEmpty

    /** Rows past [FileCaps.CSV_ROWS] are not shown — and are written back as they were. */
    val rowsCut: Boolean get() = table.rowsCut

    /** Cells past [FileCaps.CSV_COLUMNS] are not shown — and are written back as they were. */
    val columnsCut: Boolean get() = table.columnsCut

    /** The rows shown, by position: a row of the file (its index, 0 the header), or an added one (-1 - its index in [added]). */
    private var order = IntArray(table.records) { it }

    /** The rows, the header (position 0) included. */
    var rowCount: Int = table.records
        private set

    /** The columns shown, by position: a column of the file (its index), or an added one (negative). */
    private var keys = IntArray(table.columns) { it }

    var columnCount: Int = table.columns
        private set

    /** The cells typed in rows of the file: row → column key → text. A cell typed back to its text in the file is not kept. */
    private val typed = HashMap<Int, HashMap<Int, String>>()

    /** The cells of the rows added, by column key; a missing one is empty. */
    private val added = ArrayList<HashMap<Int, String>>()
    private var addedColumns = 0
    private var reshaped = false

    /** Whether saving would write something else than the file. */
    val modified: Boolean get() = reshaped || typed.isNotEmpty()

    val canAddRow: Boolean get() = !isEmpty && rowCount < FileCaps.CSV_ROWS
    val canAddColumn: Boolean get() = !isEmpty && columnCount < FileCaps.CSV_COLUMNS
    val canDeleteColumn: Boolean get() = columnCount > 1

    /** The header (position 0) is never deleted, as in the 2.15.3. */
    fun canDeleteRow(position: Int): Boolean = position in 1 until rowCount

    /**
     * The cells [from] until [until] of the row at [position] (0 the header), each of at most [maxChars]
     * characters; "" where it has none.
     */
    fun cells(position: Int, from: Int, until: Int, maxChars: Int): List<String> {
        require(position in 0 until rowCount) { "row $position of $rowCount" }
        require(from in 0..until && until <= columnCount) { "columns $from until $until of $columnCount" }
        val entry = order[position]
        if (entry < 0) {
            val row = added[-1 - entry]
            return (from until until).map { row[keys[it]].orEmpty() }
        }
        val edits = typed[entry]
        // One reading of the row for its columns of the file that show and were not typed.
        var low = Int.MAX_VALUE
        var high = -1
        for (column in from until until) {
            val key = keys[column]
            if (key >= 0 && edits?.containsKey(key) != true) {
                low = minOf(low, key)
                high = maxOf(high, key)
            }
        }
        val original = if (high >= 0) table.recordCells(entry, low, high + 1, maxChars) else emptyList()
        return (from until until).map { column ->
            val key = keys[column]
            edits?.get(key) ?: if (key >= 0) original[key - low] else ""
        }
    }

    /** The whole text of a cell, to edit it; null when it is longer than a cell may be ([FileCaps.CSV_CELL_CHARS]). */
    fun cellForEditing(position: Int, column: Int): String? {
        // Two more: a text cut there may lose half of a pair of surrogates, and still be longer than the cap.
        val text = cells(position, column, column + 1, FileCaps.CSV_CELL_CHARS + 2).single()
        return text.takeIf { it.length <= FileCaps.CSV_CELL_CHARS }
    }

    /**
     * The cell at [position], [column] as the user [typed] it. A text that is the cell's is no change —
     * a formula of the file confirmed as it was stays as it was; any other goes through the formula guard
     * ([CsvSafe.sanitizeCell]: `=SUM(A1)` becomes `'=SUM(A1)`), then must fit the file's encoding.
     */
    fun set(position: Int, column: Int, typed: String): CellChange {
        val current = cellForEditing(position, column) ?: return CellChange.TOO_LONG
        val value = CsvSafe.sanitizeCell(typed)
        return when {
            typed == current || value == current -> CellChange.UNCHANGED
            value.length > FileCaps.CSV_CELL_CHARS -> CellChange.TOO_LONG
            !encoding.charset.newEncoder().canEncode(value) -> CellChange.UNWRITABLE
            else -> {
                store(order[position], keys[column], value)
                CellChange.CHANGED
            }
        }
    }

    private fun store(entry: Int, key: Int, value: String) {
        if (entry < 0) {
            val row = added[-1 - entry]
            if (value.isEmpty()) row.remove(key) else row[key] = value
            return
        }
        val original = if (key >= 0) table.recordCells(entry, key, key + 1, FileCaps.CSV_CELL_CHARS + 2).single() else ""
        val edits = typed.getOrPut(entry) { HashMap() }
        if (value == original) edits.remove(key) else edits[key] = value
        if (edits.isEmpty()) typed.remove(entry)
    }

    /** A row of empty cells under the row at [after] (the header: 0). */
    fun insertRow(after: Int) {
        check(canAddRow) { "no row can be added" }
        require(after in 0 until rowCount) { "row $after of $rowCount" }
        added += HashMap()
        if (rowCount == order.size) order = order.copyOf(minOf(maxOf(order.size * 2, MIN_ROWS), FileCaps.CSV_ROWS))
        val at = after + 1
        order.copyInto(order, at + 1, at, rowCount)
        order[at] = -added.size
        rowCount++
        reshaped = true
    }

    fun deleteRow(position: Int) {
        require(canDeleteRow(position)) { "row $position of $rowCount cannot be deleted" }
        typed.remove(order[position])
        order.copyInto(order, position, position + 1, rowCount)
        rowCount--
        reshaped = true
    }

    /** An empty column at [at] (0 before the first, [columnCount] after the last). */
    fun insertColumn(at: Int) {
        check(canAddColumn) { "no column can be added" }
        require(at in 0..columnCount) { "column $at of $columnCount" }
        val grown = keys.copyOf(columnCount + 1)
        keys.copyInto(grown, at + 1, at, columnCount)
        grown[at] = -1 - addedColumns++
        keys = grown
        columnCount++
        reshaped = true
    }

    fun deleteColumn(column: Int) {
        check(canDeleteColumn) { "the last column cannot be deleted" }
        require(column in 0 until columnCount) { "column $column of $columnCount" }
        val key = keys[column]
        keys.copyInto(keys, column, column + 1, columnCount)
        columnCount--
        typed.values.forEach { it.remove(key) }
        typed.values.removeAll { it.isEmpty() }
        added.forEach { it.remove(key) }
        reshaped = true
    }

    /** The file as it is now, in its own encoding and dialect. */
    fun writeTo(out: OutputStream) {
        if (isEmpty) out.write(bytes, 0, text.byteAt(text.length)) else Writing(out).all()
    }

    /** One writing of the file: [open] while the last row written still lacks its line break. */
    private inner class Writing(private val out: OutputStream) {
        private val encoder = encoding.charset.newEncoder()
        private val separatorBytes = encode(separator.toString())
        private val identity = columnCount == table.columns && (0 until columnCount).all { keys[it] == it }

        /** Where each field of the row read last lies in [text], for the columns of the table. */
        private val fields = IntArray(2 * table.columns)
        private var fieldCount = 0
        private var hiddenStart = 0
        private var contentEnd = 0
        private var recordEnd = 0

        private val lineBreak: ByteArray = run {
            scan(0)
            if (contentEnd < recordEnd) bytes.copyOfRange(text.byteAt(contentEnd), text.byteAt(recordEnd)) else encode(CRLF)
        }

        /**
         * Whether the last row of the file ends with a line break of its own — what a row added after it
         * ends with. Not the last character: a quote never closed holds the line breaks after it.
         */
        private val endsWithLineBreak: Boolean = run {
            scan(table.records - 1)
            contentEnd < recordEnd
        }
        private var open = false

        /** The record whose gap — the empty rows up to the next row — is still to write; -1 for none. */
        private var pending = -1
        private var next = 0

        fun all() {
            raw(0, text.byteAt(table.recordStart(0)))
            for (position in 0 until rowCount) {
                val entry = order[position]
                if (entry < 0) {
                    addedRow(added[-1 - entry])
                } else {
                    gapsUntil(entry)
                    fileRow(entry)
                    pending = entry
                    next = entry + 1
                }
            }
            gapsUntil(table.records)
            if (open && endsWithLineBreak) out.write(lineBreak)
        }

        /** The gaps of the pending row and of the rows deleted before [record]: empty lines the user never saw stay. */
        private fun gapsUntil(record: Int) {
            if (pending >= 0) gap(pending, recordEnd)
            for (deleted in next until record) {
                scan(deleted)
                gap(deleted, recordEnd)
            }
            pending = -1
            next = record
        }

        private fun gap(record: Int, end: Int) {
            val until = if (record + 1 < table.records) table.recordStart(record + 1) else text.length
            if (until > end) {
                closeRow()
                raw(text.byteAt(end), text.byteAt(until))
            }
        }

        private fun fileRow(record: Int) {
            closeRow()
            scan(record)
            val edits = typed[record]
            if (identity && edits == null) {
                raw(text.byteAt(table.recordStart(record)), text.byteAt(recordEnd))
            } else {
                rebuild(record, edits)
                raw(text.byteAt(contentEnd), text.byteAt(recordEnd))
            }
            open = contentEnd == recordEnd
        }

        /** A row of the file written again: its untouched fields as they were, the typed ones encoded, the columns in their new order. */
        private fun rebuild(record: Int, edits: Map<Int, String>?) {
            val shown = minOf(fieldCount, table.columns)
            val length = (columnCount downTo 1).firstOrNull { column ->
                val key = keys[column - 1]
                key in 0 until shown || edits?.containsKey(key) == true || (record == 0 && key < 0)
            } ?: 0
            for (column in 0 until length) {
                if (column > 0) out.write(separatorBytes)
                val key = keys[column]
                val inFile = key in 0 until shown
                val start = if (inFile) fields[2 * key] else 0
                val end = if (inFile) fields[2 * key + 1] else 0
                val value = edits?.get(key)
                when {
                    // Its quotes kept: an empty field has no first character.
                    value != null -> {
                        val quoted = start < end && text[start] == CsvScanner.QUOTE
                        out.write(encode(quotedField(value, separator, quoted)))
                    }
                    inFile -> raw(text.byteAt(start), text.byteAt(end))
                }
            }
            // The fields past the viewer's cap of columns, as they were, after the columns shown.
            if (fieldCount > table.columns) {
                out.write(separatorBytes)
                raw(text.byteAt(hiddenStart), text.byteAt(contentEnd))
            }
        }

        private fun addedRow(row: Map<Int, String>) {
            closeRow()
            for (column in 0 until columnCount) {
                if (column > 0) out.write(separatorBytes)
                row[keys[column]]?.let { out.write(encode(quotedField(it, separator))) }
            }
            open = true
        }

        /** The line break of a row written without one, before anything follows it. */
        private fun closeRow() {
            if (open) out.write(lineBreak)
            open = false
        }

        /** Reads where the fields of [record] lie, and where it ends. */
        private fun scan(record: Int) {
            val scanner = CsvScanner(text, separator)
            fieldCount = 0
            scanner.record(table.recordStart(record)) { index, start, end ->
                if (index < table.columns) {
                    fields[2 * index] = start
                    fields[2 * index + 1] = end
                } else if (index == table.columns) {
                    hiddenStart = start
                }
                contentEnd = end
                true
            }
            fieldCount = scanner.fieldCount
            recordEnd = scanner.end
        }

        private fun raw(from: Int, to: Int) {
            if (to > from) out.write(bytes, from, to - from)
        }

        /** Strict: a character the encoding lacks fails the writing ([set] refused it already). */
        private fun encode(value: String): ByteArray {
            val buffer = encoder.reset().encode(CharBuffer.wrap(value))
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
    }

    companion object {
        /** Reads [source] whole, within the CSV viewer's cap ([FileCaps.CSV_FILE]). */
        suspend fun read(source: ViewerSource): CsvEdits {
            val (bytes, size) = source.open(FileCaps.CSV_FILE).use { TextDocument.readAll(it, source.length(), FileCaps.CSV_FILE) }
            return of(bytes, size)
        }

        /** The CSV of the first [size] bytes of [bytes], kept — not copied — whole. */
        suspend fun of(bytes: ByteArray, size: Int = bytes.size): CsvEdits = CsvEdits(bytes, CsvTable.of(bytes, size, whole = true))

        private const val CRLF = "\r\n"
        private const val MIN_ROWS = 16
    }
}

/** The cells [from] until [until] of row [record] of the file, 0 its header. */
private fun CsvTable.recordCells(record: Int, from: Int, until: Int, maxChars: Int): List<String> =
    if (record == 0) header(from, until, maxChars) else cells(record - 1, from, until, maxChars)
