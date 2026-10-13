package com.readfilestech.read_files_tech.data.viewer.html

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What a navigation of the page leads to. */
sealed interface HtmlLink {
    /** A place in the page itself (`#chapter-2`): the WebView scrolls there. */
    data object Scroll : HtmlLink

    /** A file of the page's folder, at [path] (decoded, relative): opened as the explorer opens a file. */
    data class Sibling(val path: String) : HtmlLink

    /** A web page or an address (`https:`, `http:`, `mailto:`): handed to the app the user chose for it. */
    data class Web(val url: String) : HtmlLink

    /** Nothing happens: not tapped, another scheme, or a path of the loader outside the page's folder. */
    data object Refused : HtmlLink
}

/**
 * The page's address and where its links lead. The page is served at `https://<HOST><PREFIX><name>`, its
 * folder under [PREFIX]: the WebView reads nothing through `file://` (inventory D20).
 *
 * The WebView itself never leaves the page: a link the user taps opens elsewhere — a file of the folder
 * as the explorer opens it, a web address in the user's browser — and every navigation nobody tapped (a
 * `<meta http-equiv="refresh">`, a redirect) is refused. The 2.15.3 followed a link to a page of the folder
 * INSIDE its WebView, by `file://`, where the policy injected into the first page did not exist.
 */
object HtmlLinks {
    /** WebViewAssetLoader's own domain: the app answers there, the network is never asked. */
    const val HOST = "appassets.androidplatform.net"

    /** Where the page's folder is served. */
    const val PREFIX = "/page/"

    /** The address the WebView loads the page [name] from. */
    fun pageUrl(name: String): String = "https://$HOST$PREFIX${encode(name)}"

    /**
     * Where [url] leads from the page [name]; [tapped] says whether the user's gesture started it. Only
     * the user's tap leads anywhere, and only to `https:`, `http:` and `mailto:` — never `javascript:`,
     * `file:`, `content:` or `intent:`.
     */
    fun of(url: String, name: String, tapped: Boolean): HtmlLink {
        val scheme = url.substringBefore(':', "").lowercase()
        return when {
            !tapped -> HtmlLink.Refused
            scheme == "https" && url.startsWith("https://$HOST$PREFIX") -> inFolder(url.substring("https://$HOST$PREFIX".length), name)
            // The loader's domain, any other way: never handed to a browser, the domain exists out there.
            scheme in WEB_SCHEMES && hostOf(url) == HOST -> HtmlLink.Refused
            scheme in WEB_SCHEMES || scheme == "mailto" -> HtmlLink.Web(url)
            else -> HtmlLink.Refused
        }
    }

    private fun inFolder(rest: String, name: String): HtmlLink {
        val fragment = rest.indexOf('#')
        val path = decode(rest.substring(0, if (fragment < 0) rest.length else fragment).substringBefore('?'))
        return when {
            path == null -> HtmlLink.Refused
            path == name -> if (fragment >= 0 && '?' !in rest.substring(0, fragment)) HtmlLink.Scroll else HtmlLink.Refused
            else -> HtmlLink.Sibling(path)
        }
    }

    /** The host of a `scheme://` address, lower case, without its user or port. */
    private fun hostOf(url: String): String {
        val authority = url.substringAfter("//", "").takeWhile { it !in "/?#\\" }
        return authority.substringAfterLast('@').substringBefore(':').lowercase()
    }

    /** [name] as one segment of a path: UTF-8, every byte but the unreserved ones percent-encoded. */
    internal fun encode(name: String): String = buildString {
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and BYTE).toChar()
            if (c in UNRESERVED) append(c) else append('%').append(HEX[c.code shr NIBBLE]).append(HEX[c.code and LOW_NIBBLE])
        }
    }

    /** [path] percent-decoded as UTF-8; null when it does not decode (a `%` with no two digits, bad UTF-8). */
    internal fun decode(path: String): String? {
        if (BAD_PERCENT.containsMatchIn(path)) return null
        val bytes = ByteArrayOutputStream(path.length)
        var last = 0
        PERCENT.findAll(path).forEach { escape ->
            bytes.write(path.substring(last, escape.range.first).toByteArray(Charsets.UTF_8))
            bytes.write(escape.value.substring(1).toInt(HEX_RADIX))
            last = escape.range.last + 1
        }
        bytes.write(path.substring(last).toByteArray(Charsets.UTF_8))
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (expected: CharacterCodingException) {
            null
        }
    }

    private val WEB_SCHEMES = setOf("https", "http")

    /** A `%` not followed by two hexadecimal digits. */
    private val BAD_PERCENT = Regex("%(?![0-9A-Fa-f]{2})")
    private val PERCENT = Regex("%[0-9A-Fa-f]{2}")
    private const val HEX_RADIX = 16
    private val UNRESERVED = ('A'..'Z').toSet() + ('a'..'z') + ('0'..'9') + setOf('-', '.', '_', '~')
    private const val HEX = "0123456789ABCDEF"
    private const val BYTE = 0xFF
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0xF
}
