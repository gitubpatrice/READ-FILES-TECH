package com.readfilestech.read_files_tech.ui.vault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.common.ConfirmDangerDialog
import com.readfilestech.read_files_tech.ui.common.SecureWindow
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.theme.CautionOrange
import java.io.File

/** [VaultImportFolderViewModel]'s screen: the folder's files, chosen, then encrypted into the vault. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultImportFolderScreen(onDone: () -> Unit, viewModel: VaultImportFolderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureWindow()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val running = state.progress != null
    // Back while it runs asks it to stop, as the bar's arrow does: the screen goes once the file under way is
    // written, and says where it stopped — the back key was swallowed without a word.
    BackHandler(enabled = running) { viewModel.stop() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(File(state.folder).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (running) viewModel.stop() else onDone() }, enabled = !state.stopping) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (!state.scanning && !running && state.entries.isNotEmpty()) {
                        IconButton(onClick = { viewModel.selectAll(true) }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.vault_folder_select_all))
                        }
                        IconButton(onClick = { viewModel.selectAll(false) }) {
                            Icon(Icons.Outlined.Deselect, contentDescription = stringResource(R.string.vault_folder_select_none))
                        }
                    }
                },
            )
        },
        bottomBar = { EncryptBar(state, viewModel::encrypt, viewModel::stop) },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Options(state, running, viewModel)
            if (state.capReached) CapBanner()
            when {
                state.scanning -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.unreadable -> Empty(stringResource(R.string.vault_folder_unreadable))
                state.entries.isEmpty() -> Empty(
                    stringResource(if (state.recursive) R.string.vault_folder_empty_recursive else R.string.vault_folder_empty_flat),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = FolderEntry::path) { entry ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = !running) { viewModel.toggle(entry.path) },
                            headlineContent = { Text(entry.relative, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(formatBytes(entry.size)) },
                            leadingContent = {
                                Checkbox(checked = entry.selected, onCheckedChange = { viewModel.toggle(entry.path) }, enabled = !running)
                            },
                        )
                    }
                }
            }
        }
    }
    state.confirmDelete?.let { count ->
        ConfirmDangerDialog(
            title = stringResource(R.string.vault_folder_delete_title),
            message = pluralStringResource(R.plurals.vault_folder_delete_message, count, count),
            confirmLabel = stringResource(R.string.vault_folder_delete_confirm),
            onConfirm = { viewModel.confirmDelete(true) },
            onDismiss = { viewModel.confirmDelete(false) },
        )
    }
}

@Composable
private fun Options(state: FolderImportState, running: Boolean, viewModel: VaultImportFolderViewModel) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        ListItem(
            modifier = Modifier.clickable(enabled = !running) { viewModel.setRecursive(!state.recursive) },
            headlineContent = { Text(stringResource(R.string.vault_folder_include_sub)) },
            trailingContent = { Switch(checked = state.recursive, onCheckedChange = viewModel::setRecursive, enabled = !running) },
        )
        HorizontalDivider()
        ListItem(
            modifier = Modifier.clickable(enabled = !running) { viewModel.setDeleteOriginals(!state.deleteOriginals) },
            headlineContent = { Text(stringResource(R.string.vault_folder_delete_originals)) },
            supportingContent = { Text(stringResource(R.string.vault_folder_delete_originals_hint)) },
            trailingContent = {
                Switch(checked = state.deleteOriginals, onCheckedChange = viewModel::setDeleteOriginals, enabled = !running)
            },
        )
    }
}

@Composable
private fun CapBanner() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .background(CautionOrange.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, CautionOrange.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = CautionOrange, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.vault_folder_cap, FolderScan.MAX_ENTRIES), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Empty(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.FolderOff, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Text(stringResource(R.string.vault_folder_empty), style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EncryptBar(state: FolderImportState, onEncrypt: () -> Unit, onStop: () -> Unit) {
    val selected = state.selected
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        if (state.stopping) R.string.vault_folder_stopping else R.string.vault_folder_progress,
                        (progress * PERCENT).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                // Not red: stopping destroys nothing, what is encrypted stays (2.15.x, C-C8).
                FilledTonalButton(onClick = onStop, enabled = !state.stopping, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text(stringResource(R.string.action_stop), Modifier.padding(start = 8.dp))
                }
            }
            val enabled = state.progress == null && !state.scanning && selected.isNotEmpty()
            val total = formatBytes(selected.sumOf(FolderEntry::size))
            Button(onClick = onEncrypt, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Text(
                    if (selected.isEmpty()) {
                        stringResource(R.string.vault_folder_none_selected)
                    } else {
                        pluralStringResource(R.plurals.vault_folder_encrypt, selected.size, selected.size, total)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private const val PERCENT = 100
