package com.readfilestech.read_files_tech.ui.viewer.csv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.FolderSearchField
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A column as wide as the 2.15.3's: 120 dp of text and 16 between columns. */
private val COLUMN_WIDTH = 136.dp
private val CELL_PADDING = 8.dp
private val ROW_HEIGHT = 48.dp
private val HEADER_HEIGHT = 44.dp

/**
 * `CsvViewerScreen` of the 2.15.3: the table under its header, a filter on every cell, a sort by a tap on
 * a column's name (again to reverse it), the CSV editor (phase 5 of the port), share. The 2.15.3 laid
 * every cell out at once in a `DataTable`; here only the rows AND the columns on screen exist — a row
 * of 16 384 columns lays out the six that show.
 */
@Composable
fun CsvViewerScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, viewModel: CsvViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val source = viewModel.source
    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = {
            // Edited in place, a file of the storage only: one of the vault (phase 6) has no place to be.
            if (source?.shareable != null) {
                IconButton(onClick = { onNavigate(Routes.feature(Feature.CSV_EDITOR)) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
        },
    ) { table, padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val current = shown
            when {
                table.isEmpty -> Message(stringResource(R.string.preview_empty))
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

/** Where the table is, across: in pixels, within its width. */
@Stable
private class HorizontalScroll(initial: Float) {
    var offset by mutableFloatStateOf(initial)
        private set

    var max = 0f
        set(value) {
            field = value
            offset = offset.coerceIn(0f, value)
        }

    /** Moves by [delta] pixels, forward when positive; what it moved. */
    fun consume(delta: Float): Float {
        val next = (offset + delta).coerceIn(0f, max)
        val consumed = next - offset
        offset = next
        return consumed
    }

    companion object {
        /** Kept across a rotation; clamped to the width again once laid out. */
        val Saver: Saver<HorizontalScroll, Float> = Saver(save = { it.offset }, restore = { HorizontalScroll(it) })
    }
}

/**
 * The header and the rows, lazy both ways: the rows by a LazyColumn, the columns by a scroll of their
 * own — the rows lay out only the columns that show, each one shifted by what is scrolled past.
 */
@Composable
private fun CsvGrid(table: CsvTable, shown: CsvShown, onSort: (Int) -> Unit) {
    val columnWidth = with(LocalDensity.current) { COLUMN_WIDTH.toPx() }
    var viewport by remember { mutableIntStateOf(0) }
    val scroll = rememberSaveable(saver = HorizontalScroll.Saver) { HorizontalScroll(0f) }
    SideEffect { scroll.max = maxOf(0f, table.columns * columnWidth - viewport) }
    val scrollable = rememberScrollableState(scroll::consume)
    val scope = rememberCoroutineScope()
    val firstShown by remember(scroll, columnWidth) { derivedStateOf { (scroll.offset / columnWidth).toInt() } }
    // The columns composed, and the shift measured from the first of THEM: crossing into the next column,
    // the frame before the rows are composed again stays in place (one column more than the screen holds).
    val first = firstShown
    val until = minOf(table.columns, first + ceil(viewport / columnWidth).toInt() + 1)
    val shift = { (scroll.offset - first * columnWidth).roundToInt() }
    val list = rememberLazyListState()
    ScrollToTopOnChange(shown) { list.scrollToItem(0) }
    Column(
        Modifier.fillMaxSize()
            .onSizeChanged { viewport = it.width }
            .clipToBounds()
            // TalkBack scrolls the columns as it would a horizontalScroll.
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange(value = { scroll.offset }, maxValue = { scroll.max })
                scrollBy { x, _ ->
                    scope.launch { scrollable.scrollBy(x) }
                    true
                }
            }
            // As horizontalScroll does in a left-to-right layout: a drag to the right moves back.
            .scrollable(scrollable, Orientation.Horizontal, reverseDirection = true),
    ) {
        HeaderRow(table, first, until, shift, shown.sort, onSort)
        if (shown.view.size == 0 && shown.query.isNotEmpty() && !shown.busy) {
            Message(stringResource(R.string.csv_no_match, shown.query))
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(shown.view.size) { position ->
                val row = shown.view.row(position)
                val cells = remember(table, row, first, until) { table.cells(row, first, until, CsvTable.SHOWN_CHARS) }
                CellsRow(cells, shift, ROW_HEIGHT) { _, text ->
                    Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
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

@Composable
private fun HeaderRow(table: CsvTable, first: Int, until: Int, shift: () -> Int, sort: CsvSortChoice?, onSort: (Int) -> Unit) {
    val cells = remember(table, first, until) { table.header(first, until, CsvTable.SHOWN_CHARS) }
    val sortLabel = stringResource(R.string.csv_sort)
    val ascending = stringResource(R.string.csv_sorted_ascending)
    val descending = stringResource(R.string.csv_sorted_descending)
    val numbered = stringResource(R.string.csv_column_number)
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        CellsRow(cells, shift, HEADER_HEIGHT) { index, text ->
            val column = first + index
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
                Text(
                    text,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sorted != null) {
                    Icon(
                        if (sorted.ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

/** One line of the table: the columns that show, [shift] pixels to the left, as wide as the screen. */
@Composable
private fun CellsRow(cells: List<String>, shift: () -> Int, height: Dp, cell: @Composable (Int, String) -> Unit) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Row(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(-shift(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells.forEachIndexed { index, text ->
                Box(
                    Modifier.width(COLUMN_WIDTH).height(height).padding(horizontal = CELL_PADDING),
                    contentAlignment = Alignment.CenterStart
                ) {
                    cell(index, text)
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
