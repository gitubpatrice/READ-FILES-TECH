package com.readfilestech.read_files_tech.ui.viewer.zip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.zip.NotExtracted
import com.readfilestech.read_files_tech.data.viewer.zip.ZipArchive
import com.readfilestech.read_files_tech.data.viewer.zip.ZipItem
import com.readfilestech.read_files_tech.data.viewer.zip.ZipPaths
import com.readfilestech.read_files_tech.data.viewer.zip.ZipProblem
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.FileTypeIcon
import com.readfilestech.read_files_tech.ui.explorer.FolderSearchField
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.viewer.ViewerMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold

/** The refused entries a result names one by one; the others are counted. */
private const val REFUSED_SHOWN = 50

/**
 * `ZipViewerScreen` of the 2.15.3: the entries, filtered by name; one shared (extracted to the cache
 * first); "Extract all" into `Files Tech/Extractions/`, its progress shown and cancellable, then what
 * it wrote and what it left out, and the way to the folder. An entry that cannot be read says why in
 * its row; the 2.15.3 offered its share, which then failed with the exception's text.
 *
 * @param onNavigate opens a route of the app: the explorer on the folder of an extraction.
 */
@Composable
fun ZipViewerScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, viewModel: ZipViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val extraction by viewModel.extraction.collectAsStateWithLifecycle()
    val sharing by viewModel.sharing.collectAsStateWithLifecycle()
    val toShare by viewModel.toShare.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val source = viewModel.source

    LaunchedEffect(toShare) {
        toShare?.let { file ->
            viewModel.launching.share(context, listOf(DirectoryListing.entryOf(file)))
            viewModel.shareShown()
        }
    }

    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = { archive ->
            if (viewModel.canExport && archive.entries.isNotEmpty()) {
                IconButton(onClick = viewModel::extractAll, enabled = extraction !is Extraction.Running) {
                    Icon(Icons.Outlined.Unarchive, contentDescription = stringResource(R.string.zip_extract_all))
                }
            }
        },
    ) { archive, padding ->
        Listing(archive, shown, search, viewModel.canExport, sharing, padding, viewModel::search, viewModel::share)
    }

    when (val current = extraction) {
        is Extraction.Running -> ProgressDialog(current, viewModel::cancelExtraction)
        is Extraction.Done -> ResultDialog(
            current.result,
            onClose = viewModel::extractionSeen,
            onOpenFolder = { folder ->
                viewModel.extractionSeen()
                onNavigate(Routes.explorer(folder.path))
            },
        )
        Extraction.Idle -> Unit
    }
}

