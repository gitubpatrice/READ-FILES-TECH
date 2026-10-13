package com.readfilestech.read_files_tech.data.viewer.html

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * Where a navigation of the page leads. The 2.15.3 let its WebView follow a link to a page of the folder
 * by `file://`, where the policy injected into the first page did not exist; here the WebView never
 * leaves the page, and only what the user tapped leads anywhere.
 */
class HtmlLinksTest {
    private val base = "https://${HtmlLinks.HOST}${HtmlLinks.PREFIX}"

    private fun tap(url: String, name: String = "page.html") = HtmlLinks.of(url, name, tapped = true)

    @Test
    fun `the page is served under the loader, its name one encoded segment`() {
        assertThat(HtmlLinks.pageUrl("page.html")).isEqualTo("${base}page.html")
        assertThat(HtmlLinks.pageUrl("Été #1?.html")).isEqualTo("$base%C3%89t%C3%A9%20%231%3F.html")
        assertThat(HtmlLinks.decode("%C3%89t%C3%A9%20%231%3F.html")).isEqualTo("Été #1?.html")
    }

    @Test
    fun `nothing nobody tapped leads anywhere - a refresh, a redirect`() {
        for (url in listOf("${base}page.html", "${base}page.html#top", "${base}other.html", "https://example.org/")) {
            assertWithMessage(url).that(HtmlLinks.of(url, "page.html", tapped = false)).isEqualTo(HtmlLink.Refused)
        }
    }

    @Test
    fun `a place in the page scrolls, the page itself again does nothing`() {
        assertThat(tap("${base}page.html#chapter-2")).isEqualTo(HtmlLink.Scroll)
        assertThat(tap("${base}page.html")).isEqualTo(HtmlLink.Refused)
        assertThat(tap("${base}page.html?again=1#x")).isEqualTo(HtmlLink.Refused)
    }

    @Test
    fun `a file of the folder opens as the explorer opens it`() {
        assertThat(tap("${base}report.pdf")).isEqualTo(HtmlLink.Sibling("report.pdf"))
        assertThat(tap("${base}page_files/next%20page.html#x")).isEqualTo(HtmlLink.Sibling("page_files/next page.html"))
        // The folder decides what is in it (HtmlFolder): the link only decodes.
        assertThat(tap("$base..%2F..%2Fdata")).isEqualTo(HtmlLink.Sibling("../../data"))
    }

    @Test
    fun `web addresses go to the user's apps, every other scheme nowhere`() {
        assertThat(tap("https://example.org/a?b#c")).isEqualTo(HtmlLink.Web("https://example.org/a?b#c"))
        assertThat(tap("http://example.org/")).isEqualTo(HtmlLink.Web("http://example.org/"))
        assertThat(tap("mailto:someone@example.org")).isEqualTo(HtmlLink.Web("mailto:someone@example.org"))
        for (url in listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "file:///data/data/com.readfilestech.read_files_tech/shared_prefs/settings.xml",
            "content://com.readfilestech.read_files_tech.fileprovider/root/data/data/x",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "data:text/html,<p>",
            "about:blank",
            "tel:123",
            "no scheme at all",
        )) {
            assertWithMessage(url).that(tap(url)).isEqualTo(HtmlLink.Refused)
        }
    }

    /** The loader's domain exists on the internet: a path of it outside the folder is never handed to a browser. */
    @Test
    fun `the loader's domain outside the folder is refused, not opened in a browser`() {
        for (url in listOf(
            "https://${HtmlLinks.HOST}/data/data/com.readfilestech.read_files_tech/shared_prefs/settings.xml",
            "http://${HtmlLinks.HOST}${HtmlLinks.PREFIX}page.html",
            "https://user@${HtmlLinks.HOST}${HtmlLinks.PREFIX}report.pdf",
            "https://${HtmlLinks.HOST}:443${HtmlLinks.PREFIX}report.pdf",
        )) {
            assertWithMessage(url).that(tap(url)).isEqualTo(HtmlLink.Refused)
        }
    }

    @Test
    fun `a path that does not decode is refused`() {
        for (path in listOf("%", "%4", "%zz.html", "%-1.html", "%C3.html", "%FF%FE.html")) {
            assertWithMessage(path).that(tap("$base$path")).isEqualTo(HtmlLink.Refused)
        }
    }
}
