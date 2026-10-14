package com.readfilestech.read_files_tech.ui.tools.csveditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.editor.CellChange
import com.readfilestech.read_files_tech.data.editor.CsvEdits
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import com.readfilestech.read_files_tech.data.text.CsvSafe
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.viewer.table.TableCellText
import com.readfilestech.read_files_tech.ui.viewer.table.TableCells
import com.readfilestech.read_files_tech.ui.viewer.table.TableGrid
import com.readfilestech.read_files_tech.ui.viewer.table.TableHeaderText
import com.readfilestech.read_files_tech.ui.viewer.table.TableMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val MIB = 1024 * 1024
private const val NONE = -1

/**
 * `CsvEditorScreen` of the 2.15.3: the table, a tap on a cell edits it in a dialog, a long press offers
 * to insert or delete its row or its column — the header row is never deleted —, "Add a row" and "Add a
 * column" at the end, save, and the leave dialog "Discard / Cancel / Save". The grid is the viewers'
 * ([TableGrid]): only the cells on screen are read. From the home's grid, the file is chosen in the
 * app's picker; from the CSV viewer, it comes with the route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvEditorScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CsvEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chooseTitle = stringResource(R.string.csv_editor_choose)
    LaunchedEffect(picked.files) {
        picked.files?.firstOrNull()?.let {
            picked.consume()
            viewModel.open(it)
        }
    }
    LaunchedEffect(state.left) { if (state.left) onBack() }
    var leaving by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.modified && !leaving) { leaving = true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Title(state) },
                navigationIcon = {
                    IconButton(onClick = { if (state.modified) leaving = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { state.edits?.let { BarActions(state, it, viewModel) } },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val edits = state.edits
        val failure = state.failure
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                failure != null -> TableMessage(failure.resolve())
                edits == null -> Intro { onNavigate(Routes.picker(PickMode.FILE, extensions = setOf("csv"), title = chooseTitle)) }
                edits.isEmpty -> TableMessage(stringResource(R.string.preview_empty))
                else -> Editor(edits, state.version, viewModel)
            }
        }
    }
    if (leaving) {
        LeaveDialog(state, onSave = { viewModel.save(thenLeave = true) }, onDiscard = onBack, onDismiss = { leaving = false })
    }
    state.versions?.let { HistoryDialog(it, onRestore = viewModel::restore, onDismiss = viewModel::hideHistory) }
}

/** The file's name, and "modified" while something is not saved (the 2.15.3's badge). */
@Composable
private fun Title(state: CsvEditorState) {
    Column {
        Text(state.name.ifEmpty { stringResource(R.string.feature_csv_editor) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.modified) {
            Text(
                stringResource(R.string.csv_editor_modified),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun BarActions(state: CsvEditorState, edits: CsvEdits, viewModel: CsvEditorViewModel) {
    if (state.modified) {
        IconButton(onClick = { viewModel.save() }, enabled = !state.saving) {
            if (state.saving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.action_save))
            }
        }
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val editable = !state.saving
            DropdownMenuItem(
                text = { Text(stringResource(R.string.csv_editor_add_row)) },
                enabled = editable && edits.canAddRow,
                onClick = {
                    menu = false
                    viewModel.change { it.insertRow(edits.rowCount - 1) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.csv_editor_add_column)) },
                enabled = editable && edits.canAddColumn,
                onClick = {
                    menu = false
                    viewModel.change { it.insertColumn(edits.columnCount) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.csv_editor_history)) },
                enabled = editable,
                onClick = {
                    menu = false
                    viewModel.showHistory()
                },
            )
        }
    }
}

@Composable
private fun Intro(onChoose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.TableView, contentDescription = null, modifier = Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.feature_csv_editor), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.csv_editor_intro), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onChoose) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_choose_file))
        }
    }
}

