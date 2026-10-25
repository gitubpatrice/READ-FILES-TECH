package com.readfilestech.read_files_tech.ui.tools.texttools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.TextFiles
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.tools.TextStats
import com.readfilestech.read_files_tech.data.viewer.sheet.cut
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.BrandButton
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.outputs.AutoShare
import com.readfilestech.read_files_tech.ui.outputs.OutputSavedCard
import com.readfilestech.read_files_tech.ui.tools.ChosenFileRow
import com.readfilestech.read_files_tech.ui.tools.ToolIntro
import java.text.NumberFormat

private const val PERCENT = 100

/**
 * `TxtToolsScreen` of the 2.15.3: a text chosen in the app's picker, its statistics, a search counted as it is
 * typed, "Replace all" into a copy, "Convert to PDF", and its viewer from the top bar. The outputs are shown as
 * the other tools show theirs, and shared at once when the setting says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxtToolsScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TxtToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(picked.files) {
        picked.files?.firstOrNull()?.let {
            picked.consume()
            viewModel.choose(it)
        }
    }
    AutoShare(state.share, viewModel.launching, viewModel::shared)
    // Here, not in the tools below: kept while another file is read, as the 2.15.3's fields were.
    val fields = rememberSaveable(saver = SearchFields.SAVER) { SearchFields() }
    LaunchedEffect(fields.search) { viewModel.search(fields.search) }
    val title = stringResource(R.string.feature_txt_tools)
    val pickTitle = stringResource(R.string.txt_pick_title)
    val choose = { onNavigate(Routes.picker(PickMode.FILE, TextFiles.EXTENSIONS, pickTitle)) }
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
        if (file == null) {
            ToolIntro(Icons.AutoMirrored.Outlined.TextSnippet, title, stringResource(R.string.txt_intro), choose, Modifier.padding(padding))
        } else {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChosenFileRow(file, choose, enabled = state.working == null)
                HorizontalDivider()
                val failure = state.failure
                when {
                    state.loading -> Busy(stringResource(R.string.txt_reading), null)
                    failure != null -> Text(failure.resolve(), color = MaterialTheme.colorScheme.error)
                    else -> Tools(state, fields, viewModel, onNavigate)
                }
            }
        }
    }
}

/** "View": the file in the app's viewer, as the 2.15.3's top bar offered it. */
@Composable
internal fun ViewAction(file: FileEntry, onNavigate: (String) -> Unit) {
    val target = FileRouter.targetFor(file)
    if (target is FileTarget.Internal) {
        IconButton(onClick = { onNavigate(target.route) }) {
            Icon(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.action_open))
        }
    }
}

/** What the search and the replacement fields hold, each within [FileCaps.PATTERN_CHARS]. */
private class SearchFields(search: String = "", replacement: String = "") {
    var search by mutableStateOf(search)
    var replacement by mutableStateOf(replacement)

    companion object {
        val SAVER: Saver<SearchFields, List<String>> = Saver(
            save = { listOf(it.search, it.replacement) },
            restore = { SearchFields(it[0], it[1]) },
        )
    }
}

@Composable
private fun Tools(state: TxtToolsState, fields: SearchFields, viewModel: TxtToolsViewModel, onNavigate: (String) -> Unit) {
    val search = fields.search
    val replacement = fields.replacement
    val result = state.result
    if (result != null) {
        val replaced = state.replaced
        OutputSavedCard(
            title = stringResource(if (replaced != null) R.string.txt_replaced_title else R.string.tools_pdf_created),
            body = if (replaced != null) {
                pluralStringResource(R.plurals.txt_replaced_body, replaced.toPluralCount(), replaced)
            } else {
                stringResource(R.string.convert_saved_body)
            },
            path = result,
            onNavigate = onNavigate,
        )
    }
    Text(stringResource(R.string.txt_statistics), style = MaterialTheme.typography.titleSmall)
    val stats = state.stats
    val counting = state.counting
    when {
        stats != null -> Statistics(stats)
        counting != null -> Busy(stringResource(R.string.txt_counting, (counting * PERCENT).toInt()), counting)
    }
    Text(stringResource(R.string.txt_search_replace), style = MaterialTheme.typography.titleSmall)
    val idle = state.working == null
    OutlinedTextField(
        value = search,
        onValueChange = { fields.search = cut(it, FileCaps.PATTERN_CHARS) },
        label = { Text(stringResource(R.string.txt_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        supportingText = { if (search.isNotEmpty()) Text(matchesText(state)) },
        singleLine = true,
        enabled = idle,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = replacement,
        onValueChange = { fields.replacement = cut(it, FileCaps.PATTERN_CHARS) },
        label = { Text(stringResource(R.string.txt_replace_with)) },
        leadingIcon = { Icon(Icons.Outlined.FindReplace, contentDescription = null) },
        singleLine = true,
        enabled = idle,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { viewModel.replaceAll(search, replacement) },
        enabled = idle && (state.matches ?: 0) > 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.FindReplace, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.txt_replace_all))
    }
    Text(stringResource(R.string.txt_replace_note, OutputCategory.CONVERSIONS.folderName), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.txt_export), style = MaterialTheme.typography.titleSmall)
    BrandButton(onClick = viewModel::exportPdf, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.txt_to_pdf))
    }
    state.working?.let { work ->
        val label = if (work == TxtWork.REPLACE) R.string.txt_replacing else R.string.tools_pdf_working
        Working(stringResource(label, (state.progress * PERCENT).toInt()), state.progress, viewModel::stop)
    }
}

@Composable
private fun matchesText(state: TxtToolsState): String {
    val matches = state.matches
    return when {
        state.searching || matches == null -> stringResource(R.string.txt_searching)
        matches == 0L -> stringResource(R.string.txt_not_found)
        else -> pluralStringResource(R.plurals.txt_matches, matches.toPluralCount(), matches)
    }
}

/** A count as a plural's quantity: past an Int, "many" all the same. */
private fun Long.toPluralCount(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

@Composable
private fun Statistics(stats: TextStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(stringResource(R.string.txt_characters), stats.characters, Modifier.weight(1f))
        StatCard(stringResource(R.string.txt_words), stats.words, Modifier.weight(1f))
        StatCard(stringResource(R.string.txt_lines), stats.lines, Modifier.weight(1f))
    }
}

/** A count and what it counts, as the 2.15.3's statistic cards. */
@Composable
internal fun StatCard(label: String, value: Long, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                NumberFormat.getIntegerInstance().format(value),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Work in progress: [progress] when it is known. */
@Composable
internal fun Busy(label: String, progress: Float?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** An output being made, its progress and "Cancel". */
@Composable
internal fun Working(label: String, progress: Float, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onStop) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}
