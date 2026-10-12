package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hssf.OldExcelFormatException
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import java.io.IOException
import java.util.Locale
import org.apache.poi.ss.usermodel.Sheet as PoiSheet

/**
 * An .xls (BIFF8, Excel 97-2003), read by Apache POI's HSSF (docs D-006) — ALWAYS from a stream: the `File`
 * constructors map the file and free the mappings through `CleanerUtil`, two hidden APIs of Android
 * (prototype, logcat). The workbook stays in memory until [close]; a cell is formatted when shown.
 *
 * What the 2.15.3 got wrong (excel_community 2.2.1, which read only four record types): every formula, every
 * string formula and every boolean was EMPTY, numbers had no format, dates showed their serial number
 * (inventory K3: 234 cells of 310 as Excel shows them); an Excel 95 file opened as a sheet named "潲" with
 * no text in it; an encrypted one was "unreadable".
 */
internal class XlsWorkbook private constructor(private val workbook: HSSFWorkbook, private val limits: SheetLimits, locale: Locale) :
    Workbook() {
    private val cellText = CellText(locale)
    private val date1904 = workbook.internalWorkbook.isUsing1904DateWindowing

    override val sheetNames: List<String> = (0 until workbook.numberOfSheets).map {
        cut(workbook.getSheetName(it), limits.cellChars)
    }

    /** The sheet's extent: its rows and columns up to the last cell that shows something, within the caps. */
    override suspend fun read(index: Int): Sheet = synchronized(cellText) {
        val sheet = workbook.getSheetAt(index)
        var lines = 0
        var columns = 0
        var rowsCut = false
        var columnsCut = false
        for (row in sheet.rowIterator()) {
            val width = row.lastContent()
            when {
                width == 0 -> Unit
                row.rowNum >= limits.rows -> rowsCut = true
                else -> {
                    lines = maxOf(lines, row.rowNum + 1)
                    columns = maxOf(columns, minOf(width, limits.columns))
                    if (width > limits.columns) columnsCut = true
                }
            }
        }
        XlsSheet(sheet, lines, columns, rowsCut, columnsCut)
    }

    override fun release() = workbook.close()

    /** A sheet whose cells are formatted when asked for, under the formatter's lock. */
    private inner class XlsSheet(private val sheet: PoiSheet, lines: Int, columns: Int, rowsCut: Boolean, columnsCut: Boolean) :
        Sheet(lines, columns, rowsCut, columnsCut) {
        override fun line(index: Int, first: Int, until: Int): List<String> = synchronized(cellText) {
            val row = sheet.getRow(index)
            (first until until).map { column -> row?.getCell(column)?.let(::text).orEmpty() }
        }

        private fun text(cell: Cell): String = cut(cellText.format(cell, date1904), limits.cellChars)
    }

    companion object {
        /** The stream of an Office package encrypted with a password, in its OLE file ([MS-OFFCRYPTO]). */
        private const val ENCRYPTED_PACKAGE = "EncryptedPackage"

        /**
         * The workbook of [source], an OLE file: an .xlsx encrypted with a password (its package inside an
         * OLE file), an Excel 95 workbook and an encrypted .xls are formats not opened; an OLE file holding
         * no workbook (a Word 97 document under an .xls name) is not one.
         */
        fun open(source: ViewerSource, limits: SheetLimits, locale: Locale): XlsWorkbook =
            XlsWorkbook(poiErrors { source.open(limits.fileBytes).use { POIFSFileSystem(it) }.use(::workbookOf) }, limits, locale)

        private fun workbookOf(ole: POIFSFileSystem): HSSFWorkbook {
            if (ole.root.hasEntry(ENCRYPTED_PACKAGE)) throw UnsupportedFormatException("an encrypted Office package")
            // Without keeping the OLE file's other streams (macros, pictures): its bytes go once read.
            return HSSFWorkbook(ole.root, false)
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
            // Refusals of their own pass as they are: too large for the source's cap, a format not opened.
            throw if (e is FileTooLargeException || e is UnsupportedFormatException) {
                e
            } else {
                UnreadableFileException(
                    "an .xls cut short or damaged",
                    e
                )
            }
        } catch (e: RuntimeException) {
            // RecordFormatException, IllegalArgumentException (no "Workbook" stream), an index out of bounds…
            throw UnreadableFileException("an .xls cut short or damaged", e)
        }

        /** The columns of [this] row up to its last cell that shows something; 0 when none does. */
        private fun Row.lastContent(): Int {
            var last = lastCellNum.toInt()
            while (last > 0 && getCell(last - 1)?.shows() != true) last--
            return maxOf(last, 0)
        }

        private fun Cell.shows(): Boolean = when (cellType) {
            CellType.BLANK, CellType._NONE -> false
            CellType.STRING -> stringCellValue.isNotEmpty()
            else -> true
        }
    }
}
