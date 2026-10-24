package com.readfilestech.read_files_tech.ui.tools.scanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.scanner.PageFilter
import com.readfilestech.read_files_tech.data.scanner.Point
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import kotlin.math.min
import kotlin.math.roundToInt

private const val QUARTER_DEGREES = 90
private const val MAGNIFIER_ZOOM = 2.5f

/** The loupe's gap to the screen's edges, in stroke widths. */
private const val LOUPE_MARGIN = 4

/** The loupe's cross spans this fraction of its radius on each side. */
private const val CROSS_FRACTION = 4

/** How the loupe is drawn. */
private class LoupeStyle(val radius: Float, val line: Float, val colour: Color)

/**
 * The crop screen: the photo, its frame with four corners to drag (a magnifier shows what is under the
 * finger), "detect again", "whole image", a quarter turn, and the page's filter.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PageEditor(editor: PageEditorState, busy: Boolean, actions: PageEditorActions) {
    BackHandler(onBack = actions::cancelEditor)
    val settings = editor.draft.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scanner_adjust_title)) },
                navigationIcon = {
                    IconButton(onClick = actions::cancelEditor) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    IconButton(onClick = actions::applyEditor, enabled = editor.preview != null && !editor.detecting && !busy) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.scanner_keep_page))
                    }
                },
            )
        },
        // The scanner's own screen is not composed under this one: a page that could not be made, the page
        // limit reached, are said here, where the user is.
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val preview = editor.preview
                if (preview == null) {
                    CircularProgressIndicator()
                } else {
                    // Turned as the page will be, at once (seen on the S9: "Rotate" showed nothing until "Keep").
                    // A quarter turn swaps the space's sides; Compose maps a touch back through the rotation,
                    // so a dragged corner still lands where the finger is.
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val turned = if (settings.turns % 2 == 0) Modifier.fillMaxSize() else Modifier.requiredSize(maxHeight, maxWidth)
                        Box(turned.graphicsLayer { rotationZ = settings.turns * QUARTER_DEGREES.toFloat() }) {
                            FrameCanvas(editor.filtered ?: preview, settings.quad, actions::moveCorner, actions::keepDraft)
                        }
                    }
                }
                if (editor.detecting || editor.filtering || busy) CircularProgressIndicator()
            }
            val note = when {
                editor.detecting -> stringResource(R.string.scanner_detecting)
                editor.notFound -> stringResource(R.string.scanner_not_found)
                settings.turns != 0 -> stringResource(R.string.scanner_turned, settings.turns * QUARTER_DEGREES)
                else -> null
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ready = editor.preview != null && !editor.detecting && !busy
                OutlinedButton(onClick = actions::detectAgain, enabled = ready) {
                    Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                    Text(stringResource(R.string.scanner_detect_again), Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = actions::wholeImage, enabled = ready) {
                    Icon(Icons.Outlined.CropFree, contentDescription = null)
                    Text(stringResource(R.string.scanner_whole_image), Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = actions::rotate, enabled = ready) {
                    Icon(Icons.Outlined.RotateRight, contentDescription = null)
                    Text(stringResource(R.string.scanner_rotate), Modifier.padding(start = 6.dp))
                }
            }
            FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = settings.filter == filter,
                        onClick = { actions.filter(filter) },
                        label = { Text(stringResource(filter.label)) },
                    )
                }
            }
        }
    }
}

/** The name of a filter, on its chip. */
val PageFilter.label: Int
    get() = when (this) {
        PageFilter.ORIGINAL -> R.string.scanner_filter_original
        PageFilter.GREY -> R.string.scanner_filter_grey
        PageFilter.BLACK_WHITE -> R.string.scanner_filter_black_white
        PageFilter.WHITE_PAPER -> R.string.scanner_filter_white_paper
    }

/**
 * The photo fitted in the space, the frame over it. A drag that starts near a corner moves it; the view
 * model refuses a position that would no longer make a page (crossed sides, a sliver).
 */
