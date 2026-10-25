package com.readfilestech.read_files_tech.data.convert

import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import java.io.IOException
import java.io.OutputStream

/** A limit of an Excel sheet that a CSV goes past. */
enum class ExcelLimit { ROWS, COLUMNS, CELL_CHARS }

/** The CSV would not fit in an Excel sheet: refused whole, rather than a workbook that silently lacks a part. */
class ExcelLimitException(val limit: ExcelLimit) : IOException("past Excel's limit of $limit")

/** The file has nothing to convert: a CSV without a row, a document without text, a PDF without selectable text. */
class NothingToConvertException(reason: String) : IOException(reason)

/**
 * CSV → XLSX (`_csvToXlsx` of the 2.15.3, docs D-030): the CSV as the CSV viewer reads it ([CsvTable]:
 * its encoding, its separator, its header, its empty lines skipped as the 2.15.3's `csv` package skipped
 * them), every row into one sheet of [XlsxWriter], numbers as numbers when they come back unchanged. A
 * CSV past Excel's rows, columns or characters in a cell is refused: the 2.15.3 had no such check
 * (excel_community wrote what Excel then cut or refused).
 */
object CsvToXlsx {
    /**
     * Writes [table] on [out] (not closed). [onRow] gets the rows written so far, the header included,
     * every [PROGRESS_EVERY] rows and at the end; what it throws (a cancellation) stops the writing.
     *
     * @throws NothingToConvertException the CSV holds no row.
     * @throws ExcelLimitException past [XlsxCells.MAX_ROWS], [XlsxCells.MAX_COLUMNS] or [XlsxCells.MAX_CELL_CHARS].
     */
    fun write(table: CsvTable, out: OutputStream, onRow: (Int) -> Unit = {}) {
        val refusal = when {
            table.isEmpty -> NothingToConvertException("a CSV without a row")
            table.rowsCut -> ExcelLimitException(ExcelLimit.ROWS)
            table.columnsCut -> ExcelLimitException(ExcelLimit.COLUMNS)
            else -> null
        }
        if (refusal != null) throw refusal
        val records = table.rows + 1
        val sheet = XlsxWriter(out)
        for (record in 0 until records) {
            // One character more than a cell holds: a longer cell is seen, never cut.
            val cells = if (record == 0) {
                table.header(0, table.columns, XlsxCells.MAX_CELL_CHARS + 1)
            } else {
                table.cells(record - 1, 0, table.columns, XlsxCells.MAX_CELL_CHARS + 1)
            }
            if (cells.any { it.length > XlsxCells.MAX_CELL_CHARS }) throw ExcelLimitException(ExcelLimit.CELL_CHARS)
            sheet.row(cells)
            if ((record + 1) % PROGRESS_EVERY == 0) onRow(record + 1)
        }
        sheet.finish()
        onRow(records)
    }

    /** Rows between two reports: often enough for a bar, rarely enough to cost nothing. */
    const val PROGRESS_EVERY = 1024
}
