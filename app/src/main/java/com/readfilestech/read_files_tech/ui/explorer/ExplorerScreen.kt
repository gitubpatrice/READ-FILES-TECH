package com.readfilestech.read_files_tech.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.sharing.KnownApps
import com.readfilestech.read_files_tech.data.storage.StorageModel
import com.readfilestech.read_files_tech.data.viewer.image.GalleryOrder
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.LocalStorageSettings
import com.readfilestech.read_files_tech.ui.common.ConfirmDangerDialog
import com.readfilestech.read_files_tech.ui.common.NameDialog
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes

/** A dialog or sheet of the explorer; at most one at a time. */
private sealed interface ExplorerDialog {
    data object NewFolder : ExplorerDialog

    data class Rename(val entry: FileEntry) : ExplorerDialog

    data class DeleteForever(val entries: List<FileEntry>) : ExplorerDialog

    data class Info(val entry: FileEntry) : ExplorerDialog

    data class Preview(val entry: FileEntry) : ExplorerDialog
}

/**
 * The explorer screen. In folder-choosing mode ([ExplorerUiState.pickFolder]) it is the destination
 * chooser of "copy / move to" — the 2.15.x used the system's SAF picker, which hands back a path the
 * app then had to trust.
 *
 * @param pickedFolder the destination the chooser handed back (copy / move to), until [onPickedFolderUsed].
 * @param onChooseFolder opens this screen in folder-choosing mode, starting at the given folder.
 * @param onFolderChosen the folder chosen, in folder-choosing mode.
 * @param onFilesChosen the files chosen, in file-choosing mode ([PickMode.FILE], [PickMode.FILES]).
 * @param navigationBar the home's tabs, when this screen is the home's explorer tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    pickedFolder: String?,
    onPickedFolderUsed: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSearch: () -> Unit,
    onChooseFolder: (String) -> Unit,
    onFolderChosen: (String) -> Unit,
    onFilesChosen: (List<String>) -> Unit,
    onNavigate: (String) -> Unit,
    onLeave: () -> Unit,
    viewModel: ExplorerViewModel = hiltViewModel(),
    navigationBar: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // The name filter keeps the focus otherwise: the keyboard came back after a menu, and a tap meant
    // for "Undo" typed into the filter (seen on the emulator).
    val focus = LocalFocusManager.current
    val storageSettings = LocalStorageSettings.current
    var dialog by remember { mutableStateOf<ExplorerDialog?>(null) }
    val browser = viewModel.browser
    val launching = viewModel.launching

    LaunchedEffect(pickedFolder) {
        if (pickedFolder != null) {
            viewModel.onFolderPicked(pickedFolder)
            onPickedFolderUsed()
        }
    }
    LifecycleResumeEffect(Unit) {
        browser.onResume()
        onPauseOrDispose { }
    }
    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }
    BackHandler(enabled = !state.selecting && state.canGoBack) { browser.back() }

    fun open(entry: FileEntry) {
        // How this folder is shown: an image opens among the images the user sees here, in this order.
        val gallery = GalleryOrder(state.sort, state.showHidden, state.query, state.extensions)
        when (val target = FileRouter.targetFor(entry, gallery)) {
            is FileTarget.Internal -> onNavigate(target.route)
            FileTarget.External -> launching.view(context, entry, chooser = false)
        }
    }

    fun click(entry: FileEntry) {
        focus.clearFocus()
        when {
            state.selecting -> viewModel.toggleSelection(entry)
            entry.isDirectory -> browser.open(entry.file)
            state.pick == PickMode.FILE -> onFilesChosen(listOf(entry.path))
            state.pick == PickMode.FILES -> viewModel.togglePicked(entry)
            else -> open(entry)
        }
    }

    fun transfer(entries: List<FileEntry>, move: Boolean) {
        viewModel.startTransfer(entries, move)
        state.dir?.let { onChooseFolder(it.path) }
    }

    val onAction: (EntryAction, FileEntry) -> Unit = { action, entry ->
        focus.clearFocus()
        when (action) {
            EntryAction.OPEN -> open(entry)
            EntryAction.OPEN_WITH -> when (val target = FileRouter.chooserTargetFor(entry)) {
                is FileTarget.Internal -> onNavigate(target.route)
                FileTarget.External -> launching.view(context, entry, chooser = true)
            }
            EntryAction.PREVIEW -> dialog = ExplorerDialog.Preview(entry)
            EntryAction.EDIT -> onNavigate(Routes.feature(Feature.EDITOR, entry.path))
            EntryAction.EDIT_IN_PDF_TECH -> launching.editInPdfTech(context, entry)
            EntryAction.STRIP_METADATA -> onNavigate(Routes.feature(Feature.EXIF, entry.path))
            EntryAction.SHARE -> launching.share(context, listOf(entry))
            EntryAction.SEND_TO_KDRIVE -> launching.sendTo(context, entry, KnownApps.KDRIVE, EntryLaunching.KDRIVE_NAME)
            EntryAction.SEND_TO_PROTON -> launching.sendTo(context, entry, KnownApps.PROTON_DRIVE, EntryLaunching.PROTON_DRIVE_NAME)
            EntryAction.RENAME -> dialog = ExplorerDialog.Rename(entry)
            EntryAction.COPY_TO -> transfer(listOf(entry), move = false)
            EntryAction.MOVE_TO -> transfer(listOf(entry), move = true)
            EntryAction.INFO -> dialog = ExplorerDialog.Info(entry)
            EntryAction.TRASH -> viewModel.moveToTrash(listOf(entry))
            EntryAction.DELETE_FOREVER -> dialog = ExplorerDialog.DeleteForever(listOf(entry))
        }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            when {
                state.pick != null -> PickTopBar(state, onCancel = onLeave, onVolume = { browser.open(it) })
                state.selecting -> SelectionTopBar(
                    count = state.selection.size,
                    onClear = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onShare = { launching.share(context, state.selectedEntries) },
                    onCopy = { transfer(state.selectedEntries, move = false) },
                    onMove = { transfer(state.selectedEntries, move = true) },
                    onTrash = { viewModel.moveToTrash(state.selectedEntries) },
                    onDeleteForever = { dialog = ExplorerDialog.DeleteForever(state.selectedEntries) },
                )
                else -> BrowseTopBar(
                    state = state,
                    onBack = if (state.canGoBack) ({ browser.back() }) else null,
                    onRefresh = browser::refresh,
                    onToggleHidden = browser::toggleHidden,
                    onOpenTrash = onOpenTrash,
                    onSort = browser::setSort,
                    onVolume = { browser.open(it) },
                    onSearchEverywhere = onOpenSearch,
                )
            }
        },
        floatingActionButton = {
            // A new folder: to browse, or as a destination — not while choosing files.
            if (!state.selecting && (state.pick == null || state.pickFolder)) {
                FloatingActionButton(onClick = { dialog = ExplorerDialog.NewFolder }) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.action_new_folder))
                }
            }
        },
        // The home's tabs, when this is its third: ONE scaffold, so one bar, one button and one message
        // host — the 2.15.x stacked two of each (inventory F29).
        bottomBar = { if (state.pick == null) navigationBar() else PickBottomBar(state, onFolderChosen, onFilesChosen) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumb(state.dir) { browser.open(it) }
            // Keyed on the folder: the filter is the folder's, it does not follow into the next one.
            FolderSearchField(folderKey = state.dir?.path, onQuery = browser::setQuery)
            if (state.accessBanner) AccessBanner(storageSettings.model == StorageModel.ALL_FILES, storageSettings.request)
            Text(
                pluralStringResource(R.plurals.item_count, state.visible.size, state.visible.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            PullToRefreshBox(
                isRefreshing = state.loading && state.dir != null,
                onRefresh = browser::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    state.loading && state.dir == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.visible.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item { FolderEmptyState(state, storageSettings.request, onVolume = { browser.open(it) }) }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.visible, key = FileEntry::path) { entry ->
                            EntryRow(
                                entry = entry,
                                selected = entry.path in state.selection || entry.path in state.picked,
                                enabled = entry.isDirectory || !state.pickFolder,
                                thumbnails = viewModel.thumbnails,
                                onClick = { click(entry) },
                                // Choosing for another screen: no selection, no menu — nothing to rename,
                                // move or delete on the way (the 2.15.x left them all there).
                                onLongClick = if (state.pick != null) {
                                    null
                                } else {
                                    {
                                        focus.clearFocus()
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleSelection(entry)
                                    }
                                },
                                menu = if (state.selecting || state.pick != null) {
                                    null
                                } else {
                                    { dismiss ->
                                        if (entry.isDirectory) {
                                            FolderMenu(entry, onAction, dismiss)
                                        } else {
                                            FileMenu(entry, state.kDriveInstalled, state.protonInstalled, onAction, dismiss)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    when (val shown = dialog) {
        null -> Unit
        ExplorerDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.action_new_folder),
            confirmLabel = stringResource(R.string.action_create),
            initial = "",
            hint = stringResource(R.string.new_folder_hint),
            onConfirm = {
                viewModel.createFolder(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is ExplorerDialog.Rename -> NameDialog(
            title = stringResource(R.string.action_rename),
            confirmLabel = stringResource(R.string.action_rename),
            initial = shown.entry.name,
            hint = null,
            onConfirm = {
                viewModel.rename(shown.entry, it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is ExplorerDialog.DeleteForever -> ConfirmDangerDialog(
            title = stringResource(R.string.action_delete_forever),
            message = deleteMessage(shown.entries),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = {
                viewModel.deleteForever(shown.entries)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is ExplorerDialog.Info -> EntryInfoDialog(shown.entry) { dialog = null }
        is ExplorerDialog.Preview -> PreviewSheet(shown.entry, onOpen = {
            dialog = null
            open(shown.entry)
        }, onDismiss = { dialog = null })
    }
}

/** The 2.15.x wording: what goes, that a folder goes with its content, and that the trash is bypassed. */
@Composable
private fun deleteMessage(entries: List<FileEntry>): String {
    val single = entries.singleOrNull()
    return when {
        single == null -> pluralStringResource(R.plurals.delete_forever_many, entries.size, entries.size)
        single.isDirectory -> stringResource(R.string.delete_forever_folder, single.name)
        else -> stringResource(R.string.delete_forever_file, single.name)
    }
}
