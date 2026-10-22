package com.readfilestech.read_files_tech.ui.tools.csvtools

import androidx.annotation.PluralsRes
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.convert.NothingToConvertException
import com.readfilestech.read_files_tech.data.convert.SelectionTooLargeException
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.MIB
import com.readfilestech.read_files_tech.data.tools.CsvMergeException
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.outputFailure
import com.readfilestech.read_files_tech.ui.tools.readFailure

/**
 * Why [work] wrote nothing, in words — the 2.15.3 showed "Erreur : " and the exception. [name] is what was being
 * worked on, for a phone out of memory: the file, or the merge.
 */
fun csvToolsFailure(work: CsvWork, name: String, error: Throwable): UiText = outputFailure(name, error) ?: when (error) {
    is NothingToConvertException -> uiText(if (work == CsvWork.MERGE) R.string.csv_tools_merge_empty else R.string.convert_csv_empty)
    is TooManyItemsException -> UiText.Plural(R.plurals.csv_tools_too_many_pages, error.limit)
    is SelectionTooLargeException -> uiText(R.string.zip_too_large, error.capBytes / MIB, uiText(R.string.unit_megabytes))
    is CsvMergeException -> mergeRefusal(error)
    else -> uiText(if (work == CsvWork.MERGE) R.string.csv_tools_merge_failed else R.string.tools_pdf_failed, IoErrors.reasonOf(error))
}

private fun mergeRefusal(error: CsvMergeException): UiText = when (error.reason) {
    CsvMergeException.Reason.UNREADABLE -> readFailure(error.name, error.cause ?: error)
    CsvMergeException.Reason.OTHER_COLUMNS -> uiText(R.string.csv_tools_merge_other_columns, error.name)
    CsvMergeException.Reason.TOO_MANY_COLUMNS -> limit(R.plurals.csv_tools_merge_too_many_columns, error.name, FileCaps.CSV_COLUMNS)
    CsvMergeException.Reason.CELL_TOO_LONG -> limit(R.plurals.csv_tools_merge_cell_too_long, error.name, FileCaps.CSV_CELL_CHARS)
}

/** "[name] goes past [limit]": a plural of the limit, the name first. */
private fun limit(@PluralsRes id: Int, name: String, limit: Int): UiText = UiText.Plural(id, limit, listOf(name, limit))
