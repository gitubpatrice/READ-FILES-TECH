package com.readfilestech.read_files_tech.ui.tools.ocr

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.images.ImageToolRules
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.outputs.AutoShare
import com.readfilestech.read_files_tech.ui.outputs.OutputSavedCard
import com.readfilestech.read_files_tech.ui.tools.ToolResult
import kotlinx.coroutines.launch

/** The preview of the picture read: this high at most. */
private const val PREVIEW_HEIGHT_DP = 160

/**
 * `OcrScreen` of the 2.15.3: "Take a photo" (the system's camera app) or "Choose an image" (the app's
 * picker, the images the platform decodes); the picture, the text found — selectable, copied — then
 * "Save as .txt" into `OCR/`, with the row of the other tools' outputs under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(picked: PickedResults, onNavigate: (String) -> Unit, onBack: () -> Unit, viewModel: OcrViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.feature_ocr)
    LaunchedEffect(picked.files) {
        picked.files?.firstOrNull()?.let {
            picked.consume()
            viewModel.choose(it)
        }
    }
    AutoShare(state.share, viewModel.launching, viewModel::shared)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { viewModel.captured(it) }
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
    // What the platform decodes, as Compress takes it: the 2.15.3 offered any image and ML Kit failed on some.
    val chooseImage = { onNavigate(Routes.picker(PickMode.FILE, ImageToolRules.COMPRESS_EXTENSIONS, title)) }
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
        bottomBar = {
            // Above the navigation bar, as the scanner's (its three buttons on a Galaxy S9 hid half of these).
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = takePhoto, enabled = !state.working, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Text(stringResource(R.string.scanner_take_photo), Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = chooseImage, enabled = !state.working, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text(stringResource(R.string.ocr_choose_image), Modifier.padding(start = 6.dp))
                }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        if (state.untouched) {
            Intro(title, modifier)
        } else {
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Read(state, viewModel)
                state.saved?.let { path ->
                    OutputSavedCard(stringResource(R.string.ocr_saved_title), stringResource(R.string.ocr_saved_body), path, onNavigate)
                }
            }
        }
    }
}

/** The picture, then what is being done to it or what it gave. */
@Composable
private fun Read(state: OcrState, viewModel: OcrViewModel) {
    state.preview?.let {
        Image(
            it,
            contentDescription = stringResource(R.string.ocr_image_read),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = PREVIEW_HEIGHT_DP.dp),
        )
    }
    val text = state.text
    when {
        // Announced by TalkBack when it starts and when it ends (U2 of the 2.13.2).
        state.working -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.ocr_working), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        state.failure != null -> Text(state.failure.resolve(), color = MaterialTheme.colorScheme.error)
        text == null -> Unit
        text.isEmpty() -> Text(
            stringResource(R.string.ocr_no_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        else -> {
            ToolResult(text, text.length.toLong(), viewModel::copyText, viewModel::copied)
            Button(onClick = viewModel::save, enabled = !state.saving) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text(stringResource(R.string.ocr_save_txt), Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun Intro(title: String, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.DocumentScanner,
            contentDescription = null,
            modifier = Modifier.size(88.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.ocr_intro), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
