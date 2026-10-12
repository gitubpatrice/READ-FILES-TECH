package com.readfilestech.read_files_tech.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.explorer.ExplorerScreen
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.theme.ThemeMode

/**
 * The home (`HomeScreen` of the 2.15.3): the recent files and what to reach first, the tools, the
 * explorer. The explorer tab IS the explorer screen, with these tabs as its bottom bar.
 *
 * @param pickedFiles what the picker handed back ("Open a file"), until [onPickedFilesUsed].
 * @param pickedFolder what the folder chooser handed back to the explorer tab (copy / move to).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pickedFiles: List<String>?,
    onPickedFilesUsed: () -> Unit,
    pickedFolder: String?,
    onPickedFolderUsed: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val theme by viewModel.themes.current.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }

    fun open(path: String) = viewModel.open(path) { entry: FileEntry ->
        when (val target = FileRouter.targetFor(entry)) {
            is FileTarget.Internal -> onNavigate(target.route)
            FileTarget.External -> viewModel.launching.view(context, entry, chooser = false)
        }
    }

    LaunchedEffect(pickedFiles) {
        pickedFiles?.firstOrNull()?.let {
            onPickedFilesUsed()
            open(it)
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.reload()
        onPauseOrDispose { }
    }
    // Back from a tab or a search goes to the home first; the explorer's own (selection, history) win.
    BackHandler(enabled = tab != HomeTab.HOME || query != null) {
        if (query != null) query = null else tab = HomeTab.HOME
    }

    val navigationBar: @Composable () -> Unit = { HomeNavigationBar(tab) { tab = it } }
    val openTitle = stringResource(R.string.home_open_file)
    if (tab == HomeTab.EXPLORER) {
        ExplorerScreen(
            pickedFolder = pickedFolder,
            onPickedFolderUsed = onPickedFolderUsed,
            onOpenTrash = { onNavigate(Routes.TRASH) },
            onOpenSearch = { onNavigate(Routes.SEARCH) },
            onChooseFolder = { onNavigate(Routes.explorer(it, pick = PickMode.FOLDER)) },
            onFolderChosen = {},
            onFilesChosen = {},
            onNavigate = onNavigate,
            onLeave = { tab = HomeTab.HOME },
            navigationBar = navigationBar,
        )
        return
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            val searching = query
            if (searching != null) {
                SearchTopBar(searching, onQuery = { query = it }, onClose = { query = null })
            } else {
                HomeTopBar(
                    canSearch = tab == HomeTab.HOME && state.recents.isNotEmpty(),
                    theme = theme,
                    onSearch = { query = "" },
                    onTheme = viewModel::setTheme,
                    onNavigate = onNavigate,
                )
            }
        },
        bottomBar = navigationBar,
        floatingActionButton = {
            if (query == null) {
                // The content form, not `text =` / `icon =`: that one left the button without a name for
                // TalkBack (Notes Tech 3.1.0) — seen again here, its text absent from the accessibility tree.
                ExtendedFloatingActionButton(onClick = { onNavigate(Routes.picker(PickMode.FILE, FileRouter.VIEWABLE, openTitle)) }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(12.dp))
                    Text(openTitle)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val searching = query
            when {
                searching != null -> RecentSearch(state, searching, onOpen = ::open)
                tab == HomeTab.HOME -> HomeContent(
                    state = state,
                    onOpen = ::open,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onShare = { viewModel.launching.share(context, listOf(it)) },
                    onRemove = viewModel::remove,
                    onNavigate = onNavigate,
                )
                else -> ToolsGrid(onNavigate)
            }
        }
    }
}

@Composable
private fun HomeNavigationBar(tab: HomeTab, onTab: (HomeTab) -> Unit) {
    NavigationBar {
        HomeTab.entries.forEach { each ->
            val (label, icon) = when (each) {
                HomeTab.HOME -> R.string.home_tab_home to Icons.Filled.Home
                HomeTab.TOOLS -> R.string.home_tab_tools to Icons.Outlined.Build
                HomeTab.EXPLORER -> R.string.explorer_title to Icons.Filled.Folder
            }
            NavigationBarItem(
                selected = tab == each,
                onClick = { onTab(each) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    canSearch: Boolean,
    theme: ThemeMode,
    onSearch: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onNavigate: (String) -> Unit
) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            if (canSearch) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.home_search))
                }
            }
            IconButton(onClick = { onNavigate(Routes.pending(Feature.SETTINGS)) }) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.feature_settings))
            }
            IconButton(onClick = { onNavigate(Routes.pending(Feature.ABOUT)) }) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.feature_about))
            }
            ThemeMenu(theme, onTheme)
        },
    )
}

/** Light, dark, automatic — the current one marked (the 2.15.x showed it only through the icon). */
@Composable
private fun ThemeMenu(theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = {
            open = true
        }) { Icon(themeIcon(theme), contentDescription = stringResource(R.string.home_theme)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
                ThemeMode.SYSTEM to R.string.theme_system
            )
                .forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Icon(themeIcon(mode), contentDescription = null) },
                        trailingIcon = { if (mode == theme) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = {
                            open = false
                            onTheme(mode)
                        },
                    )
                }
        }
    }
}

private fun themeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
}

/** Searching the recent files by name (`_FileSearchDelegate` of the 2.15.x), favourites included. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.focusRequester(focus),
            )
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun RecentSearch(state: HomeUiState, query: String, onOpen: (String) -> Unit) {
    val found = state.recents.filter { it.name.contains(query, ignoreCase = true) }
    if (found.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
            Text(stringResource(R.string.home_no_result), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(found, key = { it.path }) { file -> RecentRow(file, onOpen = { onOpen(file.path) }, menu = null) }
    }
}
