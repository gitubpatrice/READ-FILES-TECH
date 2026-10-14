package com.readfilestech.read_files_tech.ui.tools.images

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.images.ImageMetadata
import com.readfilestech.read_files_tech.data.images.ImageSize
import com.readfilestech.read_files_tech.data.images.ImageToolRules
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.outputs.AutoShare
import com.readfilestech.read_files_tech.ui.outputs.OutputSavedCard
import com.readfilestech.read_files_tech.ui.tools.ChosenFileRow
import com.readfilestech.read_files_tech.ui.tools.ToolIntro
import kotlin.math.roundToInt

/**
 * `CompressScreen` of the 2.15.3: choose a picture, a JPEG quality and a largest width, then save the copy —
 * shared at once when the setting says so. The 2.15.3's button said "Compress and share" and shared only then.
 */
@Composable
fun CompressScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CompressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.feature_compress)
    ChooseFrom(picked, viewModel::choose)
    AutoShare(state.share, viewModel.launching, viewModel::shared)
    val choose = { onNavigate(Routes.picker(PickMode.FILE, ImageToolRules.COMPRESS_EXTENSIONS, title)) }
    ToolScaffold(title, onBack) { padding ->
        val file = state.file
        if (file == null) {
            ToolIntro(Icons.Outlined.Compress, title, stringResource(R.string.compress_intro), choose, Modifier.padding(padding))
        } else {
            ToolColumn(padding) {
                ChosenFileRow(file, choose, enabled = !state.working)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.compress_quality, state.quality))
                Slider(
                    value = state.quality.toFloat(),
                    onValueChange = { viewModel.setQuality(it.roundToInt()) },
                    valueRange = ImageToolRules.QUALITY_MIN.toFloat()..ImageToolRules.QUALITY_MAX.toFloat(),
                    steps = ImageToolRules.SLIDER_STEPS,
                    enabled = !state.working,
                )
                Text(stringResource(R.string.compress_max_width, state.maxWidth))
                Slider(
                    value = state.maxWidth.toFloat(),
                    onValueChange = { viewModel.setMaxWidth(it.roundToInt()) },
                    valueRange = ImageToolRules.WIDTH_MIN.toFloat()..ImageToolRules.WIDTH_MAX.toFloat(),
                    steps = ImageToolRules.SLIDER_STEPS,
                    enabled = !state.working,
                )
                Spacer(Modifier.size(12.dp))
                val labels = ActionLabels(R.string.action_compress, R.string.compress_working)
                ActionButton(Icons.Outlined.Compress, labels, state.working, viewModel::compress)
                Failure(state.failure)
                state.result?.let { result ->
                    OutputSavedCard(
                        stringResource(R.string.compress_saved_title),
                        stringResource(R.string.image_copy_saved_body),
                        result.path,
                        onNavigate,
                        Modifier.padding(top = 16.dp),
                    ) {
                        Text(stringResource(R.string.compress_sizes, formatBytes(result.before), formatBytes(result.after)))
                        val reduction = ImageToolRules.reductionPercent(result.before, result.after)
                        Text(
                            reduction?.let { stringResource(R.string.compress_reduction, it) } ?: stringResource(R.string.compress_larger),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * `ExifScreen` of the 2.15.3: what the picture says of where, when and with what it was taken, then a copy
 * without any of it in `Sans-EXIF/`, upright; the original is never touched.
 */
@Composable
fun ExifScreen(picked: PickedResults, onNavigate: (String) -> Unit, onBack: () -> Unit, viewModel: ExifViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.feature_exif)
    ChooseFrom(picked, viewModel::choose)
    AutoShare(state.share, viewModel.launching, viewModel::shared)
    val choose = { onNavigate(Routes.picker(PickMode.FILE, ImageToolRules.METADATA_EXTENSIONS, title)) }
    ToolScaffold(title, onBack) { padding ->
        val file = state.file
        if (file == null) {
            ToolIntro(Icons.Outlined.CleaningServices, title, stringResource(R.string.exif_intro), choose, Modifier.padding(padding))
        } else {
            ToolColumn(padding) {
                val busy = state.reading || state.working
                ChosenFileRow(file, choose, enabled = !busy)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                if (state.reading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.metadata?.let { metadata ->
                    MetadataList(metadata, state.size)
                    Spacer(Modifier.size(16.dp))
                    val labels = ActionLabels(R.string.action_strip_metadata, R.string.exif_working)
                    ActionButton(Icons.Outlined.CleaningServices, labels, state.working, viewModel::strip)
                }
                Failure(state.failure)
                state.result?.let { path ->
                    OutputSavedCard(
                        stringResource(R.string.exif_saved_title),
                        stringResource(R.string.image_copy_saved_body),
                        path,
                        onNavigate,
                        Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

/** The file the picker handed back, given to the tool once. */
@Composable
private fun ChooseFrom(picked: PickedResults, choose: (String) -> Unit) {
    LaunchedEffect(picked.files) {
        picked.files?.firstOrNull()?.let {
            picked.consume()
            choose(it)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
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
        content = content,
    )
}

@Composable
private fun ToolColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), content = content)
}

/** A tool's button: what it does, and what it says while it does it. */
private class ActionLabels(@param:StringRes val idle: Int, @param:StringRes val busy: Int)

@Composable
private fun ActionButton(icon: ImageVector, labels: ActionLabels, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(if (busy) labels.busy else labels.idle))
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun Failure(failure: UiText?) {
    failure?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
}

/** The 2.15.3's list: location and date flagged (what tells where and when), then camera, software, size. */
@Composable
private fun MetadataList(metadata: ImageMetadata, size: ImageSize?) {
    Text(stringResource(R.string.exif_found), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.size(8.dp))
    if (metadata.isEmpty) {
        Text(
            stringResource(R.string.exif_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val rows = buildList {
        if (metadata.gps) add(Triple(R.string.exif_gps, null, true))
        metadata.taken?.let { add(Triple(R.string.exif_taken, it, true)) }
        metadata.make?.let { add(Triple(R.string.exif_make, it, false)) }
        metadata.model?.let { add(Triple(R.string.exif_model, it, false)) }
        metadata.software?.let { add(Triple(R.string.exif_software, it, false)) }
    }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            rows.forEach { (label, value, sensitive) ->
                MetadataRow(stringResource(label), value ?: stringResource(R.string.exif_present), sensitive)
            }
            size?.let {
                val dimensions = stringResource(R.string.exif_dimensions_value, it.width, it.height)
                MetadataRow(stringResource(R.string.exif_dimensions), dimensions, sensitive = false)
            }
        }
    }
    if (metadata.gps) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.exif_gps_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String, sensitive: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (sensitive) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (sensitive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
