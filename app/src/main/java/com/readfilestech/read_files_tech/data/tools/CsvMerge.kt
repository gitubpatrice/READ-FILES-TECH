package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.convert.NothingToConvertException
import com.readfilestech.read_files_tech.data.convert.SelectionTooLargeException
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.text.CsvRows
import com.readfilestech.read_files_tech.data.text.CsvScanner
import com.readfilestech.read_files_tech.data.text.field
import com.readfilestech.read_files_tech.data.text.quotedField
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.csv.EncodedText
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter

/** The merge refuses the file [name], for [reason]: nothing is written. */
class CsvMergeException(
    val name: String,
    val reason: Reason,
    cause: Throwable? = null,
) : IOException("cannot merge $name: $reason", cause) {
    enum class Reason {
        /** It could not be read: the cause says why (too large, the system's reason). */
        UNREADABLE,

        /** Its header names other columns than the first file's, or names one twice. */
        OTHER_COLUMNS,

        /** A row of more than [FileCaps.CSV_COLUMNS] cells. */
        TOO_MANY_COLUMNS,

        /** A cell of more than [FileCaps.CSV_CELL_CHARS] characters, more than a spreadsheet's cell holds. */
        CELL_TOO_LONG,
    }
}

/** What a merge wrote: [rows] under the header, from [files] files that held one. */
data class CsvMerged(val rows: Long, val files: Int)

/**
 * "Merge several CSV files" of the CSV tools (`_mergeCsv`, `csv_tools_screen.dart:146-210`): the first file's
 * header, then the rows of every file, in order. The files are read ONE AT A TIME, each within the CSV
 * viewer's cap and as the viewer reads it — its encoding, its separator ([CsvRows.dialectOf]), its rows of
 * empty fields skipped — and its rows written out as they are found.
 *
 * The files' dialects are reconciled into the first file's separator, in UTF-8 — with a byte order mark,
 * unless the first file was UTF-8 without one: an Excel CSV in Windows-1252 merged into BOM-less UTF-8 would
 * open with its accents broken. Each following file's header is compared with the first one's, trailing empty
 * cells apart: the same columns are dropped as the 2.15.3 dropped them; the same names in another order are
 * put back in the first file's order; other columns refuse the merge, in words — the 2.15.3 dropped any
 * second header and appended its rows under columns that were not theirs.
 *
 * Two departures from the 2.15.3, both its defects: every cell is written as the files hold it — the 2.15.3
 * passed them through `CsvSafe.encodeSafe` (`csv_tools_screen.dart:184`), which put a `'` before every `-5`
 * and `+33…` of the files, as its CSV editor did (D5): the formula guard is for what the user types —; and a
 * file over the cap, or not readable, refuses the merge where the 2.15.3 left it out with a passing message.
 */
object CsvMerge {
    private const val CRLF = "\r\n"
    private const val BOM = '\uFEFF'
    private const val BUFFER = 64 * 1024
    private const val CHECK_EVERY_ROWS = 1024

    /**
     * Merges [files] into [out] (not closed). [progress] gets the part read, from 0 to 1, between pieces of work;
     * what it throws (a cancellation) stops the merge.
     *
     * @throws SelectionTooLargeException the files are more than [total] bytes together — listed, or read
     * @throws CsvMergeException one of them is refused
     * @throws NothingToConvertException not one of them holds a row
     */
    fun write(
        files: List<File>,
        out: OutputStream,
        total: Long = FileCaps.CSV_MERGE_TOTAL,
        progress: (Float) -> Unit = {},
    ): CsvMerged {
        val sizes = files.map(File::length)
        requireWithin(sizes.sum(), total)
        val listed = maxOf(1L, sizes.sum()).toFloat()
        var done = 0L
        var output: MergeOutput? = null
        var rows = 0L
        var used = 0
        var bytesRead = 0L
        files.forEachIndexed { index, file ->
            val read = FileRows(file) { progress(done / listed) }
            // The bytes read, not only the sizes listed before: files grown since passed the total (GPT review R26).
            bytesRead += read.size
            requireWithin(bytesRead, total)
            val header = read.next { progress((done + read.position) / listed) }
            if (header != null) {
                used++
                val arrangement = output?.let { arrangementOf(it.header, header, file.name) }
                val bom = read.encoding != TextEncoding.UTF_8
                val target = output ?: MergeOutput(out, read.separator, bom, header).also { output = it }
                while (true) {
                    val row = read.next { progress((done + read.position) / listed) } ?: break
                    target.row(arrangement?.let { arrange(row, it) } ?: row)
                    rows++
                }
            }
            done += sizes[index]
        }
        (output ?: throw NothingToConvertException("no row in any file")).finish()
        progress(1f)
        return CsvMerged(rows, used)
    }

