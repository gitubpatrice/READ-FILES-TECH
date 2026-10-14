package com.readfilestech.read_files_tech.data.editor

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.text.CsvRows
import com.readfilestech.read_files_tech.data.text.CsvSafe
import com.readfilestech.read_files_tech.data.text.CsvScanner
import com.readfilestech.read_files_tech.data.text.quotedField
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.OutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException

/** What became of a cell the user typed ([CsvEdits.set]). */
enum class CellChange {
    /** The cell holds it now. */
    CHANGED,

    /** The cell held it already: nothing to save. */
    UNCHANGED,

    /** A character of it has no place in the encoding the file is saved in: the cell is left as it was. */
    UNWRITABLE,

    /** Longer than a cell may be ([FileCaps.CSV_CELL_CHARS]), or the cell's text in the file is: writing it back would cut it. */
    TOO_LONG,

    /** The cell's bytes do not decode to a text that encodes back to them: changing it would change bytes never shown. */
    UNDECODABLE,

    /** A character outside ASCII in a file that does not say its encoding: [CsvEdits.chosenEncoding] first. */
    ENCODING_UNCHOSEN,

    /** The file is shown, not edited ([CsvEdits.readOnly]). */
    READ_ONLY,
}

/** A cell as the editor can take it ([CsvEdits.editable]): its text, or why it cannot be edited. */
sealed interface CellText {
    data class Editable(val text: String) : CellText

    /** Longer than a cell may be ([FileCaps.CSV_CELL_CHARS]): rewriting it would cut it. */
    data object TooLong : CellText

    /** Its bytes do not decode to a text that encodes back to them (a byte the encoding lacks, a cut character). */
    data object Undecodable : CellText
}

/** Why a file is shown, not edited (Claude review R20). */
enum class ReadOnly {
    /** A NUL byte in a text of one byte per character: UTF-16 without a byte order mark, or not a text. */
    NUL_BYTES,

    /** Read as Windows-1252, but its accents sit where the MS-DOS (850) or Mac code pages put theirs. */
    DOS_CODE_PAGE,
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
 * The encoding is the file's, when the file says it (a byte order mark, or UTF-8 beyond ASCII); a file
 * that does not — pure ASCII, or read as Windows-1252 because it is not UTF-8 — has its encoding chosen
 * by the user at the first character outside ASCII typed ([chosenEncoding]). A file the editor would
 * damage is shown and not edited ([readOnly]); so is a cell whose bytes would not come back ([editable]).
 *
 * Not thread-safe: changed on one thread, written while it does not change.
 */
class CsvEdits private constructor(private val bytes: ByteArray, private val table: CsvTable, facts: ByteFacts) {
    private val text = table.text

    val encoding: TextEncoding get() = table.encoding
    val separator: Char get() = table.separator

    /** Why the file is shown and not edited; null when it is edited. */
    val readOnly: ReadOnly? = facts.readOnly

    /** Whether the file says its encoding: a byte order mark, or UTF-8 holding characters outside ASCII. */
    val encodingStated: Boolean = encoding.bomLength > 0 || (encoding == TextEncoding.UTF_8 && facts.nonAscii)

    /**
     * What a file that does not say its encoding may be saved in, asked at the first character outside ASCII
     * typed: UTF-8 or Windows-1252 for a file of ASCII only; Windows-1252 alone for one read as Windows-1252
     * — its own accents are, and UTF-8 typed among them would read wrong in every program.
     */
    val encodingChoices: List<TextEncoding> = when {
        encodingStated || readOnly != null -> emptyList()
        facts.nonAscii -> listOf(TextEncoding.WINDOWS_1252)
        else -> listOf(TextEncoding.UTF_8, TextEncoding.WINDOWS_1252)
    }

    /** The user's answer for a file that does not say its encoding, kept while the file is open. */
    var chosenEncoding: TextEncoding? = null
        set(value) {
            require(value == null || value in encodingChoices) { "not a choice for this file: $value" }
            field = value
        }