@Suppress("LongParameterList") // one screen's state, handed down once
@Composable
private fun Listing(
    archive: ZipArchive,
    shown: List<ZipItem>,
    search: String,
    canExport: Boolean,
    sharing: Int?,
    padding: PaddingValues,
    onSearch: (String) -> Unit,
    onShare: (ZipItem) -> Unit,
) {
    Column(Modifier.padding(padding).fillMaxSize()) {
        if (archive.entries.isEmpty()) {
            ViewerMessage(stringResource(R.string.zip_empty))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FolderSearchField(folderKey = null, onQuery = onSearch) }
            Text(
                pluralStringResource(R.plurals.item_count, shown.size, shown.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        if (shown.isEmpty()) {
            ViewerMessage(stringResource(R.string.folder_no_match, search))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = ZipItem::index) { item ->
                    EntryRow(item, canShare = canExport && item.readable && item.shareName != null, sharing == item.index) { onShare(item) }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(item: ZipItem, canShare: Boolean, preparing: Boolean, onShare: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(item.isDirectory, FileNames.extension(item.displayName))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val details = details(item)
            if (details.isNotEmpty()) {
                Text(
                    details,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            preparing -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            canShare -> IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.zip_share_entry, item.displayName))
            }
            else -> Spacer(Modifier.width(12.dp))
        }
    }
}

/** "a/b · 1.5 MB", then why it cannot be read or extracted, when it cannot. */
@Composable
private fun details(item: ZipItem): String {
    val why = when {
        item.isDirectory -> null
        item.problem != null -> problemText(item.problem)
        item.path == null -> stringResource(R.string.zip_unsafe_name)
        else -> null
    }
    val size = if (item.isDirectory) null else formatBytes(item.size)
    return listOfNotNull(item.folder.ifEmpty { null }, size, why).joinToString(" · ")
}

@Composable
private fun problemText(problem: ZipProblem): String = when (problem) {
    ZipProblem.ENCRYPTED -> stringResource(R.string.zip_problem_encrypted)
    ZipProblem.UNSUPPORTED_METHOD -> stringResource(R.string.zip_problem_method)
    ZipProblem.LINK -> stringResource(R.string.zip_problem_link)
    ZipProblem.OVERLAPPING -> stringResource(R.string.zip_problem_overlapping)
    ZipProblem.TOO_LARGE -> stringResource(R.string.zip_problem_too_large, formatBytes(FileCaps.ZIP_ENTRY_DECOMPRESSED))
    ZipProblem.DAMAGED -> stringResource(R.string.zip_problem_damaged)
}

@Composable
private fun reasonText(reason: NotExtracted): String = when (reason) {
    is NotExtracted.Unreadable -> problemText(reason.problem)
    NotExtracted.UnsafeName -> stringResource(R.string.zip_unsafe_name)
    NotExtracted.NameTaken -> stringResource(R.string.zip_name_taken)
    NotExtracted.OverTotal -> stringResource(R.string.zip_over_total, formatBytes(FileCaps.ZIP_EXTRACT_TOTAL))
}

/** While it runs: how far, and "Cancel" — the back key cancels too; a tap beside does nothing. */
@Composable
private fun ProgressDialog(running: Extraction.Running, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.zip_extracting)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = { if (running.total == 0) 0f else running.done.toFloat() / running.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                val bytes = formatBytes(running.bytes)
                Text(pluralStringResource(R.plurals.zip_extracting_progress, running.total, running.done, running.total, bytes))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** What it wrote and where, how it ended, what it left out and why; the folder one tap away. */
@Composable
private fun ResultDialog(result: ExtractionResult, onClose: () -> Unit, onOpenFolder: (java.io.File) -> Unit) {
    val folder = result.folder
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                stringResource(if (result.end == ExtractionEnd.Finished) R.string.zip_extract_done else R.string.zip_extract_stopped)
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultLines(result)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = folder?.takeIf { result.written > 0 }?.let { {
            TextButton(
                onClick = { onOpenFolder(it) }
            ) { Text(stringResource(R.string.action_open_folder)) }
        }
        },
    )
}

@Composable
private fun ResultLines(result: ExtractionResult) {
    val folder = result.folder
    when {
        result.end == ExtractionEnd.NoLocation -> Unit
        result.written > 0 && folder != null -> Text(
            pluralStringResource(R.plurals.zip_extract_written, result.written, result.written, ZipViewerViewModel.labelOf(folder)),
        )
        result.holdsFiles -> Text(stringResource(R.string.zip_extract_nothing))
        else -> Text(stringResource(R.string.zip_extract_no_file))
    }
    when (val end = result.end) {
        ExtractionEnd.Finished -> Unit
        ExtractionEnd.Cancelled -> Text(stringResource(R.string.zip_stop_cancelled))
        ExtractionEnd.TotalReached -> Text(stringResource(R.string.zip_stop_total, formatBytes(FileCaps.ZIP_EXTRACT_TOTAL)))
        is ExtractionEnd.Failed -> Text(stringResource(R.string.zip_stop_failed, end.reason.resolve()))
        ExtractionEnd.NoLocation -> Text(stringResource(R.string.zip_no_location))
    }
    if (result.refused.isEmpty()) return
    Text(
        pluralStringResource(R.plurals.zip_extract_refused, result.refused.size, result.refused.size),
        style = MaterialTheme.typography.titleSmall
    )
    result.refused.take(REFUSED_SHOWN).forEach { (item, reason) ->
        Text(
            stringResource(R.string.joined_dot, ZipPaths.display(item.name), reasonText(reason)),
            style = MaterialTheme.typography.bodySmall
        )
    }
    val more = result.refused.size - REFUSED_SHOWN
    if (more > 0) {
        Text(pluralStringResource(R.plurals.zip_extract_refused_more, more, more), style = MaterialTheme.typography.bodySmall)
    }
}
