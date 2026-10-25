package com.readfilestech.read_files_tech.ui.tools.csvtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.BrandButton
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.outputs.AutoShare
import com.readfilestech.read_files_tech.ui.outputs.OutputSavedCard
import com.readfilestech.read_files_tech.ui.tools.ChosenFileRow
import com.readfilestech.read_files_tech.ui.tools.ToolIntro
import com.readfilestech.read_files_tech.ui.tools.texttools.Busy
import com.readfilestech.read_files_tech.ui.tools.texttools.StatCard
import com.readfilestech.read_files_tech.ui.tools.texttools.ViewAction
import com.readfilestech.read_files_tech.ui.tools.texttools.Working

private const val PERCENT = 100
private val CSV = setOf("csv")

/**
 * `CsvToolsScreen` of the 2.15.3: a CSV chosen in the app's picker, its rows, its columns and their names, "Export to
 * PDF", "Merge" — from the start, or with the file shown —, and its viewer from the top bar. The outputs are shown as
 * the other tools show theirs, and shared at once when the setting says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvToolsScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CsvToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(picked.files) {
        picked.files?.let {
            picked.consume()
            viewModel.picked(it)
        }
    }
    AutoShare(state.share, viewModel.launching, viewModel::shared)
    val title = stringResource(R.string.feature_csv_tools)
    val openTitle = stringResource(R.string.csv_tools_pick_title)
    val mergeTitle = stringResource(R.string.csv_tools_merge_pick_title)
    val choose = {
        viewModel.pickFor(CsvPick.OPEN)
        onNavigate(Routes.picker(PickMode.FILE, CSV, openTitle))
    }
    val merge = {
        viewModel.pickFor(CsvPick.MERGE)
        onNavigate(Routes.picker(PickMode.FILES, CSV, mergeTitle))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { state.file?.let { ViewAction(it, onNavigate) } },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val file = state.file
        if (file == null && state.result == null && state.working == null) {
            Intro(title, choose, merge, Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (file != null) {
                ChosenFileRow(file, choose, enabled = state.working == null)
                HorizontalDivider()
            }
            Output(state, onNavigate)
            val failure = state.failure
            val summary = state.summary
            when {
                file == null -> if (state.working == null) Intro(title, choose, merge)
                state.loading -> Busy(stringResource(R.string.txt_reading), null)
                failure != null -> Text(failure.resolve(), color = MaterialTheme.colorScheme.error)
                summary != null -> Tools(summary, state.working == null, viewModel::exportPdf, merge)
            }
            state.working?.let { work ->
                val label = if (work == CsvWork.MERGE) R.string.csv_tools_merging else R.string.tools_pdf_working
                Working(stringResource(label, (state.progress * PERCENT).toInt()), state.progress, viewModel::stop)
            }
        }
    }
}

/** Before a file is chosen: "Choose a file", and "Merge several CSV files" as the 2.15.3 offered it there. */
@Composable
private fun Intro(title: String, onChoose: () -> Unit, onMerge: () -> Unit, modifier: Modifier = Modifier) {
    ToolIntro(Icons.Outlined.TableChart, title, stringResource(R.string.csv_tools_intro), onChoose, modifier) {
        OutlinedButton(onClick = onMerge) {
            Icon(Icons.AutoMirrored.Outlined.MergeType, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.csv_tools_merge_several))
        }
    }
}

/** The last output, as the other tools show theirs. */
@Composable
private fun Output(state: CsvToolsState, onNavigate: (String) -> Unit) {
    val result = state.result ?: return
    val merged = state.merged
    if (merged == null) {
        OutputSavedCard(stringResource(R.string.tools_pdf_created), stringResource(R.string.convert_saved_body), result, onNavigate)
    } else {
        val rows = merged.rows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        OutputSavedCard(
            title = stringResource(R.string.csv_tools_merged_title),
            body = pluralStringResource(R.plurals.csv_tools_merged_body, rows, merged.rows),
            path = result,
            onNavigate = onNavigate,
        ) {
            val files = pluralStringResource(R.plurals.csv_tools_merged_files, merged.files, merged.files)
            Text(files, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tools(summary: CsvSummary, idle: Boolean, onPdf: () -> Unit, onMerge: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(stringResource(R.string.csv_tools_rows), summary.rows.toLong(), Modifier.weight(1f))
        StatCard(stringResource(R.string.csv_tools_columns), summary.columns.toLong(), Modifier.weight(1f))
    }
    val note = MaterialTheme.typography.bodySmall
    if (summary.rowsCut) Text(stringResource(R.string.csv_tools_rows_cut, FileCaps.CSV_ROWS), style = note)
    if (summary.columnsCut) Text(stringResource(R.string.csv_tools_columns_cut, FileCaps.CSV_COLUMNS), style = note)
    if (summary.headers.isNotEmpty()) {
        Text(stringResource(R.string.csv_tools_columns), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            summary.headers.forEach { name ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        val more = summary.columns - summary.headers.size
        if (more > 0) Text(pluralStringResource(R.plurals.csv_tools_more_columns, more, more), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.csv_tools_actions), style = MaterialTheme.typography.titleSmall)
    BrandButton(onClick = onPdf, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.csv_tools_to_pdf))
    }
    OutlinedButton(onClick = onMerge, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Outlined.MergeType, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.csv_tools_merge_with))
    }
    Text(stringResource(R.string.csv_tools_merge_note), style = MaterialTheme.typography.bodySmall)
}
