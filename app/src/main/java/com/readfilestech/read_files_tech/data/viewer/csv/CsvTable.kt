package com.readfilestech.read_files_tech.data.viewer.csv

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.text.CsvRows
import com.readfilestech.read_files_tech.data.text.CsvScanner
import com.readfilestech.read_files_tech.data.text.field
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A CSV opened for the viewer (`CsvViewerScreen` of the 2.15.3): its bytes once, the start of each row
 * found in one pass, a row's cells decoded only when it is shown. The 2.15.3 parsed the whole file into
 * lists of strings — several hundred MB for its 50 MiB cap — and laid every cell out at once; a file
 * too large, in Windows-1252 or in UTF-16 showed "Fichier CSV vide" (inventory F19).
 *
 * The encoding is the text viewer's ([TextEncoding.detect]: a BOM, else UTF-8 when valid, else
 * Windows-1252 — an Excel CSV in French); the separator is found as the 2.15.3's `csv` 8 package found
 * it ([CsvRows.dialectOf]). The first row with content is the header, and a row of empty fields is not a
 * row — both as `csv` 8 read them (`skipEmptyLines`). At most [FileCaps.CSV_ROWS] rows and
 * [FileCaps.CSV_COLUMNS] columns: beyond, [rowsCut] and [columnsCut] say so.
 */
class CsvTable private constructor(internal val text: EncodedText, val separator: Char, private val index: RowIndex) {
    private val starts = index.starts

    val encoding: TextEncoding get() = text.encoding

    /** No row at all: nothing but empty lines, or nothing. */
    val isEmpty: Boolean get() = index.records == 0

    /** The rows with content, the header included. */
    internal val records: Int get() = index.records

    /** Where row [record] starts in [text] (0 is the header): the CSV editor copies what it did not touch from there. */
    internal fun recordStart(record: Int): Int = starts[record]

    /** The rows under the header. */
    val rows: Int get() = maxOf(0, index.records - 1)

    /** The widest row's cells, within [FileCaps.CSV_COLUMNS]. */
    val columns: Int get() = index.columns

    /** Rows past [FileCaps.CSV_ROWS] are not shown. */
    val rowsCut: Boolean get() = index.rowsCut

    /** Cells past [FileCaps.CSV_COLUMNS] are not shown. */
    val columnsCut: Boolean get() = index.columnsCut

    /** The header's cells [from] until [until], each of at most [maxChars] characters; "" where it has none. */
    fun header(from: Int, until: Int, maxChars: Int): List<String> = cellsOf(0, from, until, maxChars)

    /** The cells [from] until [until] of [row] (0 is the first under the header), as [header]. */
    fun cells(row: Int, from: Int, until: Int, maxChars: Int): List<String> {
        require(row in 0 until rows) { "row $row of $rows" }
        return cellsOf(row + 1, from, until, maxChars)
    }

    private fun cellsOf(record: Int, from: Int, until: Int, maxChars: Int): List<String> {
        require(from in 0..until && until <= columns) { "columns $from until $until of $columns" }
        val cells = MutableList(until - from) { "" }
        if (from < until) {
            CsvScanner(text, separator).record(starts[record]) { index, start, end ->
                if (index >= from) cells[index - from] = text.field(start, end, maxChars)
                index + 1 < until
            }
        }
        return cells
    }

    /** Gives [fields] the raw range of each field of [row], within [columns], while it returns true. */
    internal fun scan(row: Int, fields: CsvScanner.Fields) {
        CsvScanner(text, separator).record(starts[row + 1]) { index, start, end -> index < columns && fields.field(index, start, end) }
    }

    /** The raw text of [row], quotes and separators included, when it is at most [maxUnits] long; null otherwise. */
    internal fun rawRow(row: Int, maxUnits: Int): String? {
        val start = starts[row + 1]
        val scanner = CsvScanner(text, separator)
        scanner.record(start)
        return if (scanner.end - start <= maxUnits) text.decode(start, scanner.end) else null
    }

    /** The text of the raw field at [start] until [end] of this table, at most [maxChars] characters. */
    internal fun fieldText(start: Int, end: Int, maxChars: Int = FileCaps.CSV_CELL_CHARS): String = text.field(start, end, maxChars)

    /** A reader of raw fields one character at a time, for the sort's comparisons. */
    internal fun fieldChars(): FieldChars = FieldChars(text)

    companion object {
        /** Reads [source] whole, within the 2.15.3's cap (`FileCaps.CSV_FILE`). */
        suspend fun read(source: ViewerSource): CsvTable {
            val expected = source.length()
            val (bytes, size) = source.open(FileCaps.CSV_FILE).use { TextDocument.readAll(it, expected, FileCaps.CSV_FILE) }
            return of(bytes, size)
        }

        /**
         * The table of the first [size] bytes of [bytes] — kept, not copied, unless rows are cut and not
         * [whole]: the CSV editor keeps every byte, it writes back the rows it does not show.
         */
        suspend fun of(bytes: ByteArray, size: Int = bytes.size, whole: Boolean = false): CsvTable {
            val text = EncodedText(bytes, size, TextEncoding.detect(bytes, size))
            val dialect = CsvRows.dialectOf(text, 0)
            return index(text, dialect.separator, dialect.start, whole)
        }

        /**
         * One pass of the scanner: where each row with content starts, the widest row. Past the cap of
         * rows, the bytes of the rows not shown are let go — a file of 50 MiB of one-letter rows keeps 2.
         */
        private suspend fun index(text: EncodedText, separator: Char, from: Int, whole: Boolean): CsvTable {
            val scanner = CsvScanner(text, separator)
            var starts = IntArray(INITIAL_ROWS)
            var count = 0
            var widest = 0
            var position = from
            var checked = from
            var cut = false
            var kept = from
            while (position < text.length && !cut) {
                if (position - checked >= CANCEL_CHECK_EVERY) {
                    currentCoroutineContext().ensureActive()
                    checked = position
                }
                scanner.record(position)
                cut = scanner.hasContent && count == FileCaps.CSV_ROWS
                if (scanner.hasContent && !cut) {
                    if (count == starts.size) starts = starts.copyOf(minOf(starts.size * 2, FileCaps.CSV_ROWS))
                    starts[count++] = position
                    widest = maxOf(widest, scanner.fieldCount)
                    kept = scanner.end
                }
                position = scanner.end
            }
            val columns = minOf(widest, FileCaps.CSV_COLUMNS)
            val index = RowIndex(starts, count, columns, rowsCut = cut, columnsCut = widest > columns)
            return CsvTable(if (cut && !whole) text.prefix(kept) else text, separator, index)
        }

        /** What the screen decodes of a cell: two lines of a column show far less, the rest is never read. */
        const val SHOWN_CHARS = 256

        private const val INITIAL_ROWS = 1024
        private const val CANCEL_CHECK_EVERY = 1 shl 20
    }
}

/** Where the rows with content start (the header first), and the table's bounds. */
internal class RowIndex(val starts: IntArray, val records: Int, val columns: Int, val rowsCut: Boolean, val columnsCut: Boolean)
