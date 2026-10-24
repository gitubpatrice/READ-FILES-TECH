package com.readfilestech.read_files_tech.ui.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.storage.StorageModel
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.LocalStorageSettings
import com.readfilestech.read_files_tech.ui.explorer.AccessBanner
import com.readfilestech.read_files_tech.ui.explorer.EntryRow
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.explorer.PickedFiles
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import java.io.File

/**
 * The app's own picker (`RftPickerScreen` of the 2.15.3): "Recents" — the files opened and those the tools
 * made — and "Browse" — shortcuts, the other volumes, the folders of the primary one —, each folder opened
 * in the explorer choosing for this screen. Choosing a folder shows "Browse" only.
 *
 * Choosing several, ONE choice ([PickedFiles]): the files chosen here go with the explorer it opens, which
 * starts from them, and the explorer's "Confirm" ends the choice — `AppRoot` hands its files past this screen.
 *
 * @param pickedFiles what the explorer handed back, until [onResultUsed].
 * @param onBrowse opens an explorer route (choosing for this screen), with the files chosen so far for it to start from.
 * @param onFilesPicked the files chosen: this screen's result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    pickedFiles: List<String>?,
    pickedFolder: String?,
    onResultUsed: () -> Unit,
    onBrowse: (route: String, picked: List<String>) -> Unit,
    onNavigate: (String) -> Unit,
    onFilesPicked: (List<String>) -> Unit,
    onFolderPicked: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: PickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picked by viewModel.picked.paths.collectAsStateWithLifecycle()
    val mode = viewModel.mode
    val folderMode = mode == PickMode.FOLDER
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(pickedFiles, pickedFolder) {
        when {
            pickedFolder != null -> {
                onResultUsed()
                onFolderPicked(pickedFolder)
            }
            // Choosing several too: the explorer started from this screen's choice, what it hands back is the
            // whole of it — not more to add here and confirm a second time (seen on the S9).
            pickedFiles != null -> {
                onResultUsed()
                onFilesPicked(pickedFiles)
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.reload()
        onPauseOrDispose { }
    }

    val defaultTitle = stringResource(
        when (mode) {
            PickMode.FILE -> R.string.pick_file_title
            PickMode.FILES -> R.string.pick_files_title
            PickMode.FOLDER -> R.string.pick_folder_title
        },
    )
    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
                },
                title = { Text(viewModel.title ?: defaultTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
        bottomBar = {
            if (mode == PickMode.FILES) {
                Button(
                    onClick = { onFilesPicked(picked) },
                    enabled = picked.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text(pluralStringResource(R.plurals.action_confirm_files, picked.size, picked.size)) }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!folderMode) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.picker_tab_recents)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.picker_tab_browse)) })
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !folderMode && tab == 0 -> RecentsTab(state, picked, mode, viewModel, onNavigate, onFilesPicked)
                else -> {
                    fun browse(dir: File, label: String, filter: Set<String>?) = onBrowse(
                        Routes.explorer(dir.path, pick = mode, extensions = filter ?: viewModel.extensions, title = label),
                        viewModel.picked.paths.value,
                    )
                    BrowseTab(
                        state = state,
                        extensions = viewModel.extensions,
                        onShortcut = { shortcut, label ->
                            viewModel.prepare(
                                shortcut
                            ) { browse(shortcut.dir, label, shortcut.extensions) }
                        },
                        onFolder = { browse(it, it.name, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentsTab(
    state: PickerUiState,
    picked: List<String>,
    mode: PickMode,
    viewModel: PickerViewModel,
    onNavigate: (String) -> Unit,
    onFilesPicked: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    if (state.recents.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(stringResource(R.string.picker_empty_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.picker_empty_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        return
    }
    fun open(entry: FileEntry) {
        when (val target = FileRouter.targetFor(entry)) {
            is FileTarget.Internal -> onNavigate(target.route)
            FileTarget.External -> viewModel.launching.view(context, entry, chooser = false)
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.recents, key = FileEntry::path) { entry ->
            EntryRow(
                entry = entry,
                selected = entry.path in picked,
                enabled = true,
                thumbnails = viewModel.thumbnails,
                onClick = { if (mode == PickMode.FILES) viewModel.picked.toggle(entry.path) else onFilesPicked(listOf(entry.path)) },
                onLongClick = null,
                // Choosing several, a tap toggles: the menu would only get in the way.
                menu = if (mode == PickMode.FILES) {
                    null
                } else {
                    { dismiss ->
                        val canLeave = entry.path !in state.cannotLeave
                        // Another app opens it only if it may leave the app; the app's own viewer always.
                        if (canLeave || FileRouter.targetFor(entry) is FileTarget.Internal) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_open)) }, onClick = {
                                dismiss()
                                open(entry)
                            })
                        }
                        // "Modify" of the 2.15.3 (`rft_picker_screen.dart:669-676`) was offered for any file (F34).
                        FileRouter.editorFor(entry.path, entry.extension)?.let { editor ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = {
                                dismiss()
                                onNavigate(editor)
                            })
                        }
                        if (canLeave) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                                dismiss()
                                viewModel.launching.share(context, listOf(entry))
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.recent_remove)) }, onClick = {
                            dismiss()
                            viewModel.remove(entry)
                        })
                    }
                },
            )
        }
    }
}

@Composable
private fun BrowseTab(
    state: PickerUiState,
    extensions: Set<String>?,
    onShortcut: (PickerShortcut, label: String) -> Unit,
    onFolder: (File) -> Unit,
) {
    val storageSettings = LocalStorageSettings.current
    val labels = state.shortcuts.associateWith { shortcutLabel(it) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (!state.granted) item { AccessBanner(storageSettings.model == StorageModel.ALL_FILES, storageSettings.request) }
        if (extensions != null) item { FilterBanner(extensions) }
        item { SectionTitle(stringResource(R.string.picker_shortcuts)) }
        items(state.shortcuts.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { shortcut ->
                    val label = labels.getValue(shortcut)
                    ShortcutTile(shortcut, label, Modifier.weight(1f)) { onShortcut(shortcut, label) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.size(8.dp))
        }
        if (state.folders.isNotEmpty()) item { SectionTitle(stringResource(R.string.picker_all_folders)) }
        items(state.folders, key = File::getPath) { folder ->
            Row(
                Modifier.fillMaxWidth().clickable { onFolder(folder) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(16.dp))
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ShortcutTile(shortcut: PickerShortcut, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(shortcutIcon(shortcut.kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Active filter: .a, .b, …" — six at most, as the 2.15.x. */
@Composable
private fun FilterBanner(extensions: Set<String>) {
    val shown = extensions.sorted().take(MAX_FILTER_SHOWN).joinToString(", ") {
        ".$it"
    } + if (extensions.size > MAX_FILTER_SHOWN) "…" else ""
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.picker_filter, shown),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun shortcutLabel(shortcut: PickerShortcut): String = when (shortcut.kind) {
    ShortcutKind.DOWNLOADS -> stringResource(R.string.picker_downloads)
    ShortcutKind.DOCUMENTS -> stringResource(R.string.picker_documents)
    ShortcutKind.CAMERA -> stringResource(R.string.picker_camera)
    ShortcutKind.PICTURES -> stringResource(R.string.picker_pictures)
    ShortcutKind.MOVIES -> stringResource(R.string.picker_movies)
    ShortcutKind.MUSIC -> stringResource(R.string.picker_music)
    ShortcutKind.SCREENSHOTS -> stringResource(R.string.picker_screenshots)
    ShortcutKind.WHATSAPP -> "WhatsApp"
    ShortcutKind.FILES_TECH -> "Files Tech"
    ShortcutKind.APKS -> stringResource(R.string.picker_apks)
    ShortcutKind.VOLUME -> shortcut.label ?: shortcut.dir.name
}

private fun shortcutIcon(kind: ShortcutKind): ImageVector = when (kind) {
    ShortcutKind.DOWNLOADS -> Icons.Outlined.Download
    ShortcutKind.DOCUMENTS -> Icons.Outlined.Description
    ShortcutKind.CAMERA -> Icons.Outlined.PhotoCamera
    ShortcutKind.PICTURES -> Icons.Outlined.PhotoLibrary
    ShortcutKind.MOVIES -> Icons.Outlined.VideoLibrary
    ShortcutKind.MUSIC -> Icons.Outlined.MusicNote
    ShortcutKind.SCREENSHOTS -> Icons.Outlined.Screenshot
    ShortcutKind.WHATSAPP -> Icons.AutoMirrored.Outlined.Chat
    ShortcutKind.FILES_TECH -> Icons.Outlined.FolderSpecial
    ShortcutKind.APKS -> Icons.Outlined.Android
    ShortcutKind.VOLUME -> Icons.Outlined.SdStorage
}

private const val MAX_FILTER_SHOWN = 6
