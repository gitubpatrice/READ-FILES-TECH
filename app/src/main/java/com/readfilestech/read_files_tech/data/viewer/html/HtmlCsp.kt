package com.readfilestech.read_files_tech.data.viewer.html

/**
 * The policy a page is shown under, and where its `<meta>` goes (`kHtmlViewerCsp` and `injectCsp` of the
 * 2.15.3's `html_viewer_screen.dart`). The loader also sends it as a header with every answer
 * (HtmlAssets): the header holds before the parser has read a byte, the `<meta>` is the tested layer.
 *
 * `'self'` is the app's loader — the page and the files of its own folder, nothing else (HtmlPage.resource):
 * the 2.15.3 allowed `file:` for pictures, styles and fonts, with no folder at all, so a page could show a
 * private file of the app (inventory D20). No directive names a scheme that leaves the phone, and the
 * scripts are off: the 2.15.3 allowed them for its "enable JavaScript" switch, which is not ported.
 */
object HtmlCsp {
    const val POLICY: String =
        "default-src 'none'; " +
            "img-src 'self' data:; " +
            "media-src 'self' data:; " +
            "style-src 'self' data: 'unsafe-inline'; " +
            "font-src 'self' data:; " +
            "script-src 'none'; " +
            "frame-src 'none'; " +
            "form-action 'none'; " +
            "connect-src 'none'; " +
            "base-uri 'none'"

    const val TAG: String = "<meta http-equiv=\"Content-Security-Policy\" content=\"$POLICY\">"

    /**
     * Where [TAG] goes in [html]: at its very start, where nothing can come before it — the parser moves it
     * into the implied `<head>` itself, whatever follows (the 2.15.3 searched `<head` once, and a
     * `<!-- <head> -->` put the tag inside the comment). Only a DOCTYPE that opens the document stays first,
     * or the page would be drawn in quirks mode: the tag goes right after it.
     *
     * A DOCTYPE ends at its FIRST `>`, quoted or not: the HTML tokenizer ends every DOCTYPE state there,
     * an identifier's quotes included ("abrupt-doctype-public-identifier"). The 2.15.3 skipped quoted `>`:
     * on `<!DOCTYPE html PUBLIC "a>b">` it put the tag after `b">`, text that had already opened the
     * `<body>` — and a policy in a `<meta>` outside the `<head>` is ignored by Chromium. A `<` before any
     * `>` means the DOCTYPE was never closed: the tag goes first, as for a document without one.
     *
     * [html] may be the start of a page only: a DOCTYPE not closed within it is placed first, which is safe.
     */
    fun insertionPoint(html: CharSequence): Int {
        val lead = html.indexOfFirst { it !in HTML_WHITESPACE }
        val doctype = lead >= 0 && html.regionMatches(lead, DOCTYPE, 0, DOCTYPE.length, ignoreCase = true)
        // Whichever comes first after `<!doctype`: only a `>` closes it.
        val end = if (doctype) html.indexOfAny(DOCTYPE_STOPS, lead + 1) else -1
        return if (end >= 0 && html[end] == '>') end + 1 else 0
    }

    private val DOCTYPE_STOPS = charArrayOf('<', '>')

    private const val DOCTYPE = "<!doctype"

    /** What the HTML parser skips before a DOCTYPE: tab, line feed, form feed, carriage return, space. */
    private const val HTML_WHITESPACE = "\t\n\u000C\r "
}
