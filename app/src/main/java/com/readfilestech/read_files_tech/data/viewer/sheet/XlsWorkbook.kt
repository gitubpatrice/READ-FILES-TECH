package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hssf.OldExcelFormatException
import org.apache.poi.hssf.record.ArrayRecord
import org.apache.poi.hssf.record.BOFRecord
import org.apache.poi.hssf.record.BoolErrRecord
import org.apache.poi.hssf.record.BoundSheetRecord
import org.apache.poi.hssf.record.ContinueRecord
import org.apache.poi.hssf.record.DateWindow1904Record
import org.apache.poi.hssf.record.EOFRecord
import org.apache.poi.hssf.record.ExtendedFormatRecord
import org.apache.poi.hssf.record.FormatRecord
import org.apache.poi.hssf.record.FormulaRecord
import org.apache.poi.hssf.record.LabelRecord
import org.apache.poi.hssf.record.LabelSSTRecord
import org.apache.poi.hssf.record.MulRKRecord
import org.apache.poi.hssf.record.NumberRecord
import org.apache.poi.hssf.record.RKRecord
import org.apache.poi.hssf.record.RecordInputStream
import org.apache.poi.hssf.record.SSTRecord
import org.apache.poi.hssf.record.SharedFormulaRecord
import org.apache.poi.hssf.record.StringRecord
import org.apache.poi.hssf.record.TableRecord
import org.apache.poi.hssf.record.common.UnicodeString
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.poifs.storage.BATBlock
import org.apache.poi.poifs.storage.HeaderBlock
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FormulaError
import java.io.IOException
import java.util.Locale

/**
 * An .xls (BIFF8, Excel 97-2003), read as a STREAM of records by Apache POI's record classes (docs D-006) —
 * never by its `HSSFWorkbook`, which held the whole workbook: a real .xls of 46 MiB made by Excel ran out of
 * memory on a phone (S9, 256 MB of heap) after 16.5 s, for a viewer that shows 5 000 x 200 cells of one
 * sheet (on a JVM: 227 MiB held, 445 MiB for one of unique strings; read here, 54 MiB, the file's 46 with).
 * Open, the workbook keeps the names of its sheets and its number formats; a sheet is read when it is shown,
 * from the stream's start: its cells within the caps, then the shared strings (SST) those cells show, not
 * one more — POI's `SSTRecord` holds the whole table (293 MiB for 1.77 million strings, 173 bytes each,
 * whatever the text budget counts) and loops on the count it declares. Shown as the .xlsx: the values cached
 * in the file, formatted by [CellText].
 *
 * The OLE file is read ALWAYS from a stream: the `File` constructors map the file and free the mappings
 * through `CleanerUtil`, two hidden APIs of Android (prototype, logcat). `POIFSFileSystem(InputStream)` holds
 * the file whole until [close].
 *
 * What the 2.15.3 got wrong (excel_community 2.2.1, which read only four record types): every formula, every
 * string formula and every boolean was EMPTY, numbers had no format, dates showed their serial number
 * (inventory K3: 234 cells of 310 as Excel shows them); an Excel 95 file opened as a sheet named "潲" with
 * no text in it; an encrypted one was "unreadable".
 */
