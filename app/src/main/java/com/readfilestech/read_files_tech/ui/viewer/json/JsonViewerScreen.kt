package com.readfilestech.read_files_tech.ui.viewer.json

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.json.JsonContent
import com.readfilestech.read_files_tech.data.viewer.json.JsonDocument
import com.readfilestech.read_files_tech.data.viewer.json.JsonKind
import com.readfilestech.read_files_tech.data.viewer.json.JsonPretty
import com.readfilestech.read_files_tech.data.viewer.json.JsonRefusal
import com.readfilestech.read_files_tech.data.viewer.json.JsonTree
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import com.readfilestech.read_files_tech.ui.viewer.text.TextRows
import kotlinx.coroutines.launch

/** The 2.15.3's size for JSON: 13 pt in the tree, 12 in the indented text. */
private const val TREE_FONT_SIZE = 13
private const val PRETTY_FONT_SIZE = 12

/** The longest key or value shown on one row: the text viewer's row. */
private const val SHOWN_CHARS = TextDocument.ROW_BYTES

/** The 2.15.3's colours of the values (Flutter's green, orange, blue and grey). */
@Suppress("MagicNumber") // the colours themselves are the definition
private object ValueColors {
    val STRING = Color(0xFF4CAF50)
    val NUMBER = Color(0xFFFF9800)
    val BOOLEAN = Color(0xFF2196F3)
    val NULL = Color(0xFF9E9E9E)
}

/**
 * `JsonViewerScreen` of the 2.15.3: the tree (a tap opens or closes an array or an object, a long press
 * copies a value), the indented text, share. A file that is not JSON, or past the caps, is shown as text,
 * saying why.
 */
@Composable
fun JsonViewerScreen(onBack: () -> Unit, viewModel: JsonViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    val indented by viewModel.indented.collectAsStateWithLifecycle()
    val source = viewModel.source

    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = { opened ->
            val content = opened.content
            if (content is JsonContent.Tree) {
                IconButton(onClick = { viewModel.toggleView(content.document) }) {
                    if (indented) {
                        Icon(Icons.Outlined.AccountTree, contentDescription = stringResource(R.string.json_show_tree))
                    } else {
                        Icon(Icons.Outlined.DataObject, contentDescription = stringResource(R.string.json_show_pretty))
                    }
                }
            }
        },
    ) { opened, padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val content = opened.content) {
                is JsonContent.Text -> RefusedText(content)
                is JsonContent.Tree -> if (indented) {
                    LaunchedEffect(content.document) { viewModel.build(content.document) }
                    PrettyLines(viewModel)
                } else {
                    opened.tree?.let { TreeRows(content.document, it, viewModel) }
                }
            }
        }
    }
}

@Composable
private fun RefusedText(content: JsonContent.Text) {
    val reason = when (val refusal = content.refusal) {
        is JsonRefusal.Syntax -> stringResource(R.string.json_not_json, refusal.line, refusal.column)
        is JsonRefusal.TooDeep -> stringResource(R.string.json_too_deep, refusal.limit)
        is JsonRefusal.TooManyValues -> stringResource(R.string.json_too_many_values, refusal.limit)
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            reason,
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextRows(content.text, TREE_FONT_SIZE, code = true)
    }
}

@Composable
private fun PrettyLines(viewModel: JsonViewerViewModel) {
    val pretty: JsonPretty? by viewModel.pretty.collectAsStateWithLifecycle()
    val lines = pretty
    if (lines == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val style = TextStyle(fontSize = PRETTY_FONT_SIZE.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(lines.rowCount) { row -> Text(lines.line(row, SHOWN_CHARS), style = style) }
        }
    }
}

/**
 * The tree as Compose reads it: [JsonTree] changes in place, so a toggle goes through here and bumps
 * [rows] and [changes], which what reads the tree reads too.
 */
@Stable
private class TreeState(private val tree: JsonTree) {
    var rows by mutableIntStateOf(tree.rowCount)
        private set
    private var changes by mutableIntStateOf(0)

    fun value(row: Int): Int = tree.value(row)

    fun ordinal(row: Int): Int = tree.ordinal(row)

    fun childCount(container: Int): Int = tree.childCount(container)

    fun isOpen(value: Int): Boolean = changes >= 0 && tree.isOpen(value)

    fun toggle(row: Int) {
        tree.toggle(row)
        rows = tree.rowCount
        changes++
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRows(document: JsonDocument, tree: JsonTree, viewModel: JsonViewerViewModel) {
    val state = remember(tree) { TreeState(tree) }
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        items(state.rows, key = { state.value(it) }) { row ->
            val value = state.value(row)
            val container = document.isContainer(value)
            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (container) state.toggle(row) },
                    onLongClick = {
                        if (!container) {
                            viewModel.copyText(document, value)?.let { text ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) }
                                viewModel.copied()
                            }
                        }
                    },
                ).padding(start = (minOf(document.depth(value), JsonPretty.MAX_INDENT_LEVELS) * INDENT_DP).dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (container) {
                    Icon(
                        if (state.isOpen(value)) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = ValueColors.NULL,
                    )
                } else {
                    Spacer(Modifier.width(18.dp))
                }
                TreeRowText(document, state, row, value)
            }
        }
    }
}

@Composable
private fun TreeRowText(document: JsonDocument, tree: TreeState, row: Int, value: Int) {
    val kind = document.kind(value)
    val preview = when (kind) {
        JsonKind.OBJECT -> tree.childCount(value).let { pluralStringResource(R.plurals.json_keys, it, it) }
        JsonKind.ARRAY -> tree.childCount(value).let { pluralStringResource(R.plurals.json_items, it, it) }
        else -> document.literal(value, SHOWN_CHARS)
    }
    val color = when (kind) {
        JsonKind.STRING -> ValueColors.STRING
        JsonKind.NUMBER -> ValueColors.NUMBER
        JsonKind.BOOLEAN -> ValueColors.BOOLEAN
        JsonKind.NULL, JsonKind.OBJECT, JsonKind.ARRAY -> ValueColors.NULL
    }
    // A member's key as written; an element of an array its position — the 2.15.3 quoted it as if a key.
    val label = when {
        row == 0 && value == 0 -> null
        else -> document.keyLiteral(value, SHOWN_CHARS) ?: tree.ordinal(row).toString()
    }
    val primary = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            label?.let { withStyle(SpanStyle(color = primary)) { append("$it: ") } }
            withStyle(SpanStyle(color = color)) { append(preview) }
        },
        style = TextStyle(fontSize = TREE_FONT_SIZE.sp, fontFamily = FontFamily.Monospace),
    )
}

private const val INDENT_DP = 12
