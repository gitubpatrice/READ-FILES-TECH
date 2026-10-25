package com.readfilestech.read_files_tech.data.viewer.markdown

import java.util.Locale

/**
 * Which links of a Markdown file a tap opens: those to the web and to an e-mail address, in another app.
 * Every other one is refused — `javascript:`, `file:` (it would name the app's own files), `content:`,
 * `intent:`, and a link relative to the document, which names a file beside it. The 2.15.3 opened none: it
 * gave gpt_markdown no `onLinkTap` (`md_viewer_screen.dart:207`), and a tap on a link did nothing.
 */
object MarkdownLinks {
    private val OPENED = setOf("http", "https", "mailto")

    /** [destination] when a tap may open it — its scheme, before the first colon, is one of these — else null. */
    fun opened(destination: String): String? =
        destination.takeIf { it.substringBefore(':', missingDelimiterValue = "").lowercase(Locale.ROOT) in OPENED }
}
