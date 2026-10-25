package com.readfilestech.read_files_tech.ui.viewer.html

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.readfilestech.read_files_tech.data.viewer.html.HtmlCsp
import com.readfilestech.read_files_tech.data.viewer.html.HtmlLinks
import com.readfilestech.read_files_tech.data.viewer.html.HtmlPage
import com.readfilestech.read_files_tech.data.viewer.html.HtmlResource
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Everything the WebView of a page gets: its answer to each request, through androidx's WebViewAssetLoader
 * at `https://appassets.androidplatform.net/page/` — the page from memory ([HtmlPage.served], D-028), the
 * files of its folder from the disk ([HtmlPage.resource]) — and an empty refusal for anything else, so
 * that no request ever reaches the network stack: another domain, `file:`, `content:`, a path above the
 * folder, a POST.
 *
 * Every answer carries the policy as a header ([HtmlCsp]), and is not to be stored: the WebView keeps no
 * copy of a page of the vault in its cache.
 */
class HtmlAssets(val page: HtmlPage) {
    /** Where the WebView loads the page from. */
    val pageUrl: String = HtmlLinks.pageUrl(page.name)

    private val loader = WebViewAssetLoader.Builder()
        .setDomain(HtmlLinks.HOST)
        .addPathHandler(HtmlLinks.PREFIX, ::answer)
        .build()

    /** The answer to a request of [method] for [url]: never null, a refusal for what the loader does not serve. */
    fun serve(url: Uri, method: String): WebResourceResponse =
        (if (method == GET) loader.shouldInterceptRequest(url) else null) ?: refusal()

    /** The loader's answer for [path], decoded and relative to the folder. Called on the WebView's own thread. */
    private fun answer(path: String): WebResourceResponse? = when (val resource = page.resource(path)) {
        HtmlResource.Page -> response(TEXT_HTML, page.charset) { page.served() }
        is HtmlResource.Sibling -> response(resource.mimeType, null) { FileInputStream(resource.file) }
        null -> null
    }

    private fun response(mimeType: String, charset: String?, open: () -> InputStream): WebResourceResponse? = try {
        WebResourceResponse(mimeType, charset, OK, "OK", HEADERS, open())
    } catch (expected: IOException) {
        // Gone, or unreadable, since the page was read: refused like anything the loader does not serve.
        null
    }

    private fun refusal() = WebResourceResponse(TEXT_PLAIN, null, NOT_FOUND, "Not Found", HEADERS, ByteArrayInputStream(ByteArray(0)))

    private companion object {
        const val GET = "GET"
        const val TEXT_HTML = "text/html"
        const val TEXT_PLAIN = "text/plain"
        const val OK = 200
        const val NOT_FOUND = 404
        val HEADERS = mapOf("Content-Security-Policy" to HtmlCsp.POLICY, "Cache-Control" to "no-store")
    }
}
