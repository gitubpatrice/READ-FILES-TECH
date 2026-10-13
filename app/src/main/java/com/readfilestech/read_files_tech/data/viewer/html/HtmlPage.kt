package com.readfilestech.read_files_tech.data.viewer.html

import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.sharing.MimeTypes
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream

/** What the loader serves at a path of the page's folder. */
sealed interface HtmlResource {
    /** The page itself: [HtmlPage.served], in [HtmlPage.charset]. */
    data object Page : HtmlResource

    /** A file of the page's folder, of the type [mimeType]. */
    data class Sibling(val file: File, val mimeType: String) : HtmlResource
}

/**
 * A page of the HTML viewer (`HtmlViewerScreen` of the 2.15.3), read once within its cap
 * (`FileCaps.HTML_VIEWER`).
 *
 * The WebView gets its bytes from the app's loader, never from a file (docs D-028): [served] is the page
 * as read through its [ViewerSource], the policy's tag inserted ([HtmlCsp]) — a page of the vault is shown
 * without its plaintext ever being a file. Its folder is served only for a source another app may read
 * already ([ViewerSource.shareable], a file of the storage): the vault's has none, and serves its page alone.
 */
class HtmlPage private constructor(
    /** The name the page is served under, in its folder. */
    val name: String,
    private val bytes: ByteArray,
    private val size: Int,
    /** The source of the page, as the text viewer shows a text: its bytes, its encoding. */
    val source: TextDocument,
    private val folder: HtmlFolder?,
) {
    private val encoding = source.encoding
    private val utf16 = encoding.unitSize == 2

    /** The encoding the WebView is told: a BOM, else the page's `<meta>`, else UTF-8 when valid, else Windows-1252. */
    val charset: String = if (utf16 || encoding == TextEncoding.UTF_8_BOM) {
        encoding.charset.name()
    } else {
        HtmlCharset.declared(bytes, size) ?: encoding.charset.name()
    }

    /** Where the policy's tag goes, read from the page's start in its own units: one byte, or one UTF-16 unit, a character. */
    private val tagAt: Int = encoding.bomLength.let { from ->
        val window = minOf(size - from, HEAD_UNITS * encoding.unitSize).let { it - it % encoding.unitSize }
        from + HtmlCsp.insertionPoint(String(bytes, from, window, if (utf16) encoding.charset else Charsets.ISO_8859_1)) * encoding.unitSize
    }

    /** The policy's tag in the page's own units: UTF-16 after a UTF-16 BOM, ASCII in every other encoding. */
    private val tag = HtmlCsp.TAG.toByteArray(if (utf16) encoding.charset else Charsets.US_ASCII)

    /** The page as the WebView gets it: its bytes, untouched, with the policy's tag where [HtmlCsp] puts it. */
    fun served(): InputStream = SequenceInputStream(
        ByteArrayInputStream(bytes, 0, tagAt),
        SequenceInputStream(ByteArrayInputStream(tag), ByteArrayInputStream(bytes, tagAt, size - tagAt)),
    )

    /** What the loader serves at [path], decoded and relative to the page's folder; null for nothing. */
    fun resource(path: String): HtmlResource? = when {
        path == name -> HtmlResource.Page
        else -> folder?.resolve(path)?.let { HtmlResource.Sibling(it, typeOf(it.name)) }
    }

    companion object {
        /** Reads [source] whole, within the 2.15.3's cap; its folder, when it has one, is resolved now (disk). */
        suspend fun read(source: ViewerSource): HtmlPage {
            val expected = source.length()
            val (bytes, size) = source.open(FileCaps.HTML_VIEWER).use { TextDocument.readAll(it, expected, FileCaps.HTML_VIEWER) }
            return of(source.name, bytes, size, source.shareable?.parentFile?.let(HtmlFolder::of))
        }

        /** The page [name] of the first [size] bytes of [bytes] — kept, not copied — served with [folder]. */
        suspend fun of(name: String, bytes: ByteArray, size: Int = bytes.size, folder: HtmlFolder? = null): HtmlPage =
            HtmlPage(name, bytes, size, TextDocument.of(bytes, size), folder)

        /**
         * The type a file is served as: what the WebView does not guess from the bytes — a style sheet is
         * refused under another type, an SVG picture is not recognised — then the app's own table.
         */
        internal fun typeOf(name: String): String {
            val extension = FileNames.extension(name)
            return WEB_TYPES[extension] ?: MimeTypes.of(extension) ?: OCTET_STREAM
        }

        /** How much of a page's start [HtmlCsp.insertionPoint] reads: a DOCTYPE not closed by then gets the tag before it. */
        private const val HEAD_UNITS = 64 * 1024
        private const val OCTET_STREAM = "application/octet-stream"
        private val WEB_TYPES = mapOf("css" to "text/css", "svg" to "image/svg+xml")
    }
}
