package com.readfilestech.read_files_tech.ui.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.SortMode
import com.readfilestech.read_files_tech.data.storage.VolumeRoot
import com.readfilestech.read_files_tech.ui.theme.DangerRed
import java.io.File

/** The bar while browsing: title and folder, then refresh, hidden files, trash, sort, volumes, search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseTopBar(
    state: ExplorerUiState,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onOpenTrash: () -> Unit,
    onSort: (SortMode) -> Unit,
    onVolume: (File) -> Unit,
    onSearchEverywhere: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        title = { BarTitle(state.title ?: stringResource(R.string.explorer_title), state.dir?.name?.ifEmpty { "/" } ?: "/") },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
            IconButton(onClick = onToggleHidden) {
                if (state.showHidden) {
                    Icon(Icons.Outlined.VisibilityOff, contentDescription = stringResource(R.string.action_hide_hidden))
                } else {
                    Icon(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.action_show_hidden))
                }
            }
            IconButton(onClick = onOpenTrash) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.trash_title))
            }
            SortButton(onSort)
            if (state.volumes.size > 1) VolumeButton(state.volumes, onVolume)
            IconButton(onClick = onSearchEverywhere) {
                Icon(Icons.AutoMirrored.Outlined.ManageSearch, contentDescription = stringResource(R.string.search_title))
            }
        },
    )
}

/** The bar while selecting: count, then select all, share, copy, move, and delete behind a menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        title = {
            Text(pluralStringResource(R.plurals.selected_count, count, count), style = MaterialTheme.typography.titleMedium)
        },
        actions = {
            IconButton(
                onClick = onSelectAll
            ) { Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.action_select_all)) }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share)) }
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy_to))
            }
            IconButton(onClick = onMove) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.action_move_to))
            }
            DeleteButton(onTrash, onDeleteForever)
        },
    )
}

/** The bar while choosing a destination folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickTopBar(state: ExplorerUiState, onCancel: () -> Unit, onVolume: (File) -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = onCancel
            ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
        },
        title = { BarTitle(state.title ?: stringResource(pickTitleOf(state.pick)), state.dir?.path.orEmpty()) },
        // Another volume: a folder of the SD card cannot be reached otherwise.
        actions = { if (state.volumes.size > 1) VolumeButton(state.volumes, onVolume) },
    )
}

private fun pickTitleOf(pick: PickMode?): Int = when (pick) {
    PickMode.FILE -> R.string.pick_file_title
    PickMode.FILES -> R.string.pick_files_title
    PickMode.FOLDER, null -> R.string.pick_folder_title
}

/**
 * The confirmation of a choice: this folder, or the files chosen so far. Choosing ONE file needs none —
 * the tap is the choice.
 */
@Composable
fun PickBottomBar(state: ExplorerUiState, onFolderChosen: (String) -> Unit, onFilesChosen: (List<String>) -> Unit) {
    val modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
    when (state.pick) {
        PickMode.FOLDER -> Button(
            onClick = { state.dir?.let { onFolderChosen(it.path) } },
            enabled = state.dir != null && state.problem == null,
            modifier = modifier,
        ) { Text(stringResource(R.string.action_choose_this_folder)) }
        PickMode.FILES -> Button(
            onClick = { onFilesChosen(state.picked) },
            enabled = state.picked.isNotEmpty(),
            modifier = modifier,
        ) { Text(pluralStringResource(R.plurals.action_confirm_files, state.picked.size, state.picked.size)) }
        PickMode.FILE, null -> Unit
    }
}

@Composable
private fun BarTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SortButton(onSort: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true }
        ) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.action_sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                SortMode.NAME to R.string.sort_name,
                SortMode.DATE to R.string.sort_date,
                SortMode.SIZE to R.string.sort_size
            ).forEach { (mode, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    open = false
                    onSort(mode)
                })
            }
        }
    }
}