    /** What the cells typed are written in. */
    val writeEncoding: TextEncoding get() = chosenEncoding ?: encoding

    /**
     * Without an Excel `sep=` line, the separator is found again at each reading: a typed text holding any
     * of the separators weighed is quoted, so that it never tips the next reading (Claude review R20).
     */
    private val quoting: CharArray = if (CsvRows.dialectOf(text, 0).start > 0) CharArray(0) else CsvRows.CANDIDATES

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

    val canAddRow: Boolean get() = readOnly == null && !isEmpty && rowCount < FileCaps.CSV_ROWS
    val canAddColumn: Boolean get() = readOnly == null && !isEmpty && columnCount < FileCaps.CSV_COLUMNS
    val canDeleteColumn: Boolean get() = readOnly == null && columnCount > 1

    /** The header (position 0) is never deleted, as in the 2.15.3. */
    fun canDeleteRow(position: Int): Boolean = readOnly == null && position in 1 until rowCount

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

    /**
     * The whole text of a cell, to edit it — or why it cannot be: longer than a cell may be, or bytes of the
     * file that its text would not give back once encoded (a byte Windows-1252 lacks, a character cut short,
     * the odd last byte of UTF-16). U+FFFD in its place would have replaced them (Claude review R20).
     */
    fun editable(position: Int, column: Int): CellText {
        // Two more: a text cut there may lose half of a pair of surrogates, and still be longer than the cap.
        val shown = cells(position, column, column + 1, FileCaps.CSV_CELL_CHARS + 2).single()
        val entry = order[position]
        val key = keys[column]
        val fromFile = entry >= 0 && key >= 0 && typed[entry]?.containsKey(key) != true
        return when {
            shown.length > FileCaps.CSV_CELL_CHARS -> CellText.TooLong
            fromFile && !table.fieldRoundTrips(bytes, entry, key) -> CellText.Undecodable
            else -> CellText.Editable(shown)
        }
    }

    /**
     * The cell at [position], [column] as the user [typed] it. A text that is the cell's is no change —
     * a formula of the file confirmed as it was stays as it was; any other goes through the formula guard
     * ([CsvSafe.sanitizeCell]: `=SUM(A1)` becomes `'=SUM(A1)`), then must fit the encoding it is saved in.
     */
    fun set(position: Int, column: Int, typed: String): CellChange {
        val cell = editable(position, column)
        val current = (cell as? CellText.Editable)?.text
        val value = CsvSafe.sanitizeCell(typed)
        val unchosen = chosenEncoding == null && encodingChoices.isNotEmpty() && value.any { it.code >= ASCII_END }
        val change = when {
            readOnly != null -> CellChange.READ_ONLY
            cell == CellText.TooLong -> CellChange.TOO_LONG
            current == null -> CellChange.UNDECODABLE
            typed == current || value == current -> CellChange.UNCHANGED
            value.length > FileCaps.CSV_CELL_CHARS -> CellChange.TOO_LONG
            unchosen -> CellChange.ENCODING_UNCHOSEN
            !writeEncoding.charset.newEncoder().canEncode(value) -> CellChange.UNWRITABLE
            else -> CellChange.CHANGED
        }
        if (change == CellChange.CHANGED) store(order[position], keys[column], value)
        return change
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
        check(canDeleteColumn) { "this column cannot be deleted" }
        require(column in 0 until columnCount) { "column $column of $columnCount" }
        val key = keys[column]
        keys.copyInto(keys, column, column + 1, columnCount)
        columnCount--
        typed.values.forEach { it.remove(key) }
        typed.values.removeAll { it.isEmpty() }
        added.forEach { it.remove(key) }
        reshaped = true
    }

    /** The file as it is now, in its own dialect; the cells typed in [writeEncoding]. */
    fun writeTo(out: OutputStream) {
        if (isEmpty) out.write(bytes, 0, text.byteAt(text.length)) else Writing(out).all()
    }

