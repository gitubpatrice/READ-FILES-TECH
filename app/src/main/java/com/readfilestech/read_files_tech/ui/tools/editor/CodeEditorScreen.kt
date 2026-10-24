package com.readfilestech.read_files_tech.ui.tools.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.TextFiles
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.tools.ToolIntro
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The text sizes of the 2.15.3's menu, in points (`code_editor_screen.dart:374`), and its default. */
@Suppress("MagicNumber") // the menu's values themselves, as the 2.15.3 listed them
private val TEXT_SIZES = listOf(10, 11, 12, 13, 14, 16, 18)
private const val DEFAULT_TEXT_SIZE = 13
private const val LINE_HEIGHT = 1.5f

/**
 * `CodeEditorScreen` of the 2.15.3: a text file in a fixed-width font, its size 10 to 18 pt, the
 * "modified" mark, undo and redo, save, the history of the saved versions, share, and the leave dialog
 * "Discard / Cancel / Save". From the home's grid the file is chosen in the app's picker; from the
 * explorer's or the picker's "Edit", it comes with the route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CodeEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chooseTitle = stringResource(R.string.editor_choose)
    LaunchedEffect(picked.files) {
        picked.files?.firstOrNull()?.let {
            picked.consume()
            viewModel.open(it)
        }
    }
    LaunchedEffect(state.left) { if (state.left) onBack() }
    BackHandler(enabled = state.modified && !state.leaving) { viewModel.askToLeave() }
    var textSize by rememberSaveable { mutableIntStateOf(DEFAULT_TEXT_SIZE) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Title(state) },
                navigationIcon = {
                    IconButton(onClick = viewModel::askToLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { if (state.isOpen) BarActions(state, viewModel, textSize) { textSize = it } },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.isOpen -> Editor(viewModel.text, textSize, readOnly = state.saving)
                // No file yet, or the one chosen did not open: the failure says why, in words (F34).
                else -> ToolIntro(
                    Icons.Outlined.Edit,
                    stringResource(R.string.feature_editor),
                    state.failure?.resolve() ?: stringResource(R.string.editor_intro),
                    { onNavigate(Routes.picker(PickMode.FILE, extensions = TextFiles.EXTENSIONS, title = chooseTitle)) },
                    isError = state.failure != null,
                )
            }
        }
    }
    Dialogs(state, viewModel)
}

@Composable
private fun Dialogs(state: CodeEditorState, viewModel: CodeEditorViewModel) {
    when {
        // Over the leave dialog when its Save found the file changed: Cancel brings it back.
        state.changedOnDisk -> ChangedOnDiskDialog(
            state.name,
            state.saving,
            state.saveFailure,
            keptInHistory = true,
            onOverwrite = viewModel::overwrite,
            onCancel = viewModel::keepOnDisk,
        )
        state.leaving -> LeaveDialog(
            state,
            onSave = { viewModel.leave(LeaveChoice.SAVE) },
            onDiscard = { viewModel.leave(LeaveChoice.DISCARD) },
            onDismiss = { viewModel.leave(LeaveChoice.CANCEL) },
        )
    }
    val preview = state.preview
    val versions = state.versions
    when {
        preview != null -> PreviewDialog(
            preview,
            onRestore = { viewModel.restore(preview.copy) },
            onDismiss = viewModel::hidePreview,
        )
        versions != null -> HistoryDialog(versions, onPreview = viewModel::preview, onDismiss = viewModel::hideHistory)
    }
}

/** The file's name; under it, its encoding — what a save keeps — and "modified" while something is not saved. */
@Composable
private fun Title(state: CodeEditorState) {
    Column {
        Text(state.name.ifEmpty { stringResource(R.string.feature_editor) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val encoding = state.encoding ?: return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                CodeEditorSession.encodingName(encoding).resolve(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.modified) {
                Text(
                    stringResource(R.string.editor_modified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

// The text field's undo history (UndoState) is still marked experimental.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarActions(state: CodeEditorState, viewModel: CodeEditorViewModel, textSize: Int, onTextSize: (Int) -> Unit) {
    val undo = viewModel.text.undoState
    val editable = !state.saving
    IconButton(onClick = undo::undo, enabled = editable && undo.canUndo) {
        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.action_undo))
    }
    IconButton(onClick = undo::redo, enabled = editable && undo.canRedo) {
        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.editor_redo))
    }
    if (state.modified) {
        IconButton(onClick = viewModel::save, enabled = editable) {
            if (state.saving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.action_save))
            }
        }
    }
    val context = LocalContext.current
    val shareable by viewModel.shareable.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var sizes by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.editor_history)) }, enabled = editable, onClick = {
                menu = false
                viewModel.showHistory()
            })
            shareable?.let { entry ->
                DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                    menu = false
                    viewModel.launching.share(context, listOf(entry))
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.text_size)) }, onClick = {
                menu = false
                sizes = true
            })
        }
        DropdownMenu(expanded = sizes, onDismissRequest = { sizes = false }) {
            TEXT_SIZES.forEach { size ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.editor_text_size_value, size),
                            fontWeight = if (size == textSize) FontWeight.Bold else null,
                        )
                    },
                    onClick = {
                        sizes = false
                        onTextSize(size)
                    },
                )
            }
        }
    }
}

