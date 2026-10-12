package com.readfilestech.read_files_tech.ui.tools.hash

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.tools.FileHash
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import kotlinx.coroutines.launch

private const val PERCENT = 100

/**
 * `HashScreen` of the 2.15.3: choose a file in the app's picker, read it once for its four digests, copy
 * one. Its result comes back through [picked] (the feature's navigation entry).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashScreen(picked: PickedResults, onNavigate: (String) -> Unit, onBack: () -> Unit, viewModel: HashViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.feature_hash)
    LaunchedEffect(picked.files) {
        picked.files?.firstOrNull()?.let {
            picked.consume()
            viewModel.choose(it)
        }
    }
    val choose = { onNavigate(Routes.picker(PickMode.FILE, title = title)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val file = state.file
        if (file == null) {
            Intro(Modifier.padding(padding), choose)
        } else {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = choose) { Text(stringResource(R.string.action_change)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                when {
                    state.computing -> Progress(state.read, file.size)
                    state.failure != null -> Text(state.failure!!.resolve(), color = MaterialTheme.colorScheme.error)
                    else -> Digests(state.hashes, viewModel::copied)
                }
            }
        }
    }
}

@Composable
private fun Intro(modifier: Modifier, onChoose: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Fingerprint,
            contentDescription = null,
            modifier = Modifier.size(88.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.feature_hash), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.hash_intro), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onChoose) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_choose_file))
        }
    }
}

@Composable
private fun Progress(read: Long, size: Long) {
    val fraction = if (size > 0) (read.toFloat() / size).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.hash_computing, (fraction * PERCENT).toInt()), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Digests(hashes: List<FileHash>, onCopied: (String) -> Unit) {
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.hash_digests), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.size(12.dp))
    hashes.forEach { hash ->
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hash.algorithm, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(hash.algorithm, hash.hex))) }
                        onCopied(hash.hex)
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                    }
                }
                SelectionContainer {
                    Text(hash.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