@Composable
private fun VolumeButton(volumes: List<VolumeRoot>, onVolume: (File) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true }
        ) { Icon(Icons.Outlined.SdStorage, contentDescription = stringResource(R.string.action_volumes)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            volumes.forEach { volume ->
                DropdownMenuItem(text = { Text(volume.label) }, onClick = {
                    open = false
                    onVolume(volume.dir)
                })
            }
        }
    }
}

/** A menu rather than a button: a permanent deletion is never one tap away from the bar (2.15.x). */
@Composable
private fun DeleteButton(onTrash: () -> Unit, onDeleteForever: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true }
        ) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = DangerRed) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_trash)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    onTrash()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete_forever), color = DangerRed) },
                leadingIcon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = DangerRed) },
                onClick = {
                    open = false
                    onDeleteForever()
                },
            )
        }
    }
}

/** The path, one tappable segment per folder, scrolled to its end. */
@Composable
fun Breadcrumb(dir: File?, onOpen: (File) -> Unit) {
    val parts = dir?.path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
    if (parts.size <= 1) return
    val listState = rememberLazyListState()
    LaunchedEffect(parts.size) { listState.scrollToItem(parts.lastIndex) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(parts) { i, part ->
            if (i > 0) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            val last = i == parts.lastIndex
            Text(
                part,
                style = MaterialTheme.typography.labelMedium,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = !last) { onOpen(File("/" + parts.subList(0, i + 1).joinToString("/"))) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

/** The name filter of the folder (not the global search). */
@Composable
fun FolderSearchField(folderKey: String?, onQuery: (String) -> Unit) {
    // A new folder starts unfiltered (the browser clears the query too): typed in one folder, kept in
    // the next, the filter showed "Empty folder" there — the 2.15.x did the same.
    var text by rememberSaveable(folderKey) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onQuery(it)
        },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.explorer_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = {
                    text = ""
                    onQuery("")
                    focus.clearFocus()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                }
            }
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Shown while access is missing: what is listed may be incomplete. Text by Android version (2.15.x). */
@Composable
fun AccessBanner(allFilesModel: Boolean, onSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(if (allFilesModel) R.string.access_banner_all_files else R.string.access_banner_storage),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSettings) { Text(stringResource(R.string.action_settings)) }
        }
    }
}

/** An empty or unreadable folder, or one the missing access hides. */
@Composable
fun FolderEmptyState(state: ExplorerUiState, onSettings: () -> Unit, onVolume: (File) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.problem) {
            FolderProblem.ACCESS_DENIED -> {
                Icon(
                    Icons.Outlined.FolderOff,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(stringResource(R.string.access_denied_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.access_denied_body), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_open_settings))
                }
            }
            FolderProblem.UNREADABLE -> {
                Icon(
                    Icons.Outlined.FolderOff,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(stringResource(R.string.folder_unreadable), style = MaterialTheme.typography.titleSmall)
                // `/storage` and `/storage/emulated` can be crossed, not listed: reached by climbing the
                // breadcrumb, they offer the volumes instead of a dead end (the 2.15.x showed it empty).
                if (state.dir?.let(::isVolumeParent) == true) {
                    Text(
                        stringResource(R.string.folder_pick_volume),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    state.volumes.forEach { volume ->
                        OutlinedButton(onClick = { onVolume(volume.dir) }) {
                            Icon(Icons.Outlined.SdStorage, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(volume.label)
                        }
                    }
                }
            }
            null -> {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                // What hides the entries, when the folder has some: the filter, or their being hidden.
                val text = when {
                    state.entries.isEmpty() -> stringResource(R.string.folder_empty)
                    state.query.isNotBlank() -> stringResource(R.string.folder_no_match, state.query)
                    state.filteredOut -> stringResource(
                        R.string.folder_no_compatible,
                        state.extensions.orEmpty().sorted().joinToString(", ") { ".$it" },
                    )
                    else -> stringResource(R.string.folder_only_hidden)
                }
                Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            }
        }
    }
}
