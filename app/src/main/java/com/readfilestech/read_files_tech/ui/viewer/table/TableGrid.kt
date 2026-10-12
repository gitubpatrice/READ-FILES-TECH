package com.readfilestech.read_files_tech.ui.viewer.table

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A column as wide as the 2.15.3's: 120 dp of text and 16 between columns. */
private val COLUMN_WIDTH = 136.dp
private val CELL_PADDING = 8.dp
private val ROW_HEIGHT = 48.dp
private val HEADER_HEIGHT = 44.dp

/**
 * What a [TableGrid] lays out: a header, [rows] rows under it, [columns] wide. The grid asks for the cells
 * that show only — a range of columns of one row at a time — on the main thread: an implementation reads
 * them quickly (the CSV viewer decodes them from the file's bytes, the spreadsheet viewer formats them).
 */
@Stable
interface TableCells {
    val columns: Int
    val rows: Int

    fun header(first: Int, until: Int): List<String>

    fun row(position: Int, first: Int, until: Int): List<String>
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
 * The header and the rows of a table, lazy both ways (the CSV and spreadsheet viewers'): the rows by a
 * LazyColumn, the columns by a scroll of their own — the rows lay out only the columns that show, each one
 * shifted by what is scrolled past. A row of 16 384 columns lays out the six that show.
 *
 * @param headerCell one cell of the header, [TableHeaderText] by default.
 * @param aboveRows what shows between the header and the rows (the CSV viewer's "no row contains…").
 */
@Composable
fun TableGrid(
    cells: TableCells,
    list: LazyListState,
    headerCell: @Composable (column: Int, text: String) -> Unit = { _, text -> TableHeaderText(text) },
    aboveRows: @Composable () -> Unit = {},
) {
    val columnWidth = with(LocalDensity.current) { COLUMN_WIDTH.toPx() }
    var viewport by remember { mutableIntStateOf(0) }
    val scroll = rememberSaveable(saver = HorizontalScroll.Saver) { HorizontalScroll(0f) }
    SideEffect { scroll.max = maxOf(0f, cells.columns * columnWidth - viewport) }
    val scrollable = rememberScrollableState(scroll::consume)
    val scope = rememberCoroutineScope()
    val firstShown by remember(scroll, columnWidth) { derivedStateOf { (scroll.offset / columnWidth).toInt() } }
    // The columns composed, and the shift measured from the first of THEM: crossing into the next column,
    // the frame before the rows are composed again stays in place (one column more than the screen holds).
    val first = firstShown
    val until = minOf(cells.columns, first + ceil(viewport / columnWidth).toInt() + 1)
    val shift = { (scroll.offset - first * columnWidth).roundToInt() }
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
        val header = remember(cells, first, until) { cells.header(first, until) }
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            CellsRow(header, shift, HEADER_HEIGHT) { index, text -> headerCell(first + index, text) }
        }
        HorizontalDivider()
        aboveRows()
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(cells.rows) { position ->
                val row = remember(cells, position, first, until) { cells.row(position, first, until) }
                CellsRow(row, shift, ROW_HEIGHT) { _, text ->
                    Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** A header cell's text, bold, on two lines at most. */
@Composable
fun TableHeaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A line said instead of rows, or above them. */
@Composable
fun TableMessage(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
