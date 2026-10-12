package com.readfilestech.read_files_tech.ui.outputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.recents.RecentFiles
import com.readfilestech.read_files_tech.data.sharing.KnownApps
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A cloud app a result is sent to by name. */
data class CloudApp(val packageName: String, val name: String)

/**
 * Each output recorded among the recent files ONCE per run of the app, however often its row is shown
 * again (`_alreadyRegistered` of the 2.15.x's row): the tools make files the user has not opened yet.
 */
@Singleton
class OutputRecents @Inject constructor(private val recents: RecentFiles) {
    private val registered: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun register(path: String) {
        if (registered.add(path)) recents.addOrUpdate(path)
    }
}

/** What the row can offer for one output: whether it may leave the app, and the cloud apps installed. */
data class OutputActionsState(val entry: FileEntry? = null, val canLeave: Boolean = false, val clouds: List<CloudApp> = emptyList())

@HiltViewModel
class OutputActionsViewModel @Inject constructor(
    private val outputs: OutputRecents,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(OutputActionsState())
    val uiState: StateFlow<OutputActionsState> = state.asStateFlow()

    fun load(path: String) {
        viewModelScope.launch {
            state.value = withContext(io) {
                outputs.register(path)
                val entry = File(path).takeIf(File::isFile)?.let(DirectoryListing::entryOf)
                OutputActionsState(
                    entry = entry,
                    canLeave = entry != null && launching.canLeave(entry),
                    clouds = CLOUDS.filter { launching.isInstalled(it.packageName) },
                )
            }
        }
    }

    private companion object {
        val CLOUDS = listOf(
            CloudApp(KnownApps.KDRIVE, EntryLaunching.KDRIVE_NAME),
            CloudApp(KnownApps.GOOGLE_DRIVE, EntryLaunching.GOOGLE_DRIVE_NAME),
            CloudApp(KnownApps.PROTON_DRIVE, EntryLaunching.PROTON_DRIVE_NAME),
        )
    }
}

/**
 * The row under a tool's result (`OutputActionsRow` + `CloudShareRow` of the 2.15.3): open, share, send
 * to kDrive / Google Drive / Proton Drive — ONLY the apps installed: the 2.15.x showed the three, then
 * "not installed". A result kept in the app's private fallback cannot leave it, and is said so: the 2.15.x
 * offered the buttons, which then failed (inventory D46). The result is recorded among the recent files.
 *
 * @param onNavigate opens an internal viewer's route.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutputActionsRow(path: String, onNavigate: (String) -> Unit, viewModel: OutputActionsViewModel = hiltViewModel(key = "output:$path")) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(path) { viewModel.load(path) }
    val entry = state.entry ?: return
    val target = FileRouter.targetFor(entry)
    Column(Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Another app can open it only if it may leave the app.
            if (target is FileTarget.Internal || state.canLeave) {
                Button(onClick = {
                    when (target) {
                        is FileTarget.Internal -> onNavigate(target.route)
                        FileTarget.External -> viewModel.launching.view(context, entry, chooser = false)
                    }
                }) { Text(stringResource(R.string.action_open)) }
            }
            if (state.canLeave) {
                OutlinedButton(onClick = {
                    viewModel.launching.share(context, listOf(entry))
                }) { Text(stringResource(R.string.action_share)) }
                state.clouds.forEach { cloud ->
                    OutlinedButton(onClick = {
                        viewModel.launching.sendTo(context, entry, cloud.packageName, cloud.name)
                    }) { Text(cloud.name) }
                }
            }
        }
        if (!state.canLeave) {
            Text(
                stringResource(R.string.output_private),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