    /** One writing of the file: [open] while the last row written still lacks its line break. */
    private inner class Writing(private val out: OutputStream) {
        private val encoder = writeEncoding.charset.newEncoder()
        private val separatorBytes = encode(separator.toString())

        /** A row that would write nothing before its line break is an empty line, which no reader counts as a row. */
        private val emptyField = encode(EMPTY_FIELD)
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
            val hidden = fieldCount > table.columns
            // A row holding fields past the cap writes every column before them, whatever it holds: they stay
            // under the columns they were under (Claude review R20). Otherwise a short row stays short.
            val length = if (hidden) columnCount else rowLength(keys, columnCount, shown, edits, header = record == 0)
            var wrote = length > 1
            for (column in 0 until length) {
                if (column > 0) out.write(separatorBytes)
                val key = keys[column]
                val inFile = key in 0 until shown
                val start = if (inFile) fields[2 * key] else 0
                val end = if (inFile) fields[2 * key + 1] else 0
                val value = edits?.get(key)
                val written = when {
                    // Its quotes kept: an empty field has no first character.
                    value != null -> {
                        val quoted = start < end && text[start] == CsvScanner.QUOTE || value.any { it in quoting }
                        encode(quotedField(value, separator, quoted))
                    }
                    inFile -> bytes.copyOfRange(text.byteAt(start), text.byteAt(end))
                    else -> ByteArray(0)
                }
                out.write(written)
                if (written.isNotEmpty()) wrote = true
            }
            // The fields past the viewer's cap of columns, as they were, after the columns shown.
            if (hidden) {
                out.write(separatorBytes)
                raw(text.byteAt(hiddenStart), text.byteAt(contentEnd))
            } else if (!wrote) {
                out.write(emptyField)
            }
        }

