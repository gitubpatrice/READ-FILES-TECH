package com.readfilestech.read_files_tech.ui.tools.convert

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.convert.Conversion
import com.readfilestech.read_files_tech.data.convert.ExcelLimit
import com.readfilestech.read_files_tech.data.convert.ExcelLimitException
import com.readfilestech.read_files_tech.data.convert.ImageFailedException
import com.readfilestech.read_files_tech.data.convert.NothingToConvertException
import com.readfilestech.read_files_tech.data.convert.SelectionTooLargeException
import com.readfilestech.read_files_tech.data.convert.XlsxCells
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.images.ImageBounds
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.data.outputs.NoOutputLocationException
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusalException
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.readFailure
import com.readfilestech.read_files_tech.ui.viewer.pdf.PdfViewer

private const val MIB = 1024L * 1024
private const val MEGA = 1_000_000L

/**
 * Why [conversion] of the file [name] made nothing, in words, with the file's name — the 2.15.3 showed
 * "Erreur : " and the exception (`convert_screen.dart:75`). Of several images, the one that failed is named.
 */
fun convertFailure(conversion: Conversion, name: String, error: Throwable): UiText = when (error) {
    is ImageFailedException -> convertFailure(conversion, error.name, error.cause ?: error)
    is NothingToConvertException -> uiText(
        when (conversion) {
            Conversion.CSV_TO_XLSX -> R.string.convert_csv_empty
            Conversion.PDF_TO_TEXT -> R.string.convert_pdf_no_text
            else -> R.string.convert_document_empty
        },
    )
    is ExcelLimitException -> excelLimit(error.limit)
    is SelectionTooLargeException -> uiText(R.string.convert_selection_too_large, error.capBytes / MIB, uiText(R.string.unit_megabytes))
    is TooManyItemsException -> UiText.Plural(R.plurals.convert_too_many_pages, error.limit)
    is PdfRefusalException -> PdfViewer.textOf(error.refusal)
    else -> readingFailure(name, error)
}

private fun excelLimit(limit: ExcelLimit): UiText = when (limit) {
    ExcelLimit.ROWS -> uiText(R.string.convert_excel_rows, XlsxCells.MAX_ROWS)
    ExcelLimit.COLUMNS -> uiText(R.string.convert_excel_columns, XlsxCells.MAX_COLUMNS)
    ExcelLimit.CELL_CHARS -> uiText(R.string.convert_excel_cell, XlsxCells.MAX_CELL_CHARS)
}

/** What reading the file [name], or writing the output, came to. */
private fun readingFailure(name: String, error: Throwable): UiText = when (error) {
    is ImageTooLargeException ->
        uiText(R.string.convert_image_too_large, name, error.width, error.height, ImageBounds.MAX_SIDE_PX, FileCaps.IMAGE_PIXELS / MEGA)
    is UnsupportedFormatException -> uiText(R.string.convert_unsupported, name)
    is UnreadableFileException -> uiText(R.string.convert_unreadable, name)
    is NoOutputLocationException -> uiText(R.string.convert_no_output)
    is FileTooLargeException -> readFailure(name, error)
    is OutOfMemoryError -> uiText(R.string.convert_out_of_memory, name)
    // An I/O error by its reason (a full disk, a file gone); anything else is unexpected, and said so.
    else -> uiText(R.string.convert_failed, name, IoErrors.reasonOf(error))
}
