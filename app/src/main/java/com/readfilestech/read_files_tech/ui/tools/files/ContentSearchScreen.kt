package com.readfilestech.read_files_tech.ui.tools.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.search.ContentEvent
import com.readfilestech.read_files_tech.data.search.ContentHit
import com.readfilestech.read_files_tech.data.search.LineMatch
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MIB = 1024L * 1024

/**
 * `ContentSearchScreen` of the 2.15.3: a folder, what to look for, "Aa" (case) and ".*" (regex); the files
 * found with their matching lines, highlighted, each opened in the app's viewer for it (FileRouter — the
 * 2.15.3 kept a partial copy of the router here, inventory §1.C).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentSearchScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ContentSearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(picked.folder) {
        picked.folder?.let {
            picked.consume()
            viewModel.setRoot(it)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    val chooseTitle = stringResource(R.string.content_search_choose_folder)
    val start = { viewModel.start(query.trim(), matchCase, regex) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val open: (String) -> Unit = { path ->
        scope.launch {
            val entry = withContext(Dispatchers.IO) { DirectoryListing.entryOf(File(path)) }
            when (val target = FileRouter.targetFor(entry)) {
                is FileTarget.Internal -> onNavigate(target.route)
                FileTarget.External -> viewModel.launching.view(context, entry, chooser = false)
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_content_search)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FolderRow(state.root, enabled = !state.searching) { onNavigate(Routes.picker(PickMode.FOLDER, title = chooseTitle)) }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.content_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { start() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The chips scroll in what the button leaves: in French on a phone, they took the whole
                    // width and the button was squeezed into a thin bar (seen on the S9).
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = matchCase,
                            onClick = { matchCase = !matchCase },
                            label = { Text(stringResource(R.string.content_search_match_case)) }
                        )
                        FilterChip(
                            selected = regex,
                            onClick = { regex = !regex },
                            label = { Text(stringResource(R.string.content_search_regex)) }
                        )
                    }
                    SearchButton(state.searching, enabled = state.root != null, onStart = start, onStop = viewModel::stop)
                }
            }
            Status(state)
            Results(state, open, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FolderRow(root: String?, enabled: Boolean, onChoose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onChoose).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(
            root ?: stringResource(R.string.content_search_choose_folder),
            style = MaterialTheme.typography.bodyMedium,
            color = if (root == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchButton(searching: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    if (searching) {
        FilledTonalButton(onClick = onStop) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.action_stop))
        }
    } else {
        Button(onClick = onStart, enabled = enabled) { Text(stringResource(R.string.action_search)) }
    }
}

/** The progress while searching; then what was found, and what could not be searched. */
@Composable
private fun Status(state: ContentSearchState) {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (state.searching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        R.string.search_progress,
                        pluralStringResource(R.plurals.search_scanned, state.scanned, state.scanned),
                        pluralStringResource(R.plurals.search_found, state.hits.size, state.hits.size),
                    ),
                    style = style,
                    color = color,
                )
            }
        }
        if (state.hits.isNotEmpty() && !state.searching) {
            val lines = state.hits.sumOf(ContentHit::lineCount)
            Text(
                stringResource(
                    R.string.content_search_summary,
                    pluralStringResource(R.plurals.content_search_lines, lines, lines),
                    pluralStringResource(R.plurals.content_search_files, state.hits.size, state.hits.size),
                ),
                style = style,
                color = color,
            )
        }
        state.finished?.let { Notes(it) }
    }
}

@Composable
private fun Notes(finished: ContentEvent.Finished) {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.error
    if (finished.full) {
        Text(
            pluralStringResource(R.plurals.content_search_full, FileCaps.CONTENT_SEARCH_FILES, FileCaps.CONTENT_SEARCH_FILES),
            style = style,
            color = color
        )
    }
    if (finished.tooLarge > 0) {
        val megabytes = (FileCaps.SEARCH_CONTENT / MIB).toInt()
        Text(
            pluralStringResource(R.plurals.content_search_too_large, finished.tooLarge, finished.tooLarge, megabytes),
            style = style,
            color = color
        )
    }
    if (finished.unreadable > 0) {
        Text(
            pluralStringResource(R.plurals.content_search_unreadable, finished.unreadable, finished.unreadable),
            style = style,
            color = color
        )
    }
    if (finished.unreadableFolders > 0) {
        Text(
            pluralStringResource(R.plurals.walk_unreadable_folders, finished.unreadableFolders, finished.unreadableFolders),
            style = style,
            color = color,
        )
    }
}

@Composable
private fun Results(state: ContentSearchState, onOpen: (String) -> Unit, modifier: Modifier) {
    if (state.hits.isEmpty()) {
        val text = when {
            state.root == null -> R.string.content_search_choose_first
            state.searching -> R.string.search_running
            state.finished != null -> R.string.content_search_nothing
            else -> R.string.content_search_enter
        }
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    LazyColumn(modifier.fillMaxSize()) {
        items(state.hits, key = ContentHit::path) { hit ->
            HitCard(hit, state.root, hit.path in expanded, onToggle = {
                expanded = if (hit.path in expanded) expanded - hit.path else expanded + hit.path
            }, onOpen = { onOpen(hit.path) })
        }
    }
}

@Composable
private fun HitCard(hit: ContentHit, root: String?, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.clickable(onClick = onToggle).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(relative(hit.path, root), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.content_search_lines, hit.lineCount, hit.lineCount),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.action_open))
            }
        }
        if (expanded) {
            hit.lines.forEach { LineRow(it) }
            val more = hit.lineCount - hit.lines.size
            if (more > 0) {
                Text(
                    pluralStringResource(R.plurals.content_search_more_lines, more, more),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LineRow(match: LineMatch) {
    val highlight = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, fontWeight = FontWeight.Bold)
    val text = remember(match, highlight) { highlighted(match, highlight) }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(
            match.line.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
        )
    }
}

/** The line with its matches highlighted, "…" where it was cut. */
private fun highlighted(match: LineMatch, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    if (match.cutBefore) append(ELLIPSIS)
    var at = 0
    for (range in match.matches) {
        append(match.text.substring(at, range.first))
        withStyle(style) { append(match.text.substring(range.first, range.last + 1)) }
        at = range.last + 1
    }
    append(match.text.substring(at))
    if (match.cutAfter) append(ELLIPSIS)
}

/** The path below the folder searched (the 2.15.3's `_relativePath`). */
private fun relative(path: String, root: String?): String =
    if (root != null && path.startsWith(root.trimEnd('/') + "/")) path.substring(root.trimEnd('/').length + 1) else File(path).name

private const val ELLIPSIS = "…"
