package com.readfilestech.read_files_tech.ui.viewer.csv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.data.viewer.csv.CsvView
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.FolderSearchField
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import com.readfilestech.read_files_tech.ui.viewer.table.TableCells
import com.readfilestech.read_files_tech.ui.viewer.table.TableGrid
import com.readfilestech.read_files_tech.ui.viewer.table.TableHeaderText
import com.readfilestech.read_files_tech.ui.viewer.table.TableMessage

/**
 * `CsvViewerScreen` of the 2.15.3: the table under its header, a filter on every cell, a sort by a tap on
 * a column's name (again to reverse it), the CSV editor (phase 5 of the port), share. The 2.15.3 laid
 * every cell out at once in a `DataTable`; here only the rows AND the columns on screen exist ([TableGrid]).
 */
@Composable
fun CsvViewerScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, viewModel: CsvViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    // Back from its "Edit": the table the editor saved, not the one of before.
    LifecycleResumeEffect(Unit) {
        viewModel.content.reloadIfChanged()
        onPauseOrDispose { }
    }
    val source = viewModel.source
    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = {
            // Edited in place, a file of the storage only: one of the vault (phase 6) has no place to be.
            source?.shareable?.let { FileRouter.editorFor(it.path, source.extension) }?.let { editor ->
                IconButton(onClick = { onNavigate(editor) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
        },
    ) { table, padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val current = shown
            when {
                table.isEmpty -> TableMessage(stringResource(R.string.preview_empty))
                current != null -> {
                    FolderSearchField(folderKey = source?.name, onQuery = viewModel::search)
                    if (current.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    Summary(table, current)
                    CsvGrid(table, current, viewModel::sortBy)
                }
            }
        }
    }
}

/** "1 234 rows · 6 columns", or "12 rows of 1 234", and what is not shown of a file past Excel's limits. */
@Composable
private fun Summary(table: CsvTable, shown: CsvShown) {
    val rows = if (shown.query.isEmpty()) {
        pluralStringResource(R.plurals.csv_rows, table.rows, table.rows)
    } else {
        pluralStringResource(R.plurals.csv_rows_found, shown.view.size, shown.view.size, table.rows)
    }
    val columns = pluralStringResource(R.plurals.csv_columns, table.columns, table.columns)
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.joined_dot, rows, columns), style = style, color = color)
        val cuts = listOfNotNull(
            if (table.rowsCut) pluralStringResource(R.plurals.csv_rows_cut, FileCaps.CSV_ROWS, FileCaps.CSV_ROWS) else null,
            if (table.columnsCut) pluralStringResource(R.plurals.csv_columns_cut, FileCaps.CSV_COLUMNS, FileCaps.CSV_COLUMNS) else null,
        )
        cuts.forEach { Text(it, style = style, color = color) }
        shown.failure?.let { Text(it.resolve(), style = style, color = MaterialTheme.colorScheme.error) }
    }
}

/** The rows of [view] of the table, the cells decoded when they show. */
private class CsvCells(private val table: CsvTable, private val view: CsvView) : TableCells {
    override val columns: Int get() = table.columns
    override val rows: Int get() = view.size

    override fun header(first: Int, until: Int): List<String> = table.header(first, until, CsvTable.SHOWN_CHARS)

    override fun row(position: Int, first: Int, until: Int): List<String> = table.cells(
        view.row(position),
        first,
        until,
        CsvTable.SHOWN_CHARS
    )
}

/** The table, its header a row of buttons that sort. */
@Composable
private fun CsvGrid(table: CsvTable, shown: CsvShown, onSort: (Int) -> Unit) {
    val list = rememberLazyListState()
    ScrollToTopOnChange(shown) { list.scrollToItem(0) }
    val cells = remember(table, shown.view) { CsvCells(table, shown.view) }
    TableGrid(
        cells,
        list,
        headerCell = { column, text -> SortHeader(column, text, shown.sort, onSort) },
        aboveRows = {
            if (shown.view.size == 0 && shown.query.isNotEmpty() && !shown.busy) {
                TableMessage(stringResource(R.string.csv_no_match, shown.query))
            }
        },
    )
}

/** Back to the first row when the query or the sort changes — not when the screen is only rebuilt (a rotation). */
@Composable
private fun ScrollToTopOnChange(shown: CsvShown, scrollToTop: suspend () -> Unit) {
    val key = shown.query to (shown.sort?.saved ?: CsvSortChoice.NONE)
    var last by rememberSaveable { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (key != last) {
            last = key
            scrollToTop()
        }
    }
}

/** One column's name: a tap sorts by it, an arrow says the order. */
@Composable
private fun SortHeader(column: Int, text: String, sort: CsvSortChoice?, onSort: (Int) -> Unit) {
    val sortLabel = stringResource(R.string.csv_sort)
    val ascending = stringResource(R.string.csv_sorted_ascending)
    val descending = stringResource(R.string.csv_sorted_descending)
    val numbered = stringResource(R.string.csv_column_number)
    val sorted = sort?.takeIf { it.column == column }
    Row(
        Modifier.fillMaxSize()
            .clickable(role = Role.Button, onClickLabel = sortLabel) { onSort(column) }
            .semantics {
                if (text.isBlank()) contentDescription = numbered.format(column + 1)
                if (sorted != null) stateDescription = if (sorted.ascending) ascending else descending
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TableHeaderText(text, Modifier.weight(1f, fill = false))
        if (sorted != null) {
            Icon(
                if (sorted.ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
