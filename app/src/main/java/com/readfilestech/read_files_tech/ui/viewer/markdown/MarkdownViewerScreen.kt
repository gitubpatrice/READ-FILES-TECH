package com.readfilestech.read_files_tech.ui.viewer.markdown

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownAlignment
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownBlock
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownCell
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownCode
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownContent
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownRendering
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownRule
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownRun
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownStyle
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownTableRow
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownText
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import com.readfilestech.read_files_tech.ui.viewer.text.FontSizeMenu
import com.readfilestech.read_files_tech.ui.viewer.text.TextRows

/** The 2.15.3's size of the source, in points. */
private const val SOURCE_FONT_SIZE = 14

/** The 2.15.3's line height of the rendered text. */
private const val LINE_HEIGHT = 1.7f

/** Lists and quotes deeper than this are indented no further: the text keeps room. */
private const val MAX_INDENT_LEVELS = 8
private val LIST_INDENT = 24.dp
private val QUOTE_INDENT = 12.dp
private val QUOTE_BAR = 3.dp
private val CELL_WIDTH = 160.dp
private const val IMAGE_CONTENT = "image"

/**
 * `MdViewerScreen` of the 2.15.3: the text rendered, or its source in a size from 10 to 20 pt, share. No
 * image is loaded — neither from the web nor from the storage: its place shows an icon and its description.
 * A link opens in another app when tapped, if it leads to the web or to an e-mail address. A file too long
 * to render opens on its source, saying why.
 */
@Composable
fun MarkdownViewerScreen(onBack: () -> Unit, viewModel: MarkdownViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    var showSource by rememberSaveable { mutableStateOf(false) }
    var fontSize by rememberSaveable { mutableIntStateOf(SOURCE_FONT_SIZE) }
    val source = viewModel.source
    val context = LocalContext.current

    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = { content ->
            if (content.rendering is MarkdownRendering.Rendered) {
                IconButton(onClick = { showSource = !showSource }) {
                    if (showSource) {
                        Icon(Icons.Outlined.Preview, contentDescription = stringResource(R.string.markdown_show_rendered))
                    } else {
                        Icon(Icons.Outlined.Code, contentDescription = stringResource(R.string.markdown_show_source))
                    }
                }
            }
            if (showSource || content.rendering !is MarkdownRendering.Rendered) FontSizeMenu(fontSize) { fontSize = it }
        },
    ) { content, padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val rendering = content.rendering) {
                is MarkdownRendering.Rendered -> if (showSource) {
                    TextRows(content.source, fontSize, code = true)
                } else {
                    RenderedBlocks(rendering.blocks) { viewModel.open(context, it) }
                }
                else -> SourceOnly(content, rendering, fontSize)
            }
        }
    }
}

@Composable
private fun SourceOnly(content: MarkdownContent, rendering: MarkdownRendering, fontSize: Int) {
    val reason = when (rendering) {
        is MarkdownRendering.TooLong -> stringResource(R.string.markdown_too_long, formatBytes(rendering.capBytes))
        is MarkdownRendering.TooManyCells -> stringResource(R.string.markdown_too_many_cells, rendering.limit)
        is MarkdownRendering.TooManyBrackets -> stringResource(R.string.markdown_too_many_brackets, rendering.limit)
        is MarkdownRendering.Rendered -> null
    }
    Column(Modifier.fillMaxSize()) {
        reason?.let {
            Text(
                it,
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextRows(content.source, fontSize, code = true)
    }
}

@Composable
private fun RenderedBlocks(blocks: List<MarkdownBlock>, onLink: (String) -> Unit) {
    // One horizontal scroll per table, shared by its rows: they scroll as one.
    val tableScrolls = remember(blocks) { HashMap<Int, ScrollState>() }
    val quoteColor = MaterialTheme.colorScheme.outlineVariant
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
            items(blocks.size) { index ->
                val block = blocks[index]
                val levels = minOf(block.lists + block.quotes, MAX_INDENT_LEVELS)
                val quotes = minOf(block.quotes, levels)
                // A list item's marker sits in the indent of its list, before the text.
                val marked = block is MarkdownText && block.marker != null && levels > quotes
                val indent = QUOTE_INDENT * quotes + LIST_INDENT * (levels - quotes - if (marked) 1 else 0)
                Box(
                    Modifier.fillMaxWidth()
                        .padding(top = if (index > 0 && startsItem(block, blocks[index - 1])) 8.dp else 0.dp)
                        .drawBehind {
                            repeat(quotes) { level ->
                                drawRect(quoteColor, Offset(QUOTE_INDENT.toPx() * level, 0f), Size(QUOTE_BAR.toPx(), size.height))
                            }
                        }
                        .padding(start = indent),
                ) {
                    Block(block, onLink) { table -> tableScrolls.getOrPut(table) { ScrollState(0) } }
                }
            }
        }
    }
}

