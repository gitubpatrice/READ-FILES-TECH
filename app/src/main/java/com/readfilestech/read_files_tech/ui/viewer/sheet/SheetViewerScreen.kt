package com.readfilestech.read_files_tech.ui.viewer.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.sheet.Sheet
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailureMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import com.readfilestech.read_files_tech.ui.viewer.table.TableCells
import com.readfilestech.read_files_tech.ui.viewer.table.TableGrid
import com.readfilestech.read_files_tech.ui.viewer.table.TableMessage

/**
 * `XlsxViewerScreen` of the 2.15.3 (.xlsx, .xls, .ods): the sheets as chips, the one chosen under its first
 * row as a header, share. The 2.15.3 paged its rows by 25 to 200 in a `PaginatedDataTable` and laid out every
 * column of a page; here the rows and the columns on screen only exist ([TableGrid]), and what is not shown
 * past the caps is said. Neither a sort nor a search, as in the 2.15.3.
 */
@Composable
fun SheetViewerScreen(onBack: () -> Unit, viewModel: SheetViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val source = viewModel.source
    ViewerScaffold(title = source?.name.orEmpty(), shareable = source?.shareable, state = state, onBack = onBack) { book, padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val names = book.workbook.sheetNames
            val current = shown
            when {
                names.isEmpty() -> TableMessage(stringResource(R.string.sheet_no_sheet))
                current != null -> {
                    if (names.size > 1) SheetChips(names, current.index, viewModel::select)
                    SheetContent(current)
                }
            }
        }
    }
}

/** The sheets' names, the one shown selected; a sheet without a name is called by its number. */
@Composable
private fun SheetChips(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    LazyRow(
        state = list,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(names) { index, name ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(name.ifBlank { stringResource(R.string.sheet_number, index + 1) }, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun SheetContent(shown: SheetShown) {
    val sheet = shown.sheet
    when {
        shown.failure != null -> ViewerFailureMessage(shown.failure, PaddingValues())
        sheet == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        sheet.isEmpty -> TableMessage(stringResource(R.string.sheet_empty))
        // Its own grid per sheet: another sheet starts at its top-left corner.
        else -> key(shown.index) {
            Summary(sheet)
            val list = rememberLazyListState()
            val cells = remember(sheet) { SheetCells(sheet) }
            TableGrid(cells, list)
        }
    }
}

/** "1 234 rows · 6 columns", and what is not shown of a sheet past the caps. */
@Composable
private fun Summary(sheet: Sheet) {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val rows = pluralStringResource(R.plurals.csv_rows, sheet.rows, sheet.rows)
        val columns = pluralStringResource(R.plurals.csv_columns, sheet.columns, sheet.columns)
        Text(stringResource(R.string.joined_dot, rows, columns), style = style, color = color)
        if (sheet.rowsCut) {
            Text(
                pluralStringResource(R.plurals.sheet_rows_cut, FileCaps.SHEET_ROWS, FileCaps.SHEET_ROWS),
                style = style,
                color = color
            )
        }
        if (sheet.columnsCut) {
            Text(
                pluralStringResource(R.plurals.sheet_columns_cut, FileCaps.SHEET_COLUMNS, FileCaps.SHEET_COLUMNS),
                style = style,
                color = color
            )
        }
    }
}

/** A sheet's cells for the grid: formatted when they show. */
private class SheetCells(private val sheet: Sheet) : TableCells {
    override val columns: Int get() = sheet.columns
    override val rows: Int get() = sheet.rows

    override fun header(first: Int, until: Int): List<String> = sheet.header(first, until)

    override fun row(position: Int, first: Int, until: Int): List<String> = sheet.cells(position, first, until)
}