@Composable
private fun FrameCanvas(image: ImageBitmap, quad: List<Point>, onCorner: (Int, Point) -> Unit, onDragEnd: () -> Unit) {
    val corners by rememberUpdatedState(quad)
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val grab = with(density) { 40.dp.toPx() }
    val handle = with(density) { 11.dp.toPx() }
    val loupe = with(density) { 56.dp.toPx() }
    val line = with(density) { 2.dp.toPx() }
    var dragged by remember { mutableIntStateOf(-1) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val description = stringResource(R.string.scanner_frame_description)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / image.width, boxH / image.height)
        val shown = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
        val origin = Offset((boxW - shown.width) / 2, (boxH - shown.height) / 2)
        fun onScreen(p: Point) = Offset(origin.x + (p.x * shown.width).toFloat(), origin.y + (p.y * shown.height).toFloat())
        fun inPhoto(o: Offset) = Point(((o.x - origin.x) / shown.width).toDouble(), ((o.y - origin.y) / shown.height).toDouble())
        Canvas(
            Modifier.fillMaxSize().semantics { contentDescription = description }.pointerInput(shown, origin) {
                detectDragGestures(
                    onDragStart = { start ->
                        val nearest = corners.indices.minByOrNull { (onScreen(corners[it]) - start).getDistance() }
                        dragged = nearest?.takeIf { (onScreen(corners[it]) - start).getDistance() <= grab } ?: -1
                        if (dragged >= 0) finger = onScreen(corners[dragged])
                    },
                    onDrag = { change, amount ->
                        if (dragged >= 0) {
                            change.consume()
                            finger += amount
                            onCorner(dragged, inPhoto(finger))
                        }
                    },
                    onDragEnd = {
                        if (dragged >= 0) onDragEnd()
                        dragged = -1
                    },
                    onDragCancel = {
                        if (dragged >= 0) onDragEnd()
                        dragged = -1
                    },
                )
            },
        ) {
            drawImage(image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = shown)
            val points = corners.map(::onScreen)
            val frame = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(frame, primary, style = Stroke(width = line))
            points.forEachIndexed { i, p ->
                drawCircle(Color.White, radius = handle, center = p)
                drawCircle(primary, radius = handle, center = p, style = Stroke(width = if (i == dragged) line * 2 else line))
            }
            if (dragged >= 0) magnifier(image, corners[dragged], points[dragged], LoupeStyle(loupe, line, primary))
        }
    }
}

/**
 * A round loupe in the top corner away from the finger: the photo around [at] (fractions), enlarged,
 * a cross on the exact point — the finger hides what it places.
 */
private fun DrawScope.magnifier(image: ImageBitmap, at: Point, finger: Offset, style: LoupeStyle) {
    val radius = style.radius
    val gap = radius + style.line * LOUPE_MARGIN
    val centre = Offset(if (finger.x < size.width / 2) size.width - gap else gap, gap)
    // The photo's pixels that fill the loupe: its diameter, in the photo's own pixels, divided by the zoom.
    val shownScale = min(size.width / image.width, size.height / image.height)
    val span = (2 * radius / (shownScale * MAGNIFIER_ZOOM)).roundToInt().coerceAtLeast(2)
    val sx = (at.x * image.width).roundToInt() - span / 2
    val sy = (at.y * image.height).roundToInt() - span / 2
    val clip = Path().apply { addOval(Rect(centre, radius)) }
    clipPath(clip) {
        drawRect(Color.Black, topLeft = centre - Offset(radius, radius), size = Size(2 * radius, 2 * radius))
        // Clamped to the photo; the part beyond its edges stays black.
        val left = sx.coerceIn(0, image.width - 1)
        val top = sy.coerceIn(0, image.height - 1)
        val right = (sx + span).coerceIn(left + 1, image.width)
        val bottom = (sy + span).coerceIn(top + 1, image.height)
        val k = 2 * radius / span
        drawImage(
            image,
            srcOffset = IntOffset(left, top),
            srcSize = IntSize(right - left, bottom - top),
            dstOffset = IntOffset((centre.x - radius + (left - sx) * k).roundToInt(), (centre.y - radius + (top - sy) * k).roundToInt()),
            dstSize = IntSize(((right - left) * k).roundToInt(), ((bottom - top) * k).roundToInt()),
        )
    }
    val cross = radius / CROSS_FRACTION
    drawCircle(style.colour, radius = radius, center = centre, style = Stroke(width = style.line))
    drawLine(style.colour, centre - Offset(cross, 0f), centre + Offset(cross, 0f), strokeWidth = style.line / 2)
    drawLine(style.colour, centre - Offset(0f, cross), centre + Offset(0f, cross), strokeWidth = style.line / 2)
}
