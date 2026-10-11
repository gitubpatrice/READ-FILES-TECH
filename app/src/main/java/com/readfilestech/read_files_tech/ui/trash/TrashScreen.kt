package com.readfilestech.read_files_tech.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.DartDateTime
import com.readfilestech.read_files_tech.data.trash.TrashEntry
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.common.ConfirmDangerDialog
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.theme.DangerRed
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the trash screen asks to confirm. */
private sealed interface TrashDialog {
    data class DeleteOne(val entry: TrashEntry) : TrashDialog

    data object EmptyAll : TrashDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<TrashDialog?>(null) }
    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.trash_title), style = MaterialTheme.typography.titleMedium)
                        val count = pluralStringResource(R.plurals.item_count, state.entries.size, state.entries.size)
                        val summary = stringResource(R.string.trash_summary, count, formatBytes(state.totalBytes))
                        Text(
                            if (state.loading) "…" else summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::load
                    ) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
                    IconButton(onClick = { dialog = TrashDialog.EmptyAll }, enabled = state.entries.isNotEmpty()) {
                        Icon(
                            Icons.Filled.DeleteForever,
                            contentDescription = stringResource(R.string.trash_empty_action),
                            tint = if (state.entries.isNotEmpty()) DangerRed else MaterialTheme.colorScheme.outline
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.entries.isNotEmpty(),
            onRefresh = viewModel::load,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            when {
                state.loading && state.entries.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                state.entries.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) { item { EmptyTrash() } }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.id + it.trashRoot.path }) { entry ->
                        TrashRow(entry, onRestore = { viewModel.restore(entry) }, onDelete = { dialog = TrashDialog.DeleteOne(entry) })
                    }
                }
            }
        }
    }
    when (val shown = dialog) {
        null -> Unit
        is TrashDialog.DeleteOne -> ConfirmDangerDialog(
            title = stringResource(R.string.action_delete_forever),
            message = stringResource(R.string.trash_delete_one, shown.entry.name),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = {
                viewModel.deleteForever(shown.entry)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        TrashDialog.EmptyAll -> ConfirmDangerDialog(
            title = stringResource(R.string.trash_empty_action),
            message = pluralStringResource(R.plurals.trash_empty_confirm, state.entries.size, state.entries.size),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = {
                viewModel.emptyAll()
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun TrashRow(entry: TrashEntry, onRestore: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale) }
    val size = if (entry.size >= 0) formatBytes(entry.size) + " · " else ""
    ListItem(
        // The screen's own background, as the explorer's rows: no lighter band behind the list.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                if (entry.isDir) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        },
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
            Text(
                size + formatter.format(entry.deletedAt.shownLocally()) + "\n" + entry.originalPath,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menu = true }
                ) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_restore)) },
                        leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null) },
                        onClick = {
                            menu = false
                            onRestore()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete_forever), color = DangerRed) },
                        leadingIcon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = DangerRed) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

/** The deletion time in the phone's zone: the 2.15.x wrote it local, but a UTC one may come from elsewhere. */
private fun DartDateTime.shownLocally(): LocalDateTime =
    if (isUtc) value.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() else value

@Composable
private fun EmptyTrash() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Text(stringResource(R.string.trash_empty_title), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.trash_empty_body),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