/** Whether [block] starts a new item rather than going on with [previous] (a piece of one paragraph, a table's rows). */
private fun startsItem(block: MarkdownBlock, previous: MarkdownBlock): Boolean = when (block) {
    is MarkdownText -> !block.continued
    is MarkdownCode -> !block.continued
    is MarkdownTableRow -> previous !is MarkdownTableRow || previous.table != block.table
    is MarkdownRule -> true
}

@Composable
private fun Block(block: MarkdownBlock, onLink: (String) -> Unit, tableScroll: (Int) -> ScrollState) {
    when (block) {
        is MarkdownText -> TextBlock(block, onLink)
        is MarkdownCode -> Text(
            block.text,
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        is MarkdownRule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        is MarkdownTableRow -> TableRowBlock(block, onLink, tableScroll(block.table))
    }
}

@Composable
private fun TextBlock(block: MarkdownText, onLink: (String) -> Unit) {
    val typography = MaterialTheme.typography
    val style = if (block.heading == 0) {
        typography.bodyMedium.copy(lineHeight = LINE_HEIGHT.em)
    } else {
        // Headings 1 to 3 have a size each, the deeper ones the smallest.
        val sizes = listOf(typography.headlineSmall, typography.titleLarge, typography.titleMedium)
        sizes.getOrElse(block.heading - 1) { typography.titleSmall }.copy(fontWeight = FontWeight.Bold)
    }
    val text = rememberAnnotated(block.text, block.runs, onLink)
    val marker = block.marker
    if (marker == null) {
        RunText(text, style)
    } else {
        Row {
            Text(marker, Modifier.width(LIST_INDENT), style = style)
            RunText(text, style)
        }
    }
}

@Composable
private fun TableRowBlock(row: MarkdownTableRow, onLink: (String) -> Unit, scroll: ScrollState) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val style = MaterialTheme.typography.bodyMedium.let { if (row.header) it.copy(fontWeight = FontWeight.Bold) else it }
    Row(Modifier.horizontalScroll(scroll).height(IntrinsicSize.Min)) {
        row.cells.forEach { cell ->
            Box(Modifier.width(CELL_WIDTH).fillMaxHeight().border(0.5.dp, border).padding(6.dp)) {
                RunText(rememberAnnotated(cell.text, cell.runs, onLink), style.copy(textAlign = alignOf(cell)))
            }
        }
    }
}

private fun alignOf(cell: MarkdownCell): TextAlign = when (cell.alignment) {
    MarkdownAlignment.CENTER -> TextAlign.Center
    MarkdownAlignment.RIGHT -> TextAlign.End
    MarkdownAlignment.LEFT, null -> TextAlign.Start
}

/** A text with its images' places drawn as an icon. */
@Composable
private fun RunText(text: AnnotatedString, style: TextStyle) {
    val description = stringResource(R.string.markdown_image)
    val outline = MaterialTheme.colorScheme.outline
    val images = remember(description, outline) {
        mapOf(
            IMAGE_CONTENT to InlineTextContent(Placeholder(1.2.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Icon(Icons.Outlined.Image, contentDescription = description, tint = outline)
            },
        )
    }
    Text(text, style = style, color = MaterialTheme.colorScheme.onSurface, inlineContent = images)
}

/** [text] drawn as its [runs] say; a tap on a link calls [onLink] with its destination, nothing more. */
@Composable
private fun rememberAnnotated(text: String, runs: List<MarkdownRun>, onLink: (String) -> Unit): AnnotatedString {
    val currentOnLink by rememberUpdatedState(onLink)
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val imageText = stringResource(R.string.markdown_image)
    return remember(text, runs, linkColor, codeBackground, imageText) {
        buildAnnotatedString {
            var at = 0
            for (run in runs) {
                if (run.start > at) append(text, at, run.start)
                val piece: AnnotatedString.Builder.() -> Unit = {
                    if (run.style == MarkdownStyle.IMAGE) {
                        appendInlineContent(IMAGE_CONTENT, imageText)
                    } else {
                        withStyle(spanOf(run.style, codeBackground)) { append(text, run.start, run.end) }
                    }
                }
                val link = run.link
                if (link == null) {
                    piece()
                } else {
                    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    withLink(LinkAnnotation.Clickable(link, styles) { currentOnLink(link) }) { piece() }
                }
                at = run.end
            }
            if (at < text.length) append(text, at, text.length)
        }
    }
}

private fun spanOf(style: Int, codeBackground: Color): SpanStyle = SpanStyle(
    fontWeight = if (style and MarkdownStyle.BOLD != 0) FontWeight.Bold else null,
    fontStyle = if (style and MarkdownStyle.ITALIC != 0) FontStyle.Italic else null,
    textDecoration = if (style and MarkdownStyle.STRIKE != 0) TextDecoration.LineThrough else null,
    fontFamily = if (style and MarkdownStyle.CODE != 0) FontFamily.Monospace else null,
    background = if (style and MarkdownStyle.CODE != 0) codeBackground else Color.Unspecified,
)
