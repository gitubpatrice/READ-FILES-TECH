package com.readfilestech.read_files_tech.ui.tools.convert

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.convert.Conversion
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.outputs.OutputActionsRow
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import com.readfilestech.read_files_tech.ui.viewer.pdf.PasswordDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val PERCENT = 100

/**
 * The images a conversion takes: those the image viewer opens, and HEIC where Android decodes it (9 and
 * later) — the 2.15.3 announced HEIC everywhere and decoded it nowhere (inventory F6).
 */
private val IMAGES: Set<String> =
    ViewerKind.IMAGE.extensions + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setOf("heic", "heif") else emptySet()

/** How a conversion shows in the list, and what the picker offers for it — the 2.15.3's tiles. */
private class ConversionLook(
    @param:StringRes val title: Int,
    @param:StringRes val hint: Int,
    val icon: ImageVector,
    val pick: PickMode,
    val extensions: Set<String>,
)

private fun lookOf(conversion: Conversion): ConversionLook = when (conversion) {
    Conversion.IMAGES_TO_PDF -> ConversionLook(
        R.string.convert_images_to_pdf,
        R.string.convert_images_to_pdf_hint,
        Icons.Outlined.PictureAsPdf,
        PickMode.FILES,
        IMAGES,
    )
    Conversion.CSV_TO_XLSX -> ConversionLook(
        R.string.convert_csv_to_xlsx,
        R.string.convert_csv_to_xlsx_hint,
        Icons.Outlined.TableChart,
        PickMode.FILE,
        setOf("csv"),
    )
    Conversion.TEXT_TO_PDF -> ConversionLook(
        R.string.convert_text_to_pdf,
        R.string.convert_text_to_pdf_hint,
        Icons.Outlined.Description,
        PickMode.FILE,
        setOf("txt", "md"),
    )
    Conversion.PDF_TO_TEXT -> ConversionLook(
        R.string.convert_pdf_to_text,
        R.string.convert_pdf_to_text_hint,
        Icons.AutoMirrored.Outlined.TextSnippet,
        PickMode.FILE,
        setOf("pdf"),
    )
    Conversion.DOCX_TO_TEXT -> ConversionLook(
        R.string.convert_docx_to_text,
        R.string.convert_docx_to_text_hint,
        Icons.AutoMirrored.Outlined.Article,
        PickMode.FILE,
        setOf("docx", "docm"),
    )
    Conversion.IMAGE_TO_JPG -> ConversionLook(
        R.string.convert_image_to_jpg,
        R.string.convert_image_to_jpg_hint,
        Icons.Outlined.Image,
        PickMode.FILE,
        IMAGES,
    )
    Conversion.IMAGE_TO_PNG -> ConversionLook(
        R.string.convert_image_to_png,
        R.string.convert_image_to_png_hint,
        Icons.Outlined.Photo,
        PickMode.FILE,
        IMAGES,
    )
}

/**
 * `ConvertScreen` of the 2.15.3: its seven conversions, each choosing its file(s) in the app's picker
 * (the 2.15.3 used the system's for images). Its output is shown as the scanner's — the folder, open,
 * share — and shared at once when the setting says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(picked: PickedResults, onNavigate: (String) -> Unit, onBack: () -> Unit, viewModel: ConvertViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(picked.files) {
        picked.files?.let {
            picked.consume()
            viewModel.picked(it)
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_convert)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val working = state.working
            val result = state.result
            if (working != null) {
                Working(working, state.progress, viewModel::stop)
            } else if (result != null) {
                Result(result, onNavigate)
            }
            Conversion.entries.forEach { conversion ->
                val look = lookOf(conversion)
                val title = stringResource(look.title)
                ConversionRow(look, enabled = working == null) {
                    viewModel.choose(conversion)
                    onNavigate(Routes.picker(look.pick, look.extensions, title))
                }
            }
        }
    }
    state.password?.let { PasswordDialog(it, onUnlock = viewModel::unlock, onCancel = viewModel::dismissPassword) }
}

@Composable
private fun ConversionRow(look: ConversionLook, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(look.title), style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text(stringResource(look.hint)) },
            leadingContent = { Icon(look.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        )
    }
}

@Composable
private fun Working(conversion: Conversion, progress: Float?, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(lookOf(conversion).title), style = MaterialTheme.typography.titleSmall)
            if (progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.convert_working), style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.convert_working_percent, (progress * PERCENT).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onStop, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun Result(path: String, onNavigate: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.convert_saved_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(stringResource(R.string.convert_saved_body), style = MaterialTheme.typography.bodySmall)
            Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { File(path).parent?.let { onNavigate(Routes.explorer(path = it)) } }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text(stringResource(R.string.action_open_folder), Modifier.padding(start = 6.dp))
            }
            OutputActionsRow(path, onNavigate)
        }
    }
}