/**
 * The text, in a fixed-width font, wrapped as the 2.15.3's. Read only while it is written to the file:
 * what is saved is what was on screen.
 */
@Composable
private fun Editor(text: TextFieldState, textSize: Int, readOnly: Boolean) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        state = text,
        modifier = Modifier.fillMaxSize().imePadding().padding(16.dp),
        readOnly = readOnly,
        inputTransformation = LineBreaksAsLf,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = textSize.sp,
            lineHeight = LINE_HEIGHT.em,
            color = colors.onSurface,
        ),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        cursorBrush = SolidColor(colors.primary),
    )
}

/**
 * A CR or a CRLF typed or pasted becomes a LF: the field holds the editor's one kind of line break, and
 * the save writes each the file's way ([com.readfilestech.read_files_tech.data.editor.EditableText]).
 * Only around what changed: the rest of the text is already so.
 */
private object LineBreaksAsLf : InputTransformation {
    // What changed (TextFieldBuffer.changes) is still marked experimental.
    @OptIn(ExperimentalFoundationApi::class)
    override fun TextFieldBuffer.transformInput() {
        val changed = (0 until changes.changeCount).map { change ->
            val range = changes.getRange(change)
            intArrayOf(range.min, range.max)
        }
        val text = asCharSequence()
        // Every rewrite read before any is made (a CR then a CRLF are two line breaks, as when opening),
        // then made from the end, so that the places before do not move. One replace per place: one per CR
        // made the buffer track as many changes, each against the others — a paste of many CRLF stalled
        // the screen (Claude review R22).
        val rewrites = LineBreakInput.places(changed, length).mapNotNull { (from, to) -> LineBreakInput.rewrite(text, from, to) }
        if (rewrites.isEmpty()) return
        // The selection kept among the characters kept: a cursor inside a span went to its end (R23).
        val start = LineBreakInput.moved(selection.start, text, rewrites)
        val end = LineBreakInput.moved(selection.end, text, rewrites)
        rewrites.asReversed().forEach { replace(it.start, it.end, it.text) }
        selection = TextRange(start, end)
    }
}

/**
 * Unsaved changes on leaving (`_onWillPop` of the 2.15.3): Discard, Cancel, Save. Save leaves once the
 * file is written, and only then: a failure keeps the dialog, its reason in words (D3).
 */
@Composable
private fun LeaveDialog(state: CodeEditorState, onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(R.string.editor_leave_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.editor_leave_message))
                state.saveFailure?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { SaveButton(state.saving, R.string.action_save, onSave) },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard, enabled = !state.saving) { Text(stringResource(R.string.editor_discard)) }
                TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun SaveButton(saving: Boolean, label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !saving) {
        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(label))
    }
}

@Composable
private fun rememberDateFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM).withLocale(locale)
    }
}

// When the copy was WRITTEN, as the CSV editor shows it: its name orders the copies, and runs ahead after a clock change (R21).
private fun DateTimeFormatter.date(copy: HistoryCopy): String =
    format(Instant.ofEpochMilli(copy.writtenAtMs).atZone(ZoneId.systemDefault()))

/**
 * The versions the saves replaced (docs D-021), newest first, and apart those the 2.15.x kept under the
 * file's name — maybe another file's. A tap shows a version's beginning, from where it is restored.
 */
@Composable
private fun HistoryDialog(versions: EditorVersions, onPreview: (HistoryCopy, Boolean) -> Unit, onDismiss: () -> Unit) {
    val formatter = rememberDateFormatter()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_history)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        if (versions.versions.isEmpty()) {
                            stringResource(R.string.editor_history_empty, EditorHistory.KEPT)
                        } else {
                            stringResource(R.string.editor_history_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(versions.versions, key = { it.file.path }) { VersionRow(it, formatter) { onPreview(it, false) } }
                if (versions.legacy.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(stringResource(R.string.editor_legacy_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.editor_legacy_warning), style = MaterialTheme.typography.bodySmall)
                    }
                    items(versions.legacy, key = { it.file.path }) { VersionRow(it, formatter) { onPreview(it, true) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun VersionRow(copy: HistoryCopy, formatter: DateTimeFormatter, onClick: () -> Unit) {
    Text(
        stringResource(R.string.joined_dot, formatter.date(copy), formatBytes(copy.size)),
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

/** A version's first lines; Restore loads it in the editor — the file is written only when the user saves. */
@Composable
private fun PreviewDialog(preview: VersionPreview, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val formatter = rememberDateFormatter()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(formatter.date(preview.copy)) },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (preview.legacy) Text(stringResource(R.string.editor_legacy_warning), style = MaterialTheme.typography.bodySmall)
                Text(
                    preview.lines.joinToString("\n").ifEmpty { stringResource(R.string.preview_empty) },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onRestore) { Text(stringResource(R.string.action_restore)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
