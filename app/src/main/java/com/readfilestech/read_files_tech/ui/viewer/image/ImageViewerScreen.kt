package com.readfilestech.read_files_tech.ui.viewer.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.images.DecodedImage
import com.readfilestech.read_files_tech.data.viewer.image.Gallery
import com.readfilestech.read_files_tech.data.viewer.image.ImagePage
import com.readfilestech.read_files_tech.ui.explorer.EntryInfoDialog
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailureMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
import kotlin.math.min

/** No page chosen yet: the gallery opens on the image the user tapped. */
private const val NO_PAGE = -1

/**
 * `ImageViewerScreen` of the 2.15.3: the images of the folder, swiped through (from the explorer only),
 * pinched from 0.5 to 6 times, a tap hiding the bar; the file's information and share. Each page decodes
 * its own image when it is composed, and lets it go when it leaves: the gallery holds the pages beside
 * the one shown, never every image swiped past.
 */
@Composable
fun ImageViewerScreen(onBack: () -> Unit, viewModel: ImageViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableIntStateOf(NO_PAGE) }
    var barVisible by rememberSaveable { mutableStateOf(true) }
    var info by remember { mutableStateOf<FileEntry?>(null) }
    val gallery = (state as? ViewerState.Ready)?.content
    val index = gallery?.let { if (chosen in it.pages.indices) chosen else it.start }
    val page = index?.let { gallery.pages[it] }
    val shown = page?.source ?: viewModel.source

    ViewerScaffold(
        title = shown?.name.orEmpty(),
        shareable = shown?.shareable,
        state = state,
        onBack = onBack,
        barVisible = barVisible,
        actions = { loaded ->
            if (loaded.pages.size > 1 && index != null) {
                Text(
                    stringResource(R.string.image_position, index + 1, loaded.pages.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            page?.entry?.let { entry ->
                IconButton(onClick = { info = entry }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.action_info))
                }
            }
        },
    ) { loaded, padding ->
        Pages(
            gallery = loaded,
            initialPage = index ?: loaded.start,
            padding = padding,
            onPage = { chosen = it },
            onTap = { barVisible = !barVisible },
            viewModel = viewModel,
        )
    }
    info?.let { EntryInfoDialog(it, onCopied = viewModel::copied) { info = null } }
}

@Composable
private fun Pages(
    gallery: Gallery,
    initialPage: Int,
    padding: PaddingValues,
    onPage: (Int) -> Unit,
    onTap: () -> Unit,
    viewModel: ImageViewerViewModel,
) {
    val pager = rememberPagerState(initialPage = initialPage) { gallery.pages.size }
    // The zoom of the page shown; another page opens fitted.
    val zoom = remember { mutableStateOf(ImageZoom.NONE) }
    val pageChanged by rememberUpdatedState(onPage)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { current ->
            pageChanged(current)
            zoom.value = ImageZoom.NONE
        }
    }
    // Decoded for the window, not the page: hiding the bar resizes the page, and must not decode it again.
    val window = LocalWindowInfo.current.containerSize
    Box(Modifier.fillMaxSize().background(Color.Black).padding(padding)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            // The neighbours ready before a swipe reaches them: three images held at most, whatever the gallery.
            beyondViewportPageCount = 1,
            // Zoomed, a drag moves the image; the gallery swipes again once it is back to its size.
            userScrollEnabled = !zoom.value.zoomed,
        ) { index ->
            PageImage(gallery.pages[index], window, zoom.takeIf { index == pager.currentPage }, onTap, viewModel)
        }
    }
}

@Composable
private fun PageImage(
    page: ImagePage,
    window: IntSize,
    zoom: MutableState<ImageZoom>?,
    onTap: () -> Unit,
    viewModel: ImageViewerViewModel,
) {
    // Loaded while the page is composed; cancelled, and its file released, when it leaves.
    val state by produceState<ViewerState<DecodedImage>>(ViewerState.Loading, page, window) {
        val content = viewModel.pageContent(this, page, window.width, window.height)
        try {
            content.state.collect { value = it }
        } finally {
            content.close()
        }
    }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val shown = state) {
                ViewerState.Loading -> CircularProgressIndicator(color = Color.White)
                is ViewerState.Failed -> ViewerFailureMessage(shown.failure, PaddingValues())
                is ViewerState.Ready -> ZoomableImage(shown.content, page.source.name, zoom, onTap)
            }
        }
    }
}

/** The image fitted in its page, then zoomed by [zoom] — null on a page beside the one shown. */
@Composable
private fun ZoomableImage(image: DecodedImage, description: String, zoom: MutableState<ImageZoom>?, onTap: () -> Unit) {
    val painter = painterOf(image)
    var box by remember { mutableStateOf(IntSize.Zero) }
    val frame by rememberUpdatedState(fitted(painter.intrinsicSize, box))
    val target by rememberUpdatedState(zoom)
    val tapped by rememberUpdatedState(onTap)
    Image(
        painter = painter,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tapped() },
                    onDoubleTap = { at -> target?.let { it.value = it.value.doubleTapped(at, frame) } },
                )
            }
            .pointerInput(Unit) { detectPinch({ target }, { frame }) }
            // Read here, not in composition: a pinch redraws the layer, it recomposes nothing.
            .graphicsLayer {
                val applied = target?.value ?: ImageZoom.NONE
                scaleX = applied.scale
                scaleY = applied.scale
                translationX = applied.x
                translationY = applied.y
            },
    )
}

/**
 * Two fingers zoom and move the image; one finger moves it once zoomed. At its fitted size a single
 * finger is left alone, for the gallery to swipe — the consumed changes are what it does not see.
 */
private suspend fun PointerInputScope.detectPinch(zoom: () -> MutableState<ImageZoom>?, frame: () -> ZoomFrame) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val target = zoom()
            val fingers = event.changes.count { it.pressed }
            if (target != null && (fingers > 1 || target.value.zoomed)) {
                val centroid = event.calculateCentroid()
                if (centroid.isSpecified) {
                    target.value = target.value.transformed(centroid, event.calculatePan(), event.calculateZoom(), frame())
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun painterOf(image: DecodedImage): Painter = when (image) {
    // Medium: bilinear with mipmaps, as the 2.15.3 — the image is decoded at up to twice the size shown.
    is DecodedImage.Still -> remember(image) {
        BitmapPainter(image.bitmap.asImageBitmap(), filterQuality = FilterQuality.Medium)
    }
    is DecodedImage.Animated -> remember(image) { DrawablePainter(image.drawable) }
}

/** The page and the image as ContentScale.Fit lays it in it. */
private fun fitted(image: Size, box: IntSize): ZoomFrame {
    val width = box.width.toFloat()
    val height = box.height.toFloat()
    if (!image.isSpecified || image.width <= 0f || image.height <= 0f) return ZoomFrame(width, height, width, height)
    val scale = min(width / image.width, height / image.height)
    return ZoomFrame(width, height, image.width * scale, image.height * scale)
}
