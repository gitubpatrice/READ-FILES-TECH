package com.readfilestech.read_files_tech.ui.tools.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.tools.DuplicateFile
import com.readfilestech.read_files_tech.data.tools.DuplicateGroup
import com.readfilestech.read_files_tech.data.tools.DuplicateProgress
import com.readfilestech.read_files_tech.data.tools.DuplicateReport
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.BrandButton
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * `DuplicatesScreen` of the 2.15.3: a folder, "Scan", then two tabs — the groups of duplicates, the largest
 * files — over ONE selection, moved to the trash. A copy of each group always stays: its last unticked copy
 * cannot be ticked, in either tab (D7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(picked.folder) {
        picked.folder?.let {
            picked.consume()
            viewModel.setRoot(it)
        }
    }
    val chooseTitle = stringResource(R.string.duplicates_choose_folder)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_duplicates)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            if (state.ticked.isNotEmpty()) {
                BrandButton(
                    onClick = viewModel::trash,
                    enabled = !state.trashing,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(pluralStringResource(R.plurals.duplicates_trash, state.ticked.size, state.ticked.size))
                }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            RootRow(state, onChoose = {
                onNavigate(Routes.picker(PickMode.FOLDER, title = chooseTitle))
            }, viewModel::scan, viewModel::stop)
            state.progress?.let { Progress(it) }
            state.failure?.let {
                Text(
                    it.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            val report = state.report
            if (report == null) {
                if (!state.scanning && state.failure == null) Centered(stringResource(R.string.duplicates_intro))
                return@Column
            }
            Summary(report)
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.duplicates_tab_duplicates)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.duplicates_tab_largest)) })
            }
            val tick = { file: DuplicateFile -> viewModel.toggle(file.path) }
            val can = { file: DuplicateFile -> !state.trashing && state.selection.canToggle(file.path, state.ticked) }
            val smallest = formatBytes(FileCaps.DUPLICATE_MIN_BYTES)
            when {
                // Nothing compared says so, in either tab: "no duplicates" beside two identical small files misled.
                report.filesListed == 0 || tab == 1 && report.largest.isEmpty() ->
                    Centered(stringResource(R.string.duplicates_no_files, smallest))
                tab == 0 && report.groups.isEmpty() -> Centered(stringResource(R.string.duplicates_none, smallest))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (tab == 0) groups(report.groups, state.ticked, can, tick) else largest(report.largest, state.ticked, can, tick)
                }
            }
        }
    }
}

@Composable
private fun RootRow(state: DuplicatesState, onChoose: () -> Unit, onScan: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onChoose, enabled = !state.scanning) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.duplicates_choose_folder))
        }
        Text(
            state.root,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // "Stop" is not red: stopping destroys nothing (as the global search).
        if (state.scanning) {
            FilledTonalButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.action_stop))
            }
        } else {
            BrandButton(onClick = onScan) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.duplicates_scan))
            }
        }
    }
}

@Composable
private fun Progress(progress: DuplicateProgress) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        val text = when (progress) {
            is DuplicateProgress.Listing -> pluralStringResource(R.plurals.duplicates_listing, progress.files, progress.files)
            is DuplicateProgress.Sampling -> stringResource(R.string.duplicates_sampling, progress.done, progress.total)
            is DuplicateProgress.Reading -> stringResource(R.string.duplicates_reading, progress.done, progress.total)
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What was compared, and what was not — said, never read as "no duplicates there". */
@Composable
private fun Summary(report: DuplicateReport) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            stringResource(
                R.string.duplicates_summary,
                pluralStringResource(R.plurals.duplicates_files_compared, report.filesListed, report.filesListed),
                pluralStringResource(R.plurals.duplicates_groups, report.groups.size, report.groups.size),
                stringResource(R.string.duplicates_wasted, formatBytes(report.groups.sumOf(DuplicateGroup::wastedBytes))),
            ),
            style = style,
            color = color,
        )
        if (report.truncated) {
            Text(pluralStringResource(R.plurals.duplicates_truncated, report.filesListed, report.filesListed), style = style, color = color)
        }
        if (report.unreadableFolders > 0) {
            Text(
                pluralStringResource(R.plurals.walk_unreadable_folders, report.unreadableFolders, report.unreadableFolders),
                style = style,
                color = color
            )
        }
        if (report.unreadableFiles > 0) {
            Text(
                pluralStringResource(R.plurals.duplicates_unreadable_files, report.unreadableFiles, report.unreadableFiles),
                style = style,
                color = color
            )
        }
    }
}

private fun LazyListScope.groups(
    groups: List<DuplicateGroup>,
    ticked: Set<String>,
    can: (DuplicateFile) -> Boolean,
    tick: (DuplicateFile) -> Unit,
) {
    item(key = "keep-one") {
        Text(
            stringResource(R.string.duplicates_keep_one),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
    groups.forEach { group ->
        item(key = "group:" + group.digest) {
            ListItem(
                leadingContent = {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                headlineContent = { Text(File(group.files.first().path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        pluralStringResource(
                            R.plurals.duplicates_group,
                            group.files.size,
                            group.files.size,
                            formatBytes(group.size),
                            formatBytes(group.wastedBytes),
                        ),
                    )
                },
            )
        }
        items(group.files, key = { "copy:" + it.path }) { file ->
            TickRow(file, file.path, dateOf(file), ticked, can, tick)
        }
    }
}

private fun LazyListScope.largest(
    files: List<DuplicateFile>,
    ticked: Set<String>,
    can: (DuplicateFile) -> Boolean,
    tick: (DuplicateFile) -> Unit,
) {
    items(files, key = { "large:" + it.path }) { file ->
        TickRow(file, File(file.path).name, file.path, ticked, can, tick, trailing = formatBytes(file.size))
    }
}

@Composable
private fun TickRow(
    file: DuplicateFile,
    title: String,
    subtitle: String,
    ticked: Set<String>,
    can: (DuplicateFile) -> Boolean,
    tick: (DuplicateFile) -> Unit,
    trailing: String? = null,
) {
    val enabled = can(file)
    val checked = file.path in ticked
    // The whole row ticks, one target for a finger and for TalkBack: the box alone was 48 dp of a full-width row.
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { tick(file) },
        ),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        headlineContent = {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        },
        supportingContent = {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        },
        trailingContent = trailing?.let { { Text(it, style = MaterialTheme.typography.labelLarge) } },
    )
}

private fun dateOf(file: DuplicateFile): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.modifiedMs))

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
