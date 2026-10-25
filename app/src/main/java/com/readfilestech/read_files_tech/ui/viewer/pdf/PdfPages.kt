package com.readfilestech.read_files_tech.ui.viewer.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfPageFit
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** A page as its item shows it. */
private sealed interface PageImage {
    data object Loading : PageImage

    data object Failed : PageImage

    class Shown(bitmap: Bitmap) : PageImage {
        val image: ImageBitmap = bitmap.asImageBitmap()
    }
}

/** A sharper render of the part of a page on screen, drawn over the page's own (PdfView.tiles). */
private class PageTile(val bitmap: Bitmap, val request: TileRequest) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}

/**
 * The pages in one column fitted to the width (Syncfusion's continuous scroll), laid out lazily — only the
 * pages on screen are rendered — zoomed from fitted to three times by a pinch or a double tap, the pages
 * on screen rendered again at the zoom once it rests. Every bitmap stays within the screen's pixels (a
 * page's: twice them, for a page taller than the screen), and within FileCaps.IMAGE_PIXELS (PdfPageFit).
 *
 * @param onHeight the height of the column, which the page counter reads its middle from.
 */
@Composable
internal fun PdfPages(pages: PdfPageImages, list: LazyListState, zoom: MutableState<PdfZoom>, modifier: Modifier, onHeight: (Int) -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        SideEffect { onHeight(height) }
        val tiles = remember(pages) { mutableStateMapOf<Int, PageTile>() }
        TileRendering(pages, list, zoom, width, height, tiles)
        val density = LocalDensity.current
        // Zoomed, the last page must still scroll fully into view: the list ends that much lower.
        val end = with(density) { (height * (1 - 1 / zoom.value.scale)).toDp() }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(bottom = end),
            verticalArrangement = Arrangement.spacedBy(PAGE_GAP),
            modifier = Modifier
                .fillMaxSize()
                // Outside the layer: the gestures in the screen's pixels. The list's own scroll, inside it,
                // gets its moves divided by the zoom — a finger moves the page by as much as it moves.
                .pointerInput(pages, width) { detectPinch(zoom, list, width.toFloat()) }
                .pointerInput(pages, width) {
                    detectTapGestures(onDoubleTap = { at ->
                        val step = zoom.value.doubleTapped(at, width.toFloat())
                        zoom.value = step.zoom
                        list.dispatchRawDelta(step.scroll)
                    })
                }
                .graphicsLayer {
                    val applied = zoom.value
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = applied.scale
                    scaleY = applied.scale
                    translationX = applied.x
                },
        ) {
            items(pages.document.pageCount, key = { it }) { index ->
                PdfPage(pages, index, width, width.toLong() * height * PAGE_SCREENS, tiles[index])
            }
        }
    }
}

/** One page: its box laid out with its size once known (the first page's until then), its render, its tile. */
@Composable
private fun PdfPage(pages: PdfPageImages, index: Int, width: Int, maxPixels: Long, tile: PageTile?) {
    val boxHeight = PdfPageFit.boxHeight(pages.sizeOf(index), width)
    val image by produceState<PageImage>(PageImage.Loading, pages, index, width) {
        val bitmap = loadOrFailure { pages.take(index, width, maxPixels) }.getOrNull()
        if (bitmap == null) {
            value = PageImage.Failed
            return@produceState
        }
        value = PageImage.Shown(bitmap)
        // The page left the screen: its render goes back to the cache, never recycled while it is drawn.
        awaitDispose { pages.give(index, width, bitmap) }
    }
    val description = stringResource(R.string.pdf_page_description, index + 1)
    val density = LocalDensity.current
    Box(
        Modifier
            .size(with(density) { width.toDp() }, with(density) { boxHeight.toDp() })
            .background(Color.White)
            .semantics { contentDescription = description }
            .drawBehind {
                (image as? PageImage.Shown)?.let {
                    drawImage(
                        it.image,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    )
                }
                tile?.let { shown ->
                    val request = shown.request
                    drawImage(
                        shown.image,
                        dstOffset = IntOffset(request.left.roundToInt(), request.top.roundToInt()),
                        dstSize = IntSize((request.right - request.left).roundToInt(), (request.bottom - request.top).roundToInt()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image == PageImage.Failed) Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    }
}

/**
 * The tiles of the pages on screen, rendered once the zoom and the scroll have rested for [TILE_DELAY_MS]:
 * a new zoom or scroll cancels the renders in flight. A tile replaced or dropped is recycled on the main
 * thread, between two frames: the next one draws its replacement.
 */
@Composable
private fun TileRendering(
    pages: PdfPageImages,
    list: LazyListState,
    zoom: MutableState<PdfZoom>,
    width: Int,
    height: Int,
    tiles: SnapshotStateMap<Int, PageTile>
) {
    LaunchedEffect(pages, width, height) {
        snapshotFlow { zoom.value to list.layoutInfo.visibleItemsInfo.map { VisiblePage(it.index, it.offset, it.size) } }
            .collectLatest { (shown, visible) ->
                val wanted = PdfView.tiles(visible, width, height, shown)
                tiles.keys.filter { index -> wanted.none { it.index == index } }.forEach { tiles.remove(it)?.bitmap?.recycle() }
                if (wanted.isEmpty()) return@collectLatest
                delay(TILE_DELAY_MS)
                for (request in wanted) {
                    val bitmap = loadOrFailure { pages.tile(request, width.toLong() * height) }.getOrNull() ?: continue
                    tiles.put(request.index, PageTile(bitmap, request))?.bitmap?.recycle()
                }
            }
    }
    DisposableEffect(pages) {
        onDispose {
            tiles.values.forEach { it.bitmap.recycle() }
            tiles.clear()
        }
    }
}

/**
 * Two fingers zoom and move the column; one finger, zoomed, moves it sideways too — up and down are the
 * list's own, with its fling. Read on the Initial pass, before the list: a pinch never scrolls it.
 */
private suspend fun PointerInputScope.detectPinch(zoom: MutableState<PdfZoom>, list: LazyListState, width: Float) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) {
                val centroid = event.calculateCentroid()
                if (centroid.isSpecified) {
                    val step = zoom.value.transformed(centroid, event.calculatePan(), event.calculateZoom(), width)
                    zoom.value = step.zoom
                    list.dispatchRawDelta(step.scroll)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (zoom.value.zoomed) {
                zoom.value = zoom.value.panned(event.calculatePan().x, width)
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Syncfusion's `pageSpacing` (4 logical pixels). */
private val PAGE_GAP = 4.dp

/** A page's render is at most this many screens of pixels: a page taller than the screen keeps its width's sharpness. */
private const val PAGE_SCREENS = 2

/** The rest a zoom or a scroll takes before the pages on screen are rendered sharper. */
private const val TILE_DELAY_MS = 250L
