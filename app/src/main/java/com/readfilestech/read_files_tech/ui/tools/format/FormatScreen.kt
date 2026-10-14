package com.readfilestech.read_files_tech.ui.tools.format

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.tools.FormatAction
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.outputs.OutputActionsRow
import com.readfilestech.read_files_tech.ui.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val INPUT_MIN_LINES = 6
private const val INPUT_MAX_LINES = 10

/**
 * `FormatScreen` of the 2.15.3: JSON indented or minified, CSS or JS minified, from a text pasted or a
 * file opened; the result copied, or saved in `Conversions/` and opened, shared or sent from the row under it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FormatScreen(picked: PickedResults, onNavigate: (String) -> Unit, onBack: () -> Unit, viewModel: FormatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val title = stringResource(R.string.feature_format)
    LaunchedEffect(picked.files) {
        picked.files?.firstOrNull()?.let {
            picked.consume()
            viewModel.choose(it)
        }
    }
    LaunchedEffect(state.share) {
        state.share?.let { path ->
            val entry = withContext(Dispatchers.IO) { DirectoryListing.entryOf(File(path)) }
            viewModel.launching.share(context, listOf(entry))
            viewModel.shared()
        }
    }
    val open = { onNavigate(Routes.picker(PickMode.FILE, extensions = setOf(state.action.extension), title = title)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Actions(state.action, viewModel::action)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.format_input), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = open) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Text(stringResource(R.string.format_open_file), Modifier.padding(start = 6.dp))
                }
            }
            val file = state.file
            if (file != null) ChosenFile(file, viewModel::clear) else Input(state.input, viewModel::input, viewModel::clear)
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            val failure = state.failure
            when {
                failure != null -> Text(failure.resolve(), color = MaterialTheme.colorScheme.error)
                !state.working && state.length > 0 -> {
                    ToolResult(state.preview, state.length, viewModel::copyText, viewModel::copied)
                    Button(onClick = viewModel::save, enabled = !state.saving) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Text(stringResource(R.string.action_save), Modifier.padding(start = 6.dp))
                    }
                }
            }
            state.saved?.let { path ->
                Text(stringResource(R.string.format_saved_body), style = MaterialTheme.typography.bodySmall)
                Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutputActionsRow(path, onNavigate)
            }
        }
    }
}

/** JSON, CSS or JS, and for JSON indented or minified (CSS and JS are minified only, as in the 2.15.3). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(action: FormatAction, onAction: (FormatAction) -> Unit) {
    val json = action == FormatAction.JSON_INDENTED || action == FormatAction.JSON_MINIFIED
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = json,
            onClick = { if (!json) onAction(FormatAction.JSON_INDENTED) },
            label = { Text(stringResource(R.string.format_json)) },
        )
        FilterChip(
            selected = action == FormatAction.CSS_MINIFIED,
            onClick = { onAction(FormatAction.CSS_MINIFIED) },
            label = { Text(stringResource(R.string.format_css)) },
        )
        FilterChip(
            selected = action == FormatAction.JS_MINIFIED,
            onClick = { onAction(FormatAction.JS_MINIFIED) },
            label = { Text(stringResource(R.string.format_js)) },
        )
    }
    if (json) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = action == FormatAction.JSON_INDENTED,
                onClick = { onAction(FormatAction.JSON_INDENTED) },
                label = { Text(stringResource(R.string.format_indent)) },
            )
            FilterChip(
                selected = action == FormatAction.JSON_MINIFIED,
                onClick = { onAction(FormatAction.JSON_MINIFIED) },
                label = { Text(stringResource(R.string.format_minify)) },
            )
        }
    }
}

@Composable
private fun Input(text: String, onChange: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.format_input_hint)) },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        minLines = INPUT_MIN_LINES,
        maxLines = INPUT_MAX_LINES,
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.action_clear)) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A file too long for the field: its name and size in the field's place. */
@Composable
private fun ChosenFile(file: FileEntry, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onClear) { Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.action_clear)) }
    }
}
