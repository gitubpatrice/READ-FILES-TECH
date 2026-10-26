package com.readfilestech.read_files_tech.ui.vault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.text.withoutBidiControls
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.SecureWindow
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.theme.Damier
import com.readfilestech.read_files_tech.ui.theme.dangerContent
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

/**
 * The vault's screen ([VaultViewModel]): create, unlock, its files. Secured for its whole life — the password
 * typed included (FLAG_SECURE: no screenshot, a blank thumbnail among the recent apps) — and out of autofill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureWindow()
    NoAutofill()
    LaunchedEffect(picked.files, picked.folder) {
        val files = picked.files
        val folder = picked.folder
        if (files != null || folder != null) {
            picked.consume()
            viewModel.picked(files, folder)
        }
    }
    LaunchedEffect(state.folderToImport) {
        state.folderToImport?.let {
            viewModel.folderImportOpened()
            onNavigate(Routes.vaultImport(it))
        }
    }
    ShareCopy(state.share, viewModel::shared)
    // Back from the folder's import, a viewer or the picker: the list as it is now.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val pickTitles = PickTitles(
        import = stringResource(R.string.vault_import_pick_title),
        folder = stringResource(R.string.vault_import_folder_pick_title),
        export = stringResource(R.string.vault_export_pick_title),
        restore = stringResource(R.string.vault_restore_pick_title),
    )
    val open = state.phase == VaultPhase.OPEN
    val title = if (state.phase == VaultPhase.SETUP) R.string.vault_setup_title else R.string.feature_vault
    // Leaving locks the vault: an import would stop there without saying where.
    val busy = state.work != null
    BackHandler(enabled = busy) { }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { if (open) VaultMenu(viewModel, onNavigate, pickTitles, busy) },
            )
        },
        floatingActionButton = {
            // While a work runs, no other one is offered: it would be ignored (one at a time).
            if (open && !busy) {
                // On the checkerboard's blue, as the home's "Open a file".
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.pickFor(VaultPick.IMPORT)
                        onNavigate(Routes.picker(PickMode.FILES, title = pickTitles.import))
                    },
                    containerColor = Damier.Blue,
                    contentColor = Damier.OnBlue,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.vault_import))
                }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        when (state.phase) {
            VaultPhase.CHECKING -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            VaultPhase.SETUP -> SetupForm(state, viewModel::setup, Modifier.padding(padding))
            VaultPhase.LOCKED -> UnlockForm(state, viewModel::unlock, viewModel::askReset, Modifier.padding(padding))
            VaultPhase.BROKEN -> BrokenVault(viewModel::askReset, Modifier.padding(padding))
            VaultPhase.OPEN -> VaultFiles(state, viewModel, onNavigate, pickTitles, padding)
        }
    }
    VaultWorkDialog(state.work)
    VaultDialogs(state.dialog, viewModel)
}

/** The picker's titles, read once in the language of the screen. */
internal class PickTitles(val import: String, val folder: String, val export: String, val restore: String)

@Composable
private fun VaultMenu(viewModel: VaultViewModel, onNavigate: (String) -> Unit, titles: PickTitles, busy: Boolean) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = { menu = true }, enabled = !busy) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { MenuText(R.string.vault_import_folder, R.string.vault_import_folder_hint) },
            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
            onClick = {
                menu = false
                viewModel.pickFor(VaultPick.FOLDER)
                onNavigate(Routes.picker(PickMode.FOLDER, title = titles.folder))
            },
        )
        DropdownMenuItem(
            text = { MenuText(R.string.vault_backup, R.string.vault_backup_hint) },
            leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
            onClick = {
                menu = false
                viewModel.askBackup()
            },
        )
        DropdownMenuItem(
            text = { MenuText(R.string.vault_restore, R.string.vault_restore_hint) },
            leadingIcon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
            onClick = {
                menu = false
                viewModel.pickFor(VaultPick.RESTORE)
                onNavigate(Routes.picker(PickMode.FILE, title = titles.restore))
            },
        )
    }
    IconButton(onClick = viewModel::lock) {
        Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.vault_lock))
    }
}

@Composable
private fun MenuText(title: Int, hint: Int) {
    Column {
        Text(stringResource(title))
        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VaultFiles(
    state: VaultState,
    viewModel: VaultViewModel,
    onNavigate: (String) -> Unit,
    titles: PickTitles,
    padding: PaddingValues,
) {
    Column(Modifier.padding(padding).fillMaxSize()) {
        when (val work = state.work) {
            is VaultWork.Importing -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { work.done.toFloat() / work.total }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.vault_importing, work.done, work.total), style = MaterialTheme.typography.bodySmall)
            }
            VaultWork.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }
        if (state.files.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(stringResource(R.string.vault_empty), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.vault_empty_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            return@Column
        }
        // Room under the last file for the import button.
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(state.files, key = VaultItem::encryptedName) { item ->
                VaultRow(item, viewModel, onNavigate, titles, busy = state.work != null)
            }
        }
    }
}

@Composable
private fun VaultRow(item: VaultItem, viewModel: VaultViewModel, onNavigate: (String) -> Unit, titles: PickTitles, busy: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val open = {
        val viewer = item.viewer
        if (viewer != null) onNavigate(Routes.vaultViewer(viewer, item.encryptedName)) else viewModel.notViewable()
    }
    ListItem(
        modifier = Modifier.clickable(onClick = open),
        headlineContent = { Text(item.name.withoutBidiControls(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(formatBytes(item.size)) },
        leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box {
                // Share, export, delete: one work at a time, the others would be ignored.
                IconButton(onClick = { menu = true }, enabled = !busy) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (item.viewer != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.vault_open)) }, onClick = {
                            menu = false
                            open()
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.vault_share)) }, onClick = {
                        menu = false
                        viewModel.share(item)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.vault_export)) }, onClick = {
                        menu = false
                        viewModel.pickFor(VaultPick.EXPORT, item)
                        onNavigate(Routes.picker(PickMode.FOLDER, title = titles.export))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = dangerContent) }, onClick = {
                        menu = false
                        viewModel.askDelete(item)
                    })
                }
            }
        },
    )
}

/** The share sheet for a copy decrypted for it, once (docs D-034). */
@Composable
private fun ShareCopy(path: String?, onShared: () -> Unit, viewModel: VaultLaunchViewModel = hiltViewModel()) {
    val context = LocalContext.current
    LaunchedEffect(path) {
        path?.let {
            onShared()
            viewModel.launching.shareVaultCopy(context, File(it))
        }
    }
}

/** The way out to other apps, for the share above: the vault's own view model stays free of Android. */
@HiltViewModel
class VaultLaunchViewModel @Inject constructor(val launching: EntryLaunching) : ViewModel()