/** The rows of the editor, the header apart, as the grid reads them; a new one at each [CsvEditorState.version]. */
private class EditorCells(private val edits: CsvEdits) : TableCells {
    override val columns: Int get() = edits.columnCount
    override val rows: Int get() = edits.rowCount - 1

    override fun header(first: Int, until: Int): List<String> = edits.cells(0, first, until, CsvTable.SHOWN_CHARS)

    override fun row(position: Int, first: Int, until: Int): List<String> = edits.cells(position + 1, first, until, CsvTable.SHOWN_CHARS)
}

@Composable
private fun Editor(edits: CsvEdits, version: Int, viewModel: CsvEditorViewModel) {
    var editRow by rememberSaveable { mutableIntStateOf(NONE) }
    var editColumn by rememberSaveable { mutableIntStateOf(NONE) }
    Summary(edits)
    val cells = remember(edits, version) { EditorCells(edits) }
    val actions = CellActions(edits) { position, column ->
        editRow = position
        editColumn = column
    }
    TableGrid(
        cells,
        rememberLazyListState(),
        headerCell = { column, text -> EditableCell(0, column, text, actions, viewModel) },
        rowCell = { position, column, text -> EditableCell(position + 1, column, text, actions, viewModel) },
    )
    if (editRow != NONE && editRow < edits.rowCount && editColumn < edits.columnCount) {
        val close = {
            editRow = NONE
            editColumn = NONE
        }
        CellDialog(
            position = editRow,
            column = editColumn,
            initial = remember(edits, editRow, editColumn) { edits.cellForEditing(editRow, editColumn) },
            encoding = edits.encoding.charset.displayName(),
            onConfirm = { typed -> viewModel.change { it.set(editRow, editColumn, typed) } ?: CellChange.UNCHANGED },
            onDismiss = close,
        )
    }
}

/** "1 234 rows · 6 columns", and what is not shown of a file past Excel's limits — but kept as it is. */
@Composable
private fun Summary(edits: CsvEdits) {
    val rows = pluralStringResource(R.plurals.csv_rows, edits.rowCount - 1, edits.rowCount - 1)
    val columns = pluralStringResource(R.plurals.csv_columns, edits.columnCount, edits.columnCount)
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.joined_dot, rows, columns), style = style, color = color)
        if (edits.rowsCut) {
            Text(pluralStringResource(R.plurals.csv_rows_cut, FileCaps.CSV_ROWS, FileCaps.CSV_ROWS), style = style, color = color)
        }
        if (edits.columnsCut) {
            Text(pluralStringResource(R.plurals.csv_columns_cut, FileCaps.CSV_COLUMNS, FileCaps.CSV_COLUMNS), style = style, color = color)
        }
        if (edits.rowsCut || edits.columnsCut) Text(stringResource(R.string.csv_editor_cut_kept), style = style, color = color)
        Text(stringResource(R.string.csv_editor_hint), style = style, color = color)
    }
    HorizontalDivider()
}

/** What a cell's tap and long press reach. */
private class CellActions(val edits: CsvEdits, val edit: (position: Int, column: Int) -> Unit)

/** A cell: a tap edits it; a long press inserts or deletes its row or its column. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableCell(position: Int, column: Int, text: String, actions: CellActions, viewModel: CsvEditorViewModel) {
    var menu by remember { mutableStateOf(false) }
    val edits = actions.edits
    Box(
        Modifier.fillMaxSize().combinedClickable(
            onClickLabel = stringResource(R.string.action_edit),
            onLongClickLabel = stringResource(R.string.action_more),
            onLongClick = { menu = true },
            onClick = { actions.edit(position, column) },
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (position == 0) TableHeaderText(text) else TableCellText(text)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val close = { menu = false }
            CellMenuItem(R.string.csv_editor_insert_row, edits.canAddRow, close) {
                viewModel.change { it.insertRow(position) }
            }
            CellMenuItem(R.string.csv_editor_delete_row, edits.canDeleteRow(position), close) {
                viewModel.change { it.deleteRow(position) }
            }
            CellMenuItem(R.string.csv_editor_insert_column, edits.canAddColumn, close) {
                viewModel.change { it.insertColumn(column + 1) }
            }
            CellMenuItem(R.string.csv_editor_delete_column, edits.canDeleteColumn, close) {
                viewModel.change { it.deleteColumn(column) }
            }
        }
    }
}

@Composable
private fun CellMenuItem(label: Int, enabled: Boolean, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        enabled = enabled,
        onClick = {
            close()
            action()
        },
    )
}

/**
 * Edits one cell (`_editCell` of the 2.15.3). A text that starts like a formula is said before it is
 * confirmed: it is saved with a `'` in front ([CsvSafe]). A text the file's encoding cannot hold, or too
 * long, keeps the dialog open with the reason. [initial] null: the cell is too long to be edited here.
 */
