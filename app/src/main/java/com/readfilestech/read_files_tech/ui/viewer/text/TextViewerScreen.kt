package com.readfilestech.read_files_tech.ui.viewer.text

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.text.TextColor
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.viewer.ViewerMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import kotlinx.coroutines.launch

/** The sizes the 2.15.3 offered, in points; 13 by default. */
@Suppress("MagicNumber") // the list itself is the definition
private val FONT_SIZES = listOf(10, 12, 13, 14, 16, 18, 20)
private const val DEFAULT_FONT_SIZE = 13

/** The 2.15.3's line height for plain text. */
private const val PLAIN_LINE_HEIGHT = 1.6f

/**
 * `TxtViewerScreen` of the 2.15.3: the text, its colours in a bar (a tap copies the code), a size
 * from 10 to 20 pt, share; code in a fixed-width font and coloured, which can be turned off. Rows are
 * laid out lazily — only those on screen exist — and coloured as they show.
 */
@Composable
fun TextViewerScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, viewModel: TextViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    // Back from its "Edit": the text the editor saved, not the one of before.
    LifecycleResumeEffect(Unit) {
        viewModel.content.reloadIfChanged()
        onPauseOrDispose { }
    }
    val colors by viewModel.colors.collectAsStateWithLifecycle()
    val codeColours by viewModel.codeColours.collectAsStateWithLifecycle()
    var fontSize by rememberSaveable { mutableIntStateOf(DEFAULT_FONT_SIZE) }
    // The bar shows by default once colours are found, as the 2.15.3's; the user's choice wins after.
    var colorsHidden by rememberSaveable { mutableStateOf(false) }
    // Coloured by default, as the 2.15.3 always was.
    var syntaxHidden by rememberSaveable { mutableStateOf(false) }
    val source = viewModel.source
    val code = viewModel.language != null

    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = {
            // Edited in place, a file of the storage only: one of the vault has no place to be — as the CSV viewer.
            source?.shareable?.let { FileRouter.editorFor(it.path, source.extension) }?.let { editor ->
                IconButton(onClick = { onNavigate(editor) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
            if (code) {
                ToggleAction(Icons.Outlined.FormatColorText, stringResource(R.string.text_syntax_colours), !syntaxHidden) {
                    syntaxHidden = !syntaxHidden
                }
            }
            if (colors.isNotEmpty()) ColorsToggle(colorsHidden) { colorsHidden = !colorsHidden }
            FontSizeMenu(fontSize) { fontSize = it }
        },
    ) { document, padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!colorsHidden && colors.isNotEmpty()) ColorBar(colors, viewModel::copied)
            if (document.isEmpty) {
                ViewerMessage(stringResource(R.string.preview_empty))
            } else {
                TextRows(document, fontSize, code, codeColours.takeUnless { syntaxHidden })
            }
        }
    }
}

/**
 * The rows of [document], laid out lazily and selectable, coloured by [colours] when given — also the
 * document viewer's, which shows a text, and the source views of the Markdown, HTML and JSON viewers.
 */
@Composable
internal fun TextRows(document: TextDocument, fontSize: Int, code: Boolean, colours: RowColours? = null) {
    val style = if (code) {
        TextStyle(fontSize = fontSize.sp, fontFamily = FontFamily.Monospace)
    } else {
        // Untrimmed: each row is its own Text, and Compose trims the line height above a Text's first line
        // and below its last — two rows sat closer than two lines of one wrapped row (seen on an .odt).
        TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * PLAIN_LINE_HEIGHT).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(document.rowCount) { index ->
                if (colours != null) {
                    ColouredRow(document, index, colours, style)
                } else {
                    Text(document.row(index), style = style, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** The text size, chosen among [sizes] (points). */
@Composable
internal fun FontSizeMenu(current: Int, sizes: List<Int> = FONT_SIZES, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.TextFields, contentDescription = stringResource(R.string.text_size))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        sizes.forEach { size ->
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.text_size_value, size),
                        fontWeight = if (size == current) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    open = false
                    onChoose(size)
                },
            )
        }
    }
}

/** Shows or hides the [ColorBar] — also the HTML viewer's. */
@Composable
internal fun ColorsToggle(hidden: Boolean, onToggle: () -> Unit) =
    ToggleAction(Icons.Outlined.Palette, stringResource(R.string.text_colors), on = !hidden, onToggle)

/** An action of the bar that is on or off — tinted when on, and said to be by TalkBack. */
@Composable
private fun ToggleAction(icon: ImageVector, description: String, on: Boolean, onToggle: () -> Unit) {
    IconToggleButton(checked = on, onCheckedChange = { onToggle() }) {
        Icon(icon, contentDescription = description, tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

/** The colours of a text, a tap copying one's code — also the HTML viewer's, from the page's source. */
@Composable
internal fun ColorBar(colors: List<TextColor>, onCopied: (String) -> Unit) {
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LazyRow(
        Modifier.fillMaxWidth().height(48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(colors, key = TextColor::code) { color ->
            Row(
                Modifier.clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(color.code, color.code))) }
                    onCopied(color.code)
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(22.dp)
                        .background(Color(color.argb), RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
                )
                Text(color.code, fontSize = 11.sp)
            }
        }
    }
    HorizontalDivider()
}
