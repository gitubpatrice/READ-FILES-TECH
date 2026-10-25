package com.readfilestech.read_files_tech.ui.tools.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.TextFiles
import com.readfilestech.read_files_tech.data.tools.DiffKind
import com.readfilestech.read_files_tech.data.tools.DiffRow
import com.readfilestech.read_files_tech.data.tools.LineComparison
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.theme.DiffColors
import com.readfilestech.read_files_tech.ui.tools.ToolIntro

private const val ROW_TINT = 0.08f
private const val LINE_NUMBER_WIDTH = 44

/**
 * `DiffScreen` of the 2.15.3: two text files, their lines compared whole — what was removed from the
 * first, added in the second, the unchanged ones around — or said identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(picked: PickedResults, onNavigate: (String) -> Unit, onBack: () -> Unit, viewModel: DiffViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(picked.files) {
        picked.files?.firstOrNull()?.let {
            picked.consume()
            viewModel.picked(it)
        }
    }
    val fileA = stringResource(R.string.diff_file_a)
    val fileB = stringResource(R.string.diff_file_b)
    val choose = { slot: DiffSlot ->
        viewModel.picking(slot)
        onNavigate(Routes.picker(PickMode.FILE, extensions = TextFiles.EXTENSIONS, title = if (slot == DiffSlot.A) fileA else fileB))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_diff)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The two files are chosen on their own rows, under it; the bar says the tool's name.
                    if (state.fileA == null && state.fileB == null) {
                        ToolIntro(Icons.Outlined.Difference, title = null, stringResource(R.string.diff_intro), onChoose = null)
                    }
                    ChosenFile(fileA, state.fileA) { choose(DiffSlot.A) }
                    ChosenFile(fileB, state.fileB) { choose(DiffSlot.B) }
                    // The whole row is the switch: the label names it for TalkBack (coherence audit, phase 9).
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(state.lineEndings, role = Role.Switch, onValueChange = viewModel::lineEndings),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.diff_line_endings), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = state.lineEndings, onCheckedChange = null)
                    }
                    Status(state, viewModel::stop, viewModel::compare)
                }
                HorizontalDivider()
            }
            state.comparison?.takeIf { !state.computing }?.let { comparison ->
                items(comparison.rows) { DiffLine(it) }
                if (comparison.truncated) {
                    item { Note(pluralStringResource(R.plurals.diff_truncated, FileCaps.DIFF_ROWS, FileCaps.DIFF_ROWS)) }
                }
            }
        }
    }
}

/** A file's label, its name and size once chosen, and the button that chooses it. */
@Composable
private fun ChosenFile(label: String, file: FileEntry?, onChoose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (file != null) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onChoose) {
            Text(stringResource(if (file == null) R.string.action_choose_file else R.string.action_change))
        }
    }
}

/** Comparing, stopped, failed, identical, or the counts. */
@Composable
private fun Status(state: DiffState, onStop: () -> Unit, onCompare: () -> Unit) {
    val failure = state.failure
    val comparison = state.comparison
    when {
        state.computing -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diff_computing), Modifier.weight(1f))
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            }
        }
        state.stopped -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.diff_stopped), Modifier.weight(1f))
            OutlinedButton(onClick = onCompare) { Text(stringResource(R.string.diff_compare_again)) }
        }
        failure != null -> Text(failure.resolve(), color = MaterialTheme.colorScheme.error)
        state.identical -> Text(stringResource(R.string.diff_identical), style = MaterialTheme.typography.titleSmall)
        comparison != null -> Counts(comparison)
    }
}

@Composable
private fun Counts(comparison: LineComparison) {
    if (comparison.sameLines) {
        Text(stringResource(R.string.diff_same_lines), style = MaterialTheme.typography.titleSmall)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(pluralStringResource(R.plurals.diff_removed, comparison.removed, comparison.removed), color = DiffColors.Removed)
        Text(pluralStringResource(R.plurals.diff_added, comparison.added, comparison.added), color = DiffColors.Added)
    }
    if (comparison.approximate) Note(stringResource(R.string.diff_approximate))
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One row: the sign, the line's numbers in each file, its text — or the unchanged lines folded. */
@Composable
private fun DiffLine(row: DiffRow) {
    if (row.kind == DiffKind.SKIPPED) {
        Text(
            pluralStringResource(R.plurals.diff_skipped, row.skipped, row.skipped),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp),
        )
        return
    }
    val (sign, color) = when (row.kind) {
        DiffKind.REMOVED -> stringResource(R.string.diff_sign_removed) to DiffColors.Removed
        DiffKind.ADDED -> stringResource(R.string.diff_sign_added) to DiffColors.Added
        else -> " " to MaterialTheme.colorScheme.onSurface
    }
    val tint = if (row.kind == DiffKind.SAME) Color.Transparent else color.copy(alpha = ROW_TINT)
    Row(Modifier.fillMaxWidth().background(tint).padding(horizontal = 8.dp, vertical = 2.dp)) {
        LineNumber(row.lineA)
        LineNumber(row.lineB)
        Text(sign, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
        Text(row.text, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LineNumber(line: Int) {
    Text(
        if (line > 0) line.toString() else "",
        modifier = Modifier.width(LINE_NUMBER_WIDTH.dp),
        textAlign = TextAlign.End,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
