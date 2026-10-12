package com.readfilestech.read_files_tech.ui.tools.scanner

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.scanner.ScanPage
import com.readfilestech.read_files_tech.data.scanner.ScanSession
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.outputs.OutputActionsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the picker offers to import: the images the 2.15.3's gallery import took that Android decodes. */
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")

/** The thumbnails are decoded at this many pixels a side at most. */
private const val THUMBNAIL_SIDE = 360

/**
 * `ScannerScreen` of the 2.15.3: photos (the system's camera app) or imported images become pages, each
 * framed on the crop screen; the PDF goes to `Scans/`, shared at once if the setting says so, and the row
 * under it opens, shares or sends it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(picked: PickedResults, onNavigate: (String) -> Unit, onBack: () -> Unit, viewModel: ScannerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.feature_scanner)
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(picked.files) {
        picked.files?.takeIf { it.isNotEmpty() }?.let {
            picked.consume()
            viewModel.import(it)
        }
    }
    LaunchedEffect(state.share) {
        state.share?.let { path ->
            val entry = withContext(Dispatchers.IO) { DirectoryListing.entryOf(File(path)) }
            viewModel.launching.share(context, listOf(entry))
            // Marked done only once handed over: done first, its own recomposition cancelled this effect.
            viewModel.shared()
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { viewModel.captured(it) }
    val full = state.pages.size >= ScanSession.MAX_PAGES
    val busy = state.progress != null
    val takePhoto: () -> Unit = {
        scope.launch {
            val uri = viewModel.prepareCapture() ?: return@launch
            try {
                camera.launch(uri)
            } catch (expected: ActivityNotFoundException) {
                viewModel.captured(false)
                viewModel.noCamera()
            }
        }
    }
    val importImages = { onNavigate(Routes.picker(PickMode.FILES, IMAGE_EXTENSIONS, title)) }
    // Pages not yet in a PDF: leaving asks first.
    val leave = { if (state.pages.isEmpty()) onBack() else confirmLeave = true }
    val editor = state.editor
    if (editor != null) {
        PageEditor(editor, busy, viewModel)
        return
    }
    BackHandler(enabled = state.pages.isNotEmpty()) { confirmLeave = true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = takePhoto, enabled = !full && !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Text(stringResource(R.string.scanner_take_photo), Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = importImages, enabled = !full && !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text(stringResource(R.string.scanner_import), Modifier.padding(start = 6.dp))
                }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.progress?.let { progress ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(progress.resolve(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            state.result?.let { path ->
                item(span = { GridItemSpan(maxLineSpan) }) { Result(path, onNavigate) }
            }
            if (state.pages.isEmpty() && state.result == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Intro() }
            }
            if (state.pages.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Button(onClick = viewModel::export, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                        Text(
                            pluralStringResource(R.plurals.scanner_make_pdf, state.pages.size, state.pages.size),
                            Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                PageCard(index, page, state.pages.size, busy, viewModel)
            }
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.scanner_discard_title)) },
            text = { Text(pluralStringResource(R.plurals.scanner_discard_body, state.pages.size, state.pages.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) { Text(stringResource(R.string.scanner_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Intro() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.DocumentScanner,
            contentDescription = null,
            modifier = Modifier.size(88.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(stringResource(R.string.feature_scanner), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.scanner_intro), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Result(path: String, onNavigate: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.scanner_saved_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(stringResource(R.string.scanner_saved_body), style = MaterialTheme.typography.bodySmall)
            Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { File(path).parent?.let { onNavigate(Routes.explorer(path = it)) } }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text(stringResource(R.string.action_open_folder), Modifier.padding(start = 6.dp))
            }
            OutputActionsRow(path, onNavigate)
        }
    }
}

@Composable
private fun PageCard(index: Int, page: ScanPage, count: Int, busy: Boolean, viewModel: ScannerViewModel) {
    val thumbnail by produceState<ImageBitmap?>(null, page.id, page.revision) {
        value = viewModel.thumbnail(page, THUMBNAIL_SIDE)
    }
    val number = index + 1
    Card {
        Column {
            Box(
                Modifier.fillMaxWidth().height(190.dp).clickable(enabled = !busy) { viewModel.edit(page) },
                contentAlignment = Alignment.Center,
            ) {
                thumbnail?.let {
                    Image(it, contentDescription = stringResource(R.string.scanner_edit_page, number), contentScale = ContentScale.Fit)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.scanner_page, number),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 10.dp)
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.move(index, index - 1) }, enabled = index > 0 && !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.scanner_move_earlier))
                }
                IconButton(onClick = { viewModel.move(index, index + 1) }, enabled = index < count - 1 && !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.scanner_move_later))
                }
                IconButton(onClick = { viewModel.delete(page) }, enabled = !busy) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.scanner_delete_page, number))
                }
            }
        }
    }
}