@Composable
private fun CellDialog(
    position: Int,
    column: Int,
    initial: String?,
    encoding: String,
    onConfirm: (String) -> CellChange,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    var refusal by remember { mutableStateOf<CellChange?>(null) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (position == 0) {
                    stringResource(R.string.csv_editor_cell_header, column + 1)
                } else {
                    stringResource(R.string.csv_editor_cell_title, position, column + 1)
                },
            )
        },
        text = {
            if (initial == null) {
                Text(pluralStringResource(R.plurals.csv_editor_cell_too_long, FileCaps.CSV_CELL_CHARS, FileCaps.CSV_CELL_CHARS))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            value = it.take(FileCaps.CSV_CELL_CHARS)
                            refusal = null
                        },
                        minLines = 1,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    if (value != initial && CsvSafe.sanitizeCell(value) != value) {
                        Text(stringResource(R.string.csv_editor_formula_guarded), style = MaterialTheme.typography.bodySmall)
                    }
                    refusal?.let {
                        Text(refusalText(it, encoding), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        },
        confirmButton = {
            Button(onClick = {
                val change = if (initial == null) CellChange.UNCHANGED else onConfirm(value)
                if (change == CellChange.CHANGED || change == CellChange.UNCHANGED) onDismiss() else refusal = change
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            if (initial != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun refusalText(refusal: CellChange, encoding: String): String = when (refusal) {
    CellChange.UNWRITABLE -> stringResource(R.string.csv_editor_unwritable, encoding)
    else -> pluralStringResource(R.plurals.csv_editor_too_long, FileCaps.CSV_CELL_CHARS, FileCaps.CSV_CELL_CHARS)
}

/**
 * Unsaved changes on leaving (`_confirmLeave` of the 2.15.3): Discard, Cancel, Save. Save leaves once
 * the file is written, and only then: a failure keeps the dialog, its reason in words (D3).
 */
@Composable
private fun LeaveDialog(state: CsvEditorState, onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(R.string.csv_editor_leave_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.csv_editor_leave_message))
                state.saveFailure?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !state.saving) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard, enabled = !state.saving) { Text(stringResource(R.string.csv_editor_discard)) }
                TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** The versions the saves replaced (docs D-021), newest first: one restored is loaded in the editor, not written. */
@Composable
private fun HistoryDialog(versions: List<HistoryCopy>, onRestore: (HistoryCopy) -> Unit, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM).withLocale(locale)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.csv_editor_history)) },
        text = {
            if (versions.isEmpty()) {
                Text(stringResource(R.string.csv_editor_history_empty, EditorHistory.KEPT, FileCaps.EDITOR_HISTORY_COPY / MIB))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.csv_editor_history_hint), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(versions, key = { it.file.name }) { copy ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val date = formatter.format(Instant.ofEpochMilli(copy.savedAtMs).atZone(ZoneId.systemDefault()))
                                Text(stringResource(R.string.joined_dot, date, formatBytes(copy.size)), Modifier.weight(1f))
                                TextButton(onClick = { onRestore(copy) }) { Text(stringResource(R.string.action_restore)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
