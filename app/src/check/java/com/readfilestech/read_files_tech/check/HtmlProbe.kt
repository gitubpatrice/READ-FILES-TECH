package com.readfilestech.read_files_tech.check

import androidx.core.net.toUri
import com.readfilestech.read_files_tech.data.viewer.html.HtmlLinks
import com.readfilestech.read_files_tech.data.viewer.html.HtmlPage
import com.readfilestech.read_files_tech.data.viewer.html.HtmlResource
import com.readfilestech.read_files_tech.ui.viewer.html.HtmlAssets
import java.io.File
import java.net.URI
import java.net.URISyntaxException

/**
 * What the HTML viewer's loader WOULD serve (the corpus runs no WebView): the page at its address, then
 * every address the page names — `src`, `href`, `srcset`, CSS `url()` and `@import` — and the private
 * files of the app by the three ways of inventory D20: a path above the folder, `file:`, its FileProvider.
 * The page must be served; anything else served must be a file of [folder], outside the app's [sandbox].
 * A breach throws: the corpus then reports the page `unreadable`, never `ok`.
 */
class HtmlProbe(private val sandbox: File, private val folder: File) {
    fun probe(page: HtmlPage) {
        val assets = HtmlAssets(page)
        check(assets.serve(assets.pageUrl.toUri(), GET).statusCode == OK) { "the page itself is not served" }
        val text = page.source.text(0, page.source.rowCount)
        val pkg = sandbox.name
        val private = "data/data/$pkg/shared_prefs/settings.xml"
        val d20 = listOf(
            "../../../../../$private",
            "..%2F..%2F..%2F..%2F..%2F" + private.replace("/", "%2F"),
            "file://${sandbox.absolutePath}/shared_prefs/settings.xml",
            "file:///$private",
            "content://$pkg.fileprovider/root/$private",
        )
        for (reference in references(text) + d20) {
            val url = resolve(assets.pageUrl, reference)
            if (url != null && assets.serve(url.toUri(), GET).statusCode == OK) checkServed(page, url)
        }
    }

    /** What the loader served at [url] is the page, or a file of the folder outside the sandbox. */
    private fun checkServed(page: HtmlPage, url: String) {
        check(url.startsWith("https://${HtmlLinks.HOST}${HtmlLinks.PREFIX}")) { "served outside the loader: $url" }
        val served = page.resource(url.toUri().path.orEmpty().removePrefix(HtmlLinks.PREFIX))
        if (served is HtmlResource.Sibling) {
            val file = served.file.canonicalFile.toPath()
            check(file.startsWith(folder.canonicalFile.toPath())) { "served outside the folder: $url" }
            check(!file.startsWith(sandbox.canonicalFile.toPath())) { "served from the sandbox: $url" }
        }
    }

    /** The addresses the page names, roughly: what a browser would ask for, and the links a user could tap. */
    private fun references(text: String): List<String> {
        val found = mutableListOf<String>()
        for (pattern in listOf(ATTRIBUTE, CSS_URL, IMPORT)) {
            pattern.findAll(text).forEach { match ->
                val value = match.groupValues.drop(1).firstOrNull(String::isNotEmpty) ?: return@forEach
                if (match.value.startsWith("srcset", ignoreCase = true)) {
                    value.split(',').mapNotNullTo(found) { it.trim().substringBefore(' ').takeIf(String::isNotEmpty) }
                } else {
                    found += value.trim()
                }
            }
        }
        return found
    }

    /** [reference] resolved against [base] as a browser roughly would: backslashes as slashes, the rest escaped. */
    private fun resolve(base: String, reference: String): String? = try {
        URI(base).resolve(URI(escape(reference.replace(BACKSLASH, '/')))).toString()
    } catch (expected: URISyntaxException) {
        null
    } catch (expected: IllegalArgumentException) {
        null
    }

    private fun escape(reference: String): String = buildString {
        for (c in reference) {
            if (c.code in PRINTABLE && c !in UNSAFE) {
                append(c)
            } else {
                c.toString().toByteArray().forEach {
                    append("%%%02X".format(it.toInt() and BYTE))
                }
            }
        }
    }

    private companion object {
        const val GET = "GET"
        const val OK = 200
        const val BYTE = 0xFF
        val BACKSLASH = Char(92)
        val PRINTABLE = 0x21..0x7E
        const val UNSAFE = "\"<>^`{|}"
        val ATTRIBUTE = Regex(
            "\\b(?:src|href|srcset|poster|data|background|action|formaction)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            RegexOption.IGNORE_CASE,
        )
        val CSS_URL = Regex("url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^)\\s]*))\\s*\\)", RegexOption.IGNORE_CASE)
        val IMPORT = Regex("@import\\s+(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    }
}
