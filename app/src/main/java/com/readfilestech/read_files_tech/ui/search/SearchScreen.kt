package com.readfilestech.read_files_tech.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.search.SearchHit
import com.readfilestech.read_files_tech.data.storage.StorageModel
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.LocalStorageSettings
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.explorer.AccessBanner
import java.io.File

/**
 * @param pickedRoot the folder the chooser handed back, until [onPickedRootUsed].
 * @param onChooseRoot opens the app's folder picker, as the content search and the duplicates do.
 * @param onOpenFolder shows a result's folder in the explorer — more useful than opening the file (2.15.x).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    pickedRoot: String?,
    onPickedRootUsed: () -> Unit,
    onBack: () -> Unit,
    onChooseRoot: () -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(pickedRoot) {
        if (pickedRoot != null) {
            viewModel.setRoot(pickedRoot)
            onPickedRootUsed()
        }
    }
    // Access granted in Android's settings meanwhile: the banner goes.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }
    val storage = LocalStorageSettings.current
    var name by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val start = { viewModel.start(name, content) }
    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.search_title)) },
                actions = {
                    if (state.results.isNotEmpty()) {
                        Text(
                            pluralStringResource(R.plurals.search_results, state.results.size, state.results.size),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CriterionField(name, { name = it }, R.string.search_name_label, R.string.search_name_hint, Icons.Outlined.Title, start)
                CriterionField(content, {
                    content = it
                }, R.string.search_content_label, R.string.search_content_hint, Icons.Outlined.FindInPage, start)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onChooseRoot, enabled = !state.searching) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.search_choose_root))
                    }
                    Text(
                        state.root.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // "Stop" is not red: stopping destroys nothing, and red is kept for what does (2.15.x, C-C8).
                    if (state.searching) {
                        FilledTonalButton(onClick = viewModel::stop) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(R.string.action_stop))
                        }
                    } else {
                        Button(onClick = start) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(R.string.action_search))
                        }
                    }
                }
            }
            // Without all-files access a search finds part of the files only: said, with the way to allow it.
            if (state.accessMissing) AccessBanner(storage.model == StorageModel.ALL_FILES, storage.request)
            state.finished?.let {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SearchNotes(it.full, it.nameOnly, R.plurals.search_name_only, it.unreadable, it.unreadableFolders)
                }
            }
            if (state.stopped && state.results.isNotEmpty()) {
                Text(
                    stringResource(R.string.search_stopped),
                    Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.searching) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(
                            R.string.search_progress,
                            pluralStringResource(R.plurals.search_scanned, state.scanned, state.scanned),
                            pluralStringResource(R.plurals.search_found, state.results.size, state.results.size)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            when {
                                state.searching -> R.string.search_running
                                state.stopped -> R.string.search_stopped
                                state.finished != null -> R.string.search_no_result
                                else -> R.string.search_enter
                            },
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.results, key = SearchHit::path) { hit -> HitRow(hit, onOpenFolder) }
                }
            }
        }
    }
}

@Composable
private fun CriterionField(
    value: String,
    onValue: (String) -> Unit,
    label: Int,
    hint: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onDone: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(hint)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HitRow(hit: SearchHit, onOpenFolder: (String) -> Unit) {
    val file = File(hit.path)
    val parent = file.parent.orEmpty()
    ListItem(
        // The screen's own background, as the explorer's rows: no lighter band behind the list.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onOpenFolder(parent) },
        leadingContent = {
            Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        headlineContent = {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
            Column {
                Text(
                    parent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                hit.snippet?.let {
                    Text(
                        it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        trailingContent = {
            Text(formatBytes(hit.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