internal class XlsWorkbook private constructor(
    private val ole: POIFSFileSystem,
    private val stream: XlsStream,
    override val sheetNames: List<String>,
    private val numbers: XlsxWorkbook.Numbers,
    private val limits: SheetLimits,
    private val budget: TextBudget,
) : Workbook() {
    override suspend fun read(index: Int): Sheet = poiErrors {
        val budget = budget.forSheet()
        val grid = GridBuilder(limits, budget)
        if (!stream.pass { XlsSheetCells(it, grid, numbers.cellText).read(index) }) {
            throw UnreadableFileException("a sheet without its records")
        }
        val indices = grid.sharedIndices()
        if (indices.isNotEmpty()) {
            val strings = stream.pass { XlsSharedStrings(it, indices, limits.cellChars, budget).read() }
            grid.resolveShared { strings[indices.binarySearch(it)] }
        }
        grid.build(numbers::format)
    }

    override fun release() = ole.close()

    companion object {
        /** The stream of an Office package encrypted with a password, in its OLE file ([MS-OFFCRYPTO]). */
        private const val ENCRYPTED_PACKAGE = "EncryptedPackage"

        /**
         * The workbook of [source], an OLE file: an .xlsx encrypted with a password (its package inside an
         * OLE file), an Excel 95 workbook and an encrypted .xls are formats not opened; an OLE file holding
         * no workbook (a Word 97 document under an .xls name) is not one.
         */
        suspend fun open(source: ViewerSource, limits: SheetLimits, locale: Locale): XlsWorkbook = poiErrors {
            checkDeclaredSize(source, limits)
            val ole = source.open(limits.fileBytes).use { POIFSFileSystem(it) }
            var workbook: XlsWorkbook? = null
            try {
                if (ole.root.hasEntry(ENCRYPTED_PACKAGE)) throw UnsupportedFormatException("an encrypted Office package")
                val stream = XlsStream.open(ole, HSSFWorkbook.getWorkbookDirEntryName(ole.root))
                val budget = TextBudget(limits.textChars)
                val globals = stream.pass { XlsGlobals(it, limits.cellChars, budget).read() }
                val numbers = XlsxWorkbook.Numbers(globals.styles(), globals.formats, globals.date1904, CellText(locale))
                XlsWorkbook(ole, stream, globals.names, numbers, limits, budget).also { workbook = it }
            } finally {
                if (workbook == null) ole.close()
            }
        }

        /**
         * Refuses an OLE file whose header addresses more than the cap: `POIFSFileSystem(InputStream)` allocates
         * at once the largest file its allocation table can address, whatever the file holds — up to 250 MB
         * for a file of a few KB. A real file's last allocation sector is partly used: one sector's reach more.
         */
        private fun checkDeclaredSize(source: ViewerSource, limits: SheetLimits) {
            val header = source.open(limits.fileBytes).use(::HeaderBlock)
            val block = header.bigBlockSize
            val slack = (block.batEntriesPerBlock + 1L) * block.bigBlockSize
            if (BATBlock.calculateMaximumSize(header) > limits.fileBytes + slack) {
                throw UnreadableFileException("an OLE header addressing more than the cap")
            }
        }

        /** What POI throws, in words: an encrypted or Excel 95 workbook is a format not opened; the rest, damage. */
        // POI throws its structure errors as RuntimeExceptions of many classes, and plain IOExceptions as the app's own refusals.
        @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
        private inline fun <T> poiErrors(read: () -> T): T = try {
            read()
        } catch (e: EncryptedDocumentException) {
            throw UnsupportedFormatException("an .xls encrypted with a password").apply { initCause(e) }
        } catch (e: OldExcelFormatException) {
            throw UnsupportedFormatException("Excel 5.0/95").apply { initCause(e) }
        } catch (e: IOException) {
            // Refusals of their own pass as they are: too large for the source's cap, a format not opened, damage said.
            throw if (e is FileTooLargeException || e is UnsupportedFormatException || e is UnreadableFileException) {
                e
            } else {
                UnreadableFileException(
                    "an .xls cut short or damaged",
                    e
                )
            }
        } catch (e: RuntimeException) {
            // RecordFormatException, IllegalArgumentException (no "Workbook" stream), an index out of bounds…
            // A cancellation (the sheet left while read) is no damage.
            throw if (e is CancellationException) e else UnreadableFileException("an .xls cut short or damaged", e)
        }
    }
}

/**
 * The workbook's globals, up to their EOF: the names of its sheets, the number format of each cell style
 * (XF), the custom formats, the calendar of its dates. Names and formats are spent from [budget].
 */
private class XlsGlobals(private val records: XlsRecords, private val nameChars: Int, private val budget: TextBudget) {
    val names = ArrayList<String>()
    val formats = HashMap<Int, String>()
    var date1904 = false
        private set

    private var styles = IntArray(INITIAL_STYLES)
    private var count = 0

