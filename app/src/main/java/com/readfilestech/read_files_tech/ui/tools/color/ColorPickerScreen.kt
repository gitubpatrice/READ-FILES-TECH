package com.readfilestech.read_files_tech.ui.tools.color

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.tools.Hsv
import com.readfilestech.read_files_tech.data.tools.Rgb
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import kotlinx.coroutines.launch

private const val FULL_TURN = 360.0
private const val HUE_STOPS = 6
private const val MARKER_RADIUS = 9f
private const val MARKER_STROKE = 3f

/**
 * `ColorPickerScreen` of the 2.15.3: a saturation-value square under a hue bar (flutter_colorpicker's
 * `hsvWithHue`, the 2.15.3's default), the HEX, RGB and HSL codes copied with a tap, and the palette of
 * 12 colours — "Couleurs récentes" in the 2.15.3, which they never were (inventory F23).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerScreen(onBack: () -> Unit, viewModel: ColorPickerViewModel = hiltViewModel()) {
    val hsv by viewModel.color.collectAsStateWithLifecycle()
    val rgb = hsv.toRgb()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_color_picker)) },
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
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(rgb.color)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            )
            SaturationValue(hsv, viewModel::pick)
            HueBar(hsv, viewModel::pick)
            Codes(rgb, viewModel::copied)
            Text(stringResource(R.string.color_palette), style = MaterialTheme.typography.titleSmall)
            Palette(rgb, viewModel::pick)
        }
    }
}

/** The square of the hue chosen: saturation from left to right, value from bottom to top. */
@Composable
private fun SaturationValue(hsv: Hsv, onPick: (Hsv) -> Unit) {
    val current by rememberUpdatedState(hsv)
    val hue = Hsv(hsv.hue, 1.0, 1.0).toRgb().color
    val description = stringResource(R.string.color_area_description)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                track { position, size ->
                    val saturation = (position.x / size.width).toDouble().coerceIn(0.0, 1.0)
                    val value = (1 - position.y / size.height).toDouble().coerceIn(0.0, 1.0)
                    onPick(Hsv(current.hue, saturation, value))
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val marker = Offset((hsv.saturation * size.width).toFloat(), ((1 - hsv.value) * size.height).toFloat())
        drawCircle(Color.White, MARKER_RADIUS.dp.toPx(), marker, style = Stroke(MARKER_STROKE.dp.toPx()))
    }
}

/** The hues around the circle, from red back to red. */
@Composable
private fun HueBar(hsv: Hsv, onPick: (Hsv) -> Unit) {
    val current by rememberUpdatedState(hsv)
    val description = stringResource(R.string.color_hue_description)
    val hues = (0..HUE_STOPS).map { Hsv(it * FULL_TURN / HUE_STOPS, 1.0, 1.0).toRgb().color }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                track { position, size ->
                    // The right end is 360°, the same hue as the left: kept just below.
                    val hue = (position.x / size.width * FULL_TURN).coerceIn(0.0, Math.nextDown(FULL_TURN))
                    onPick(Hsv(hue, current.saturation, current.value))
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(hues))
        val marker = Offset((hsv.hue / FULL_TURN * size.width).toFloat(), size.height / 2)
        drawCircle(Color.White, size.height / 2 - MARKER_STROKE.dp.toPx(), marker, style = Stroke(MARKER_STROKE.dp.toPx()))
    }
}

/** Calls [onPosition] where the finger goes down, then wherever it is dragged, until it lifts. */
private suspend fun PointerInputScope.track(onPosition: (Offset, IntSize) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPosition(down.position, size)
        down.consume()
        drag(down.id) { change ->
            onPosition(change.position, size)
            change.consume()
        }
    }
}

@Composable
private fun Codes(rgb: Rgb, onCopied: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CodeRow(stringResource(R.string.color_hex), rgb.hex, onCopied)
        CodeRow(stringResource(R.string.color_rgb), rgb.rgbCode, onCopied)
        CodeRow(stringResource(R.string.color_hsl), rgb.hslCode, onCopied)
    }
}

@Composable
private fun CodeRow(label: String, value: String, onCopied: (String) -> Unit) {
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
            SelectionContainer(Modifier.weight(1f)) {
                Text(value, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value))) }
                onCopied(value)
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Palette(selected: Rgb, onPick: (Rgb) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorPickerViewModel.PALETTE.forEach { color ->
            val chosen = color == selected
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.color)
                    .border(
                        if (chosen) 3.dp else 1.dp,
                        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                    .semantics { contentDescription = color.hex }
                    .clickable { onPick(color) },
            )
        }
    }
}

private val Rgb.color: Color get() = Color(red, green, blue)