        private fun addedRow(row: Map<Int, String>) {
            closeRow()
            for (column in 0 until columnCount) {
                if (column > 0) out.write(separatorBytes)
                row[keys[column]]?.let { value -> out.write(encode(quotedField(value, separator, value.any { it in quoting }))) }
            }
            if (columnCount == 1 && row[keys[0]] == null) out.write(emptyField)
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
        suspend fun of(bytes: ByteArray, size: Int = bytes.size): CsvEdits {
            val table = CsvTable.of(bytes, size, whole = true)
            return CsvEdits(bytes, table, ByteFacts.of(bytes, size, table.encoding))
        }

        private const val CRLF = "\r\n"
        private const val EMPTY_FIELD = "\"\""
        private const val MIN_ROWS = 16
        private const val ASCII_END = 0x80
    }
}

/** What one pass over a file's bytes says of its encoding (Claude review R20). */
private class ByteFacts(val nonAscii: Boolean, val readOnly: ReadOnly?) {
    companion object {
        /**
         * In a text of one byte per character: whether a byte is outside ASCII; a NUL byte, which no text CSV
         * holds (UTF-16 without a byte order mark is one, every other byte); and, in a file read as
         * Windows-1252, a byte 0x80-0x9F that ends or splits a word — where MS-DOS 850 puts `é è à ç ô` and Mac
         * Roman `é è ê`, and Windows-1252 little a word holds ([isDosAccent] leaves it out).
         */
        fun of(bytes: ByteArray, size: Int, encoding: TextEncoding): ByteFacts {
            if (encoding.unitSize != 1) return ByteFacts(nonAscii = true, readOnly = null)
            val start = encoding.bomLength
            var nonAscii = false
            var nul = false
            var dos = false
            for (i in start until size) {
                val byte = bytes[i].toInt() and BYTE
                nul = nul || byte == 0
                nonAscii = nonAscii || byte >= ASCII_END
                dos = dos || encoding == TextEncoding.WINDOWS_1252 && isDosAccent(bytes, i, start, size)
            }
            val readOnly = when {
                nul -> ReadOnly.NUL_BYTES
                dos -> ReadOnly.DOS_CODE_PAGE
                else -> null
            }
            return ByteFacts(nonAscii, readOnly)
        }

        /**
         * A byte of 0x80–0x9F after an ASCII letter, where an MS-DOS or Mac accent stands: before another letter
         * (MS-DOS 850 `cafés`), or at the end of a word — before a separator, a quote, a line break or the end
         * (`Payé;`). Never the Windows-1252 characters found there in ordinary text: dashes, `š ž`, `‘ ’ Œ œ`
         * (`Paris–Lyon`, `Košice`, `l’homme`, `cœur`), and at a word's end `… ” ™` — so MS-DOS `voilà`, whose
         * `à` is the byte of `…`, goes unseen (Claude reviews R20, R21: a valid file read-only, even right after
         * the user's own save of an en dash).
         */
        private fun isDosAccent(bytes: ByteArray, i: Int, start: Int, size: Int): Boolean {
            val byte = bytes[i].toInt() and BYTE
            val afterLetter = i > start && isAsciiLetter(bytes[i - 1])
            if (byte !in DOS_ACCENTS || byte in WORD_PUNCTUATION_1252 || !afterLetter) return false
            val beforeLetter = i + 1 < size && isAsciiLetter(bytes[i + 1])
            return beforeLetter || byte !in END_PUNCTUATION_1252 && endsWord(bytes, i + 1, size)
        }

        private fun endsWord(bytes: ByteArray, next: Int, size: Int): Boolean =
            next == size || bytes[next].toInt().toChar().let { it in CsvRows.CANDIDATES || it == '"' || it == '\r' || it == '\n' }

        private fun isAsciiLetter(byte: Byte): Boolean = byte.toInt().toChar().let { it in 'a'..'z' || it in 'A'..'Z' }

        private const val BYTE = 0xFF
        private const val ASCII_END = 0x80
        private val DOS_ACCENTS = 0x80..0x9F

        /** Windows-1252 seen between letters in ordinary text: `Œ ‘ ’ – — š œ ž`. */
        private val WORD_PUNCTUATION_1252 = intArrayOf(0x8C, 0x91, 0x92, 0x96, 0x97, 0x9A, 0x9C, 0x9E)

        /** Windows-1252 seen at the end of a word: `… ” ™`. */
        private val END_PUNCTUATION_1252 = intArrayOf(0x85, 0x94, 0x99)
    }
}

/**
 * How many fields a row of the file writes once the columns changed: up to its last column of the file or
 * typed cell — a short row stays short —, and every column added for the [header], which keeps the width.
 */
private fun rowLength(keys: IntArray, columnCount: Int, shown: Int, edits: Map<Int, String>?, header: Boolean): Int =
    (columnCount downTo 1).firstOrNull { column ->
        val key = keys[column - 1]
        key in 0 until shown || edits?.containsKey(key) == true || (header && key < 0)
    } ?: 0

/** The cells [from] until [until] of row [record] of the file, 0 its header. */
private fun CsvTable.recordCells(record: Int, from: Int, until: Int, maxChars: Int): List<String> =
    if (record == 0) header(from, until, maxChars) else cells(record - 1, from, until, maxChars)

/**
 * Whether field [key] of row [record] decodes to a text that encodes back to its very bytes — a field the
 * row lacks does. The text is decoded raw, quotes included: only the encoding is checked here.
 */
private fun CsvTable.fieldRoundTrips(bytes: ByteArray, record: Int, key: Int): Boolean {
    val range = intArrayOf(-1, -1)
    CsvScanner(text, separator).record(recordStart(record)) { index, start, end ->
        if (index == key) {
            range[0] = start
            range[1] = end
        }
        index < key
    }
    if (range[0] < 0) return true
    val from = text.byteAt(range[0])
    val original = bytes.copyOfRange(from, text.byteAt(range[1]))
    val again = try {
        encoding.charset.newEncoder().encode(CharBuffer.wrap(text.decode(range[0], range[1])))
    } catch (ignored: CharacterCodingException) {
        null
    }
    return again != null && ByteArray(again.remaining()).also { again.get(it) }.contentEquals(original)
}
