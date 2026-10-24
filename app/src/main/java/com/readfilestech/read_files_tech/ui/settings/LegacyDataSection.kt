package com.readfilestech.read_files_tech.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.legacy.LegacyData
import com.readfilestech.read_files_tech.ui.common.ConfirmDangerDialog
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.theme.DangerRed

/**
 * The notice of the 2.x's extractions, at launch, once in the app's life (docs D-022), and how a move or a
 * deletion is going, whichever screen started it — the app's root shows it above every screen.
 */
@Composable
fun LegacyDataNotice(viewModel: LegacyDataViewModel = hiltViewModel()) {
    val working by viewModel.working.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.offerOnce() }
    LegacyDialogs(viewModel)
    if (working) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.legacy_working)) },
            text = { CircularProgressIndicator() },
        )
    }
}

/** The settings' section of the 2.x's data (docs D-021, D-022): there only while something is left. */
@Composable
internal fun LegacyDataSection(viewModel: LegacyDataViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val summary = state.summary
    if (summary != null && !summary.isEmpty) {
        SectionTitle(stringResource(R.string.legacy_section))
        if (summary.extractions.isNotEmpty()) {
            SettingRow(
                icon = Icons.Outlined.Unarchive,
                title = stringResource(R.string.legacy_extractions_title),
                summary = filesAndSize(R.plurals.legacy_extractions_summary, summary.extractionFiles, summary.extractionBytes),
                onClick = viewModel::askExtractions,
                enabled = !working,
            )
        }
        if (summary.history.isNotEmpty()) {
            SettingRow(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.legacy_history_title),
                summary = filesAndSize(R.plurals.legacy_history_summary, summary.history.size, summary.historyBytes),
                onClick = viewModel::askDeleteHistory,
                enabled = !working,
            )
        }
    }
    LegacyDialogs(viewModel)
}

@Composable
private fun LegacyDialogs(viewModel: LegacyDataViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.summary ?: return
    when (val dialog = state.dialog) {
        is LegacyDialog.Extractions -> ExtractionsDialog(
            summary,
            state.destination,
            first = dialog.first,
            onMove = viewModel::move,
            onDelete = viewModel::askDeleteExtractions,
            onLater = viewModel::dismiss,
        )
        LegacyDialog.DeleteExtractions -> ConfirmDangerDialog(
            title = stringResource(R.string.legacy_extractions_delete_title),
            message = filesAndSize(R.plurals.legacy_extractions_delete_body, summary.extractionFiles, summary.extractionBytes),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = viewModel::deleteExtractions,
            onDismiss = viewModel::dismiss,
        )
        LegacyDialog.DeleteHistory -> ConfirmDangerDialog(
            title = stringResource(R.string.legacy_history_delete_title),
            message = filesAndSize(R.plurals.legacy_history_delete_body, summary.history.size, summary.historyBytes),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = viewModel::deleteHistory,
            onDismiss = viewModel::dismiss,
        )
        null -> Unit
    }
}

/**
 * Move (what "Extract all" was for), delete — asked again, for good —, or later: nothing is moved in silence,
 * out of the private folder the files become readable by any app with storage access.
 */
@Composable
private fun ExtractionsDialog(
    summary: LegacyData.Summary,
    destination: String,
    first: Boolean,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
        title = { Text(stringResource(R.string.legacy_extractions_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(filesAndSize(R.plurals.legacy_extractions_body, summary.extractionFiles, summary.extractionBytes))
                Text(destination, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (first) Text(stringResource(R.string.legacy_later_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onMove) { Text(stringResource(R.string.legacy_move)) } },
        dismissButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = DangerRed) }
            TextButton(onClick = onLater) { Text(stringResource(R.string.action_later)) }
        },
    )
}

/** "N files, X MB" in a plural's words. */
@Composable
private fun filesAndSize(plural: Int, count: Int, bytes: Long): String = pluralStringResource(plural, count, count, formatBytes(bytes))