    suspend fun read(): XlsGlobals {
        while (records.next() && records.sid != EOFRecord.sid) {
            val input = records.input
            when (records.sid) {
                BoundSheetRecord.sid -> names += cut(BoundSheetRecord(input).sheetname, nameChars).also { budget.spend(it.length) }
                FormatRecord.sid -> FormatRecord(input).let { format ->
                    budget.spend(format.formatString.length)
                    formats[format.indexCode and USHORT] = format.formatString
                }
                ExtendedFormatRecord.sid -> style(ExtendedFormatRecord(input).formatIndex.toInt() and USHORT)
                DateWindow1904Record.sid -> date1904 = DateWindow1904Record(input).windowing.toInt() == 1
            }
        }
        return this
    }

    /** The number format of each style, by its index. */
    fun styles(): IntArray = styles.copyOf(count)

    /** A cell names its style in 16 bits: the styles past them are never used. */
    private fun style(format: Int) {
        if (count == MAX_STYLES) return
        if (count == styles.size) styles = styles.copyOf(count * 2)
        styles[count++] = format
    }

    private companion object {
        const val INITIAL_STYLES = 64
        const val MAX_STYLES = 0x10000
    }
}

/**
 * The cells of one sheet into [grid], which keeps those within the caps: numbers with their style, texts, and
 * the indices of the shared strings, resolved once the sheet is read.
 */
private class XlsSheetCells(private val records: XlsRecords, private val grid: GridBuilder, private val cellText: CellText) {
    /** The cell of a formula whose cached result is a text, held by the STRING record that follows it. */
    private var textRow = NONE
    private var textColumn = NONE

    /**
     * Sheet [index], counted as POI's `HSSFWorkbook` counted them (worksheets, chart sheets, macro sheets),
     * one substream each after the globals; false when the stream ends before it.
     */
    suspend fun read(index: Int): Boolean {
        records.skipGlobals()
        var sheet = -1
        var found = false
        // Past the last substream, what follows its EOF is no BOF (padding), as POI reads it.
        while (!found && records.next() && records.sid == BOFRecord.sid) {
            val counted = BOFRecord(records.input).type in SHEET_TYPES
            if (counted) sheet++
            found = counted && sheet == index
            if (!found) records.skipSubstream()
        }
        if (found) cells()
        return found
    }

    private suspend fun cells() {
        var depth = 1
        while (depth > 0 && records.next()) {
            when (records.sid) {
                BOFRecord.sid -> depth++
                EOFRecord.sid -> depth--
                // The substream of a chart drawn on the sheet holds none of its cells.
                else -> if (depth == 1) cell()
            }
        }
    }

    private fun cell() {
        val input = records.input
        when (records.sid) {
            FormulaRecord.sid -> formula(FormulaRecord(input))
            StringRecord.sid -> {
                if (textRow != NONE) grid.text(textRow, textColumn, StringRecord(input).string)
                textRow = NONE
            }
            // Between a formula and its STRING: the shared formula, the array or the table it belongs to.
            SharedFormulaRecord.sid, ArrayRecord.sid, TableRecord.sid -> Unit
            else -> {
                textRow = NONE
                value(input)
            }
        }
    }

    private fun value(input: RecordInputStream) {
        when (records.sid) {
            NumberRecord.sid -> NumberRecord(input).let { number(it.row, it.column, it.value, it.xfIndex) }
            RKRecord.sid -> RKRecord(input).let { number(it.row, it.column, it.rkNumber, it.xfIndex) }
            MulRKRecord.sid -> numbers(MulRKRecord(input))
            LabelSSTRecord.sid -> LabelSSTRecord(input).let { grid.shared(it.row, it.column.unsigned(), it.sstIndex) }
            LabelRecord.sid -> LabelRecord(input).let { grid.text(it.row, it.column.unsigned(), it.value.orEmpty()) }
            BoolErrRecord.sid -> BoolErrRecord(input).let { grid.text(it.row, it.column.unsigned(), booleanOrError(it)) }
        }
    }

