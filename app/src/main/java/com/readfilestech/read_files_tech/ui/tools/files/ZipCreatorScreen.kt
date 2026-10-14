package com.readfilestech.read_files_tech.ui.tools.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.outputs.AutoShare
import com.readfilestech.read_files_tech.ui.outputs.OutputSavedCard
import com.readfilestech.read_files_tech.ui.tools.ToolIntro

private const val PERCENT = 100

/**
 * `ZipCreatorScreen` of the 2.15.3: files chosen in the app's picker, listed with their sizes, made into one
 * archive saved in `Conversions/` and shown as the other tools show their output — shared at once when the
 * setting says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZipCreatorScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ZipCreatorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(picked.files) {
        picked.files?.let {
            picked.consume()
            viewModel.add(it)
        }
    }
    AutoShare(state.share, viewModel.launching, viewModel::shared)
    val title = stringResource(R.string.feature_zip_creator)
    val pickTitle = stringResource(R.string.zip_pick_title)
    val add = { onNavigate(Routes.picker(PickMode.FILES, title = pickTitle)) }
    val working = state.progress != null
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
        bottomBar = { if (state.files.isNotEmpty()) CreateBar(state, viewModel::create, viewModel::stop) },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val result = state.result
        if (state.files.isEmpty() && result == null) {
            ToolIntro(Icons.Outlined.FolderZip, title, stringResource(R.string.zip_intro), add, Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (result != null && state.files.isEmpty()) {
                OutputSavedCard(
                    title = stringResource(R.string.zip_saved_title),
                    body = pluralStringResource(R.plurals.zip_saved_body, state.resultCount, state.resultCount),
                    path = result,
                    onNavigate = onNavigate,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.zip_files_count, state.files.size, state.files.size, formatBytes(state.totalBytes)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = add, enabled = !working) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.zip_add_files))
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.files, key = FileEntry::path) { file -> FileRow(file, enabled = !working) { viewModel.remove(file.path) } }
            }
        }
    }
}

@Composable
private fun FileRow(file: FileEntry, enabled: Boolean, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(formatBytes(file.size)) },
        leadingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.zip_remove))
            }
        },
    )
}

@Composable
private fun CreateBar(state: ZipState, onCreate: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val progress = state.progress
        if (progress == null) {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text(pluralStringResource(R.plurals.zip_create, state.files.size, state.files.size))
            }
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.zip_working, (progress * PERCENT).toInt()), modifier = Modifier.weight(1f))
                TextButton(onClick = onStop) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}
