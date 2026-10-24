package com.readfilestech.read_files_tech.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.common.SecureWindow
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import java.io.File

/**
 * Every viewer's frame: back, the file's name, the viewer's own [actions], share and "Open with…" when the
 * file may leave the app (never for the vault's, D-028), and the three states of [state] — the content
 * drawn by [content] only once it is ready.
 *
 * @param barVisible false hides the bar, actions included: a tap on a picture does (the 2.15.3's image viewer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ViewerScaffold(
    title: String,
    shareable: File?,
    state: ViewerState<T>,
    onBack: () -> Unit,
    barVisible: Boolean = true,
    actions: @Composable RowScope.(T) -> Unit = {},
    content: @Composable (T, PaddingValues) -> Unit,
) {
    val viewModel: ViewerActionsViewModel = hiltViewModel()
    LaunchedEffect(shareable) { viewModel.load(shareable) }
    // A vault file's (docs D-028): no screenshot, and gone the moment the vault locks — 30 s away, 3 min
    // without a touch, the lock button, the panic: its plaintext and its name leave the screen, the viewer
    // the stack. Collected in the background too: locked while away, the first frame back shows nothing.
    val vaultOpen by viewModel.vaultOpen.collectAsState()
    val hidden = viewModel.inVault && !vaultOpen
    if (viewModel.inVault) {
        SecureWindow()
        LaunchedEffect(hidden) { if (hidden) onBack() }
    }
    if (hidden) {
        Box(Modifier.fillMaxSize())
        return
    }
    val leavable by viewModel.leavable.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = topBar@{
            if (!barVisible) return@topBar
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state is ViewerState.Ready) actions(state.content)
                    // The entry of the file shown, never of the one before it: a gallery changes [shareable] at each page.
                    leavable?.takeIf { it.path == shareable?.absolutePath }?.let { entry ->
                        IconButton(onClick = { viewModel.launching.share(context, listOf(entry)) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                        }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_open_with)) },
                                onClick = {
                                    menu = false
                                    viewModel.launching.view(context, entry, chooser = true)
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        when (state) {
            ViewerState.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ViewerState.Failed -> ViewerFailureMessage(state.failure, padding)
            is ViewerState.Ready -> content(state.content, padding)
        }
    }
}

@Composable
internal fun ViewerFailureMessage(failure: ViewerFailure, padding: PaddingValues) {
    val text = when (failure) {
        is ViewerFailure.TooLarge -> stringResource(R.string.viewer_too_large, formatBytes(failure.capBytes))
        is ViewerFailure.Reason -> failure.text.resolve()
    }
    Column(
        Modifier.padding(padding).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
