package com.readfilestech.read_files_tech.ui.viewer.html

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.html.HtmlLink
import com.readfilestech.read_files_tech.data.viewer.html.HtmlLinks
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailureMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import com.readfilestech.read_files_tech.ui.viewer.text.ColorBar
import com.readfilestech.read_files_tech.ui.viewer.text.ColorsToggle
import com.readfilestech.read_files_tech.ui.viewer.text.TextRows
import kotlinx.coroutines.launch

/** The 2.15.3's source view: a fixed-width font of 12 pt. */
private const val SOURCE_FONT_SIZE = 12

/**
 * `HtmlViewerScreen` of the 2.15.3: the page drawn by a locked WebView, or its source; the colours of its
 * source in a bar (a tap copies the code); its reading mode, the reader's; share and "Open with…".
 *
 * Not ported: its "enable JavaScript" switch (scripts stay off). A link the user taps leaves the page ([HtmlLinks]).
 */
@Composable
fun HtmlViewerScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, viewModel: HtmlViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    val colors by viewModel.colors.collectAsStateWithLifecycle()
    var showSource by rememberSaveable { mutableStateOf(false) }
    var colorsHidden by rememberSaveable { mutableStateOf(false) }
    val source = viewModel.source
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = {
            if (colors.isNotEmpty()) ColorsToggle(colorsHidden) { colorsHidden = !colorsHidden }
            IconButton(onClick = { showSource = !showSource }) {
                Icon(
                    if (showSource) Icons.Filled.Web else Icons.Filled.Code,
                    contentDescription = stringResource(if (showSource) R.string.html_view_page else R.string.html_view_source),
                )
            }
            viewModel.readingRoute?.let { route ->
                IconButton(onClick = { onNavigate(route) }) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = stringResource(R.string.html_reading_mode))
                }
            }
        },
    ) { assets, padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!colorsHidden && colors.isNotEmpty()) ColorBar(colors, viewModel::copied)
            if (showSource) {
                TextRows(assets.page.source, SOURCE_FONT_SIZE, code = true)
            } else {
                PageView(assets) { link -> scope.launch { viewModel.follow(link, assets.page, context)?.let(onNavigate) } }
            }
        }
    }
}

/** The page in a WebView that reads only what [assets] serves; a renderer that dies takes the page, not the app. */
@Composable
private fun PageView(assets: HtmlAssets, onLink: (HtmlLink) -> Unit) {
    var drawing by remember(assets) { mutableStateOf(true) }
    var gone by remember(assets) { mutableStateOf(false) }
    val currentOnLink by rememberUpdatedState(onLink)
    if (gone) {
        ViewerFailureMessage(ViewerFailure.Reason(uiText(R.string.html_render_gone)), PaddingValues())
        return
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    lockDown(settings)
                    webViewClient = PageClient(
                        assets,
                        onLink = { currentOnLink(it) },
                        onDrawn = { drawing = false },
                        onGone = { gone = true },
                    )
                    loadUrl(assets.pageUrl)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = WebView::destroy,
        )
        if (drawing) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

/**
 * Nothing but what the loader serves: no file, no content URI, no network (the app has no INTERNET
 * permission, and asks the WebView not to try), no script, no storage, no cache, no window, no Safe
 * Browsing lookup. Zoom and the page's own width as the 2.15.3's webview_flutter set them.
 */
private fun lockDown(settings: WebSettings) = with(settings) {
    javaScriptEnabled = false
    allowFileAccess = false
    allowContentAccess = false
    blockNetworkLoads = true
    cacheMode = WebSettings.LOAD_NO_CACHE
    domStorageEnabled = false
    setGeolocationEnabled(false)
    safeBrowsingEnabled = false
    setSupportMultipleWindows(false)
    javaScriptCanOpenWindowsAutomatically = false
    mediaPlaybackRequiresUserGesture = true
    builtInZoomControls = true
    displayZoomControls = false
    loadWithOverviewMode = true
    useWideViewPort = true
}

/**
 * Every request answered by [assets] — never null, so none reaches the network stack — and every
 * navigation stopped but a scroll within the page: what the user tapped goes to [onLink], what nobody
 * tapped (a `<meta http-equiv="refresh">`, a redirect, a frame) goes nowhere.
 */
// MissingOnRenderProcessGone: implemented below, returning true — lint (AGP 9.4.1) reports this class all the same.
@SuppressLint("MissingOnRenderProcessGone")
private class PageClient(
    private val assets: HtmlAssets,
    private val onLink: (HtmlLink) -> Unit,
    private val onDrawn: () -> Unit,
    private val onGone: () -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
        assets.serve(request.url, request.method)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val tapped = request.isForMainFrame && !request.isRedirect && request.hasGesture()
        val link = HtmlLinks.of(request.url.toString(), assets.page.name, tapped)
        if (link == HtmlLink.Scroll) return false
        // A refused link the user tapped is said; a refresh nobody asked for is not.
        if (tapped) onLink(link)
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) = onDrawn()

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone()
        return true
    }
}