    /**
     * How the cells of a file whose header is [header] go under [first]: null when as they are (the same columns,
     * trailing empty cells apart); else, for each column of [first], where it is in this file.
     *
     * @throws CsvMergeException other columns, or a name twice
     */
    private fun arrangementOf(first: List<String>, header: List<String>, name: String): IntArray? {
        val expected = first.dropLastWhile(String::isEmpty)
        val found = header.dropLastWhile(String::isEmpty)
        if (found == expected) return null
        val unique = expected.toSet().size == expected.size && found.toSet().size == found.size
        if (!unique || found.toSet() != expected.toSet()) throw CsvMergeException(name, CsvMergeException.Reason.OTHER_COLUMNS)
        // By a map: an index searched per column made 16 384 columns cost 134 million comparisons, no stop between
        // (GPT review R26).
        val positions = HashMap<String, Int>(found.size * 2)
        found.forEachIndexed { index, column -> positions[column] = index }
        return IntArray(expected.size) { positions.getValue(expected[it]) }
    }

    /** @throws SelectionTooLargeException [bytes] over [total] */
    private fun requireWithin(bytes: Long, total: Long) {
        if (bytes > total) throw SelectionTooLargeException(total)
    }

    /** [row] in the first file's order; its cells beyond its own header after them, in their order. */
    private fun arrange(row: List<String>, arrangement: IntArray): List<String> =
        arrangement.map { row.getOrElse(it) { "" } } + row.drop(arrangement.size)

    /** The rows of one file, read whole within [FileCaps.CSV_FILE], given one at a time. */
    private class FileRows(private val file: File, checkpoint: () -> Unit) {
        private val bytes: Pair<ByteArray, Int> = try {
            val source = StorageFileSource(file)
            val cap = FileCaps.CSV_FILE
            source.open(cap).use { TextDocument.readAll(it, source.length(), cap, checkpoint) }
        } catch (e: IOException) {
            throw CsvMergeException(file.name, CsvMergeException.Reason.UNREADABLE, e)
        }

        /** The file's bytes, as read. */
        val size: Int = bytes.second
        private val text = EncodedText(bytes.first, size, TextEncoding.detect(bytes.first, size))
        private val dialect = CsvRows.dialectOf(text, 0)
        private val scanner = CsvScanner(text, dialect.separator)
        private var units = dialect.start
        private var records = 0

        val encoding: TextEncoding get() = text.encoding
        val separator: Char get() = dialect.separator

        /** Where the reading is, in bytes. */
        val position: Long get() = units.toLong() * text.encoding.unitSize

        /** The next row with content, its cells in full; null at the end. [checkpoint] is called every so many rows. */
        fun next(checkpoint: () -> Unit): List<String>? {
            while (units < text.length) {
                if (records++ % CHECK_EVERY_ROWS == 0) checkpoint()
                val cells = ArrayList<String>()
                var tooLong = false
                val whole = scanner.record(units) { index, start, end ->
                    val cell = text.field(start, end, FileCaps.CSV_CELL_CHARS + 1)
                    tooLong = cell.length > FileCaps.CSV_CELL_CHARS
                    cells += cell
                    !tooLong && index + 1 < FileCaps.CSV_COLUMNS
                }
                if (tooLong) throw CsvMergeException(file.name, CsvMergeException.Reason.CELL_TOO_LONG)
                if (!whole) throw CsvMergeException(file.name, CsvMergeException.Reason.TOO_MANY_COLUMNS)
                units = scanner.end
                if (scanner.hasContent) return cells
            }
            return null
        }
    }

    /** The merged file: UTF-8, [separator], CRLF after each row, a field quoted when it needs to be. */
    private class MergeOutput(out: OutputStream, private val separator: Char, bom: Boolean, val header: List<String>) {
        private val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), BUFFER)

        init {
            if (bom) writer.write(BOM.code)
            row(header)
        }

        fun row(cells: List<String>) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) writer.write(separator.code)
                writer.write(quotedField(cell, separator))
            }
            writer.write(CRLF)
        }

        fun finish() = writer.flush()
    }
}