    private fun number(row: Int, column: Short, value: Double, style: Short) = grid.number(row, column.unsigned(), value, style.unsigned())

    /** The numbers of a MULRK record: cells side by side in one row. */
    private fun numbers(record: MulRKRecord) {
        for (i in 0 until record.numColumns) {
            grid.number(record.row, record.firstColumn.unsigned() + i, record.getRKNumberAt(i), record.getXFAt(i).unsigned())
        }
    }

    private fun booleanOrError(record: BoolErrRecord): String =
        if (record.isBoolean) cellText.booleanText(record.booleanValue) else error(record.errorValue.toInt())

    /** The value a formula's cell shows: the result cached in the file; a text one is in the STRING that follows. */
    private fun formula(record: FormulaRecord) {
        val row = record.row
        val column = record.column.unsigned()
        textRow = NONE
        when (record.cachedResultTypeEnum) {
            CellType.NUMERIC -> grid.number(row, column, record.value, record.xfIndex.unsigned())
            CellType.BOOLEAN -> grid.text(row, column, cellText.booleanText(record.cachedBooleanValue))
            CellType.ERROR -> grid.text(row, column, error(record.cachedErrorValue))
            // A text, or an empty one (nothing follows it, nothing shown).
            else -> if (record.hasCachedResultString()) {
                textRow = row
                textColumn = column
            }
        }
    }

    private companion object {
        const val NONE = -1

        /** The substreams POI's `HSSFWorkbook` made sheets of: worksheet (dialog sheets too), chart, Excel 4.0 macros. */
        val SHEET_TYPES = setOf(BOFRecord.TYPE_WORKSHEET, BOFRecord.TYPE_CHART, BOFRecord.TYPE_EXCEL_4_MACRO)

        /** An error as Excel shows it ("#DIV/0!"); nothing for a code that names no error, in a damaged file. */
        fun error(code: Int): String = if (FormulaError.isValidCode(code)) FormulaError.forInt(code).string else ""
    }
}

/**
 * The shared strings [wanted] (sorted, once each) of the workbook's table (SST and its CONTINUEs), read one by
 * one up to the last one wanted — the others are read and dropped. Each is cut to [cellChars] and spent from
 * [budget]. An index past the table's count makes the sheet unreadable, as POI did.
 */
private class XlsSharedStrings(
    private val records: XlsRecords,
    private val wanted: IntArray,
    private val cellChars: Int,
    private val budget: TextBudget,
) {
    suspend fun read(): Array<String> {
        while (records.next() && records.sid != EOFRecord.sid) {
            if (records.sid == SSTRecord.sid) return table(records.input)
        }
        throw UnreadableFileException("shared strings used, and no table in the workbook")
    }

    private suspend fun table(input: RecordInputStream): Array<String> {
        val total = input.readInt()
        // POI's rule (its bug 57456): a table that holds no string at all has no unique one either.
        val unique = input.readInt().takeIf { total != 0 } ?: 0
        if (wanted.first() < 0 || wanted.last() >= unique) throw UnreadableFileException("a shared string past the table: ${wanted.last()}")
        val found = Array(wanted.size) { "" }
        var next = 0
        var index = 0
        // A table that ends before its count, its record over and no CONTINUE after it: its last strings are
        // empty, as POI made them — never a loop on the count, which a file can set to two billion.
        while (next < wanted.size && (input.remaining() > 0 || continued(input))) {
            if (index % CANCEL_CHECK_EVERY == 0) currentCoroutineContext().ensureActive()
            val text = UnicodeString(input).string
            if (index++ == wanted[next]) found[next++] = cut(text, cellChars).also { budget.spend(it.length) }
        }
        return found
    }

    private fun continued(input: RecordInputStream) = input.hasNextRecord() && input.nextSid == ContinueRecord.sid.toInt()

    private companion object {
        const val CANCEL_CHECK_EVERY = 4096
    }
}

private const val USHORT = 0xFFFF

/** A row's column or a cell's style, written in 16 bits unsigned. */
private fun Short.unsigned(): Int = toInt() and USHORT
