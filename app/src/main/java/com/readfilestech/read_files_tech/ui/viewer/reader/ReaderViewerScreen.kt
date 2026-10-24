package com.readfilestech.read_files_tech.ui.viewer.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.reader.BlockKind
import com.readfilestech.read_files_tech.data.viewer.reader.ReaderBlocks
import com.readfilestech.read_files_tech.data.viewer.reader.ReaderChapter
import com.readfilestech.read_files_tech.data.viewer.reader.ReaderDocument
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailureMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
import com.readfilestech.read_files_tech.ui.viewer.text.FontSizeMenu

/** The sizes the 2.15.3 offered, in points; 16 by default. */
@Suppress("MagicNumber") // the list itself is the definition
private val FONT_SIZES = listOf(12, 14, 16, 18, 20, 24)
private const val DEFAULT_FONT_SIZE = 16

/** The 2.15.3's line height, and its headings' sizes, in the text's. */
private val LINE_HEIGHT = 1.6.em
private const val HEADING_1 = 1.6f
private const val HEADING_2 = 1.35f
private const val HEADING_3 = 1.15f

/**
 * `ReaderViewerScreen` of the 2.15.3: a book's chapter, or a page in reading mode, as blocks of text laid out
 * lazily — headings, paragraphs, quotes, list items — selectable; a text size from 12 to 24 pt; a book's
 * chapters listed by title, and the one before and after. The chapter shown is read when chosen; one that
 * cannot be says why in its place.
 */
@Composable
fun ReaderViewerScreen(onBack: () -> Unit, viewModel: ReaderViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val chapter by viewModel.chapter.collectAsStateWithLifecycle()
    var fontSize by rememberSaveable { mutableIntStateOf(DEFAULT_FONT_SIZE) }
    var listing by rememberSaveable { mutableStateOf(false) }
    val source = viewModel.source

    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = { document ->
            FontSizeMenu(fontSize, FONT_SIZES) { fontSize = it }
            if (document.chapterCount > 1) {
                IconButton(onClick = { listing = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Toc, contentDescription = stringResource(R.string.reader_chapters))
                }
            }
        },
    ) { document, padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (document.chapterCount == 0) {
                    ViewerMessage(stringResource(R.string.reader_no_chapter))
                } else {
                    Shown(chapter, selected, fontSize)
                }
            }
            if (document.chapterCount > 1) {
                val title = (chapter as? ViewerState.Ready)?.content?.title ?: document.title(selected)
                ChapterBar(selected, document.chapterCount, title, viewModel::show)
            }
        }
        if (listing) {
            ChapterList(document, selected, onChoose = { index ->
                listing = false
                viewModel.show(index)
            }, onDismiss = { listing = false })
        }
    }
}

@Composable
private fun Shown(chapter: ViewerState<ReaderChapter>, selected: Int, fontSize: Int) {
    when (chapter) {
        ViewerState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ViewerState.Failed -> ViewerFailureMessage(chapter.failure, PaddingValues())
        is ViewerState.Ready -> if (chapter.content.blocks.size == 0) {
            ViewerMessage(stringResource(R.string.reader_empty))
        } else {
            // A chapter chosen starts at its top: one scroll position per chapter.
            key(selected) { Blocks(chapter.content.blocks, fontSize) }
        }
    }
}

/** The blocks, laid out lazily: only those on screen exist. */
@Composable
private fun Blocks(blocks: ReaderBlocks, fontSize: Int) {
    val base = TextStyle(fontSize = fontSize.sp, lineHeight = LINE_HEIGHT, color = MaterialTheme.colorScheme.onSurface)
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 80.dp),
        ) {
            items(blocks.size) { index -> Block(blocks.kind(index), blocks.text(index), base, fontSize) }
        }
    }
}

@Composable
private fun Block(kind: BlockKind, text: String, base: TextStyle, fontSize: Int) {
    when (kind) {
        BlockKind.HEADING_1 -> Heading(text, base, fontSize * HEADING_1, FontWeight.Bold, 12.dp)
        BlockKind.HEADING_2 -> Heading(text, base, fontSize * HEADING_2, FontWeight.Bold, 10.dp)
        BlockKind.HEADING_3 -> Heading(text, base, fontSize * HEADING_3, FontWeight.SemiBold, 8.dp)
        BlockKind.QUOTE -> {
            val rule = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            Text(
                text,
                style = base.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(vertical = 8.dp)
                    .drawBehind { drawLine(rule, Offset.Zero, Offset(0f, size.height), strokeWidth = 3.dp.toPx()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        BlockKind.ITEM -> Row(Modifier.padding(vertical = 4.dp)) {
            Text("• ", style = base)
            Text(text, style = base, modifier = Modifier.weight(1f))
        }
        BlockKind.PARAGRAPH -> Text(text, style = base, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
private fun Heading(text: String, base: TextStyle, size: Float, weight: FontWeight, vertical: Dp) {
    Text(text, style = base.copy(fontSize = size.sp, fontWeight = weight), modifier = Modifier.padding(vertical = vertical))
}

/** The chapter before, where the reader is and in which chapter, the chapter after (the 2.15.3's bottom bar). */
@Composable
private fun ChapterBar(selected: Int, count: Int, title: String, onShow: (Int) -> Unit) {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShow(selected - 1) }, enabled = selected > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.reader_previous))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.reader_position, selected + 1, count), style = MaterialTheme.typography.labelSmall)
            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onShow(selected + 1) }, enabled = selected < count - 1) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.reader_next))
        }
    }
}

/** Every chapter by its title, the one shown in bold, laid out lazily: a book may have thousands. */
@Composable
private fun ChapterList(document: ReaderDocument, selected: Int, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.reader_chapters)) },
        text = {
            LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = selected)) {
                items(document.chapterCount) { index ->
                    Text(
                        stringResource(R.string.reader_chapter_entry, index + 1, document.title(index)),
                        fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(index) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
    )
}
