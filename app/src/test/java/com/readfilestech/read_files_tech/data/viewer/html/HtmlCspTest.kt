package com.readfilestech.read_files_tech.data.viewer.html

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * `test/html_csp_injection_test.dart` of the 2.15.3, case by case, through what the WebView is served
 * ([HtmlPage.served]). The policy injected is what keeps a hostile page from showing what it should not;
 * if the insertion fails, nothing else in the page holds (the header of HtmlAssets aside).
 *
 * Two cases change, and say why: the policy allows the loader (`'self'`) where it allowed `file:` (D20),
 * and a `>` inside a DOCTYPE's quoted identifier ENDS the DOCTYPE, as the HTML tokenizer says — the
 * 2.15.3's test pinned the opposite, and with it a policy placed in the `<body>`, where Chromium ignores it.
 */
class HtmlCspTest {
    private fun injectCsp(html: String): String =
        runBlocking { HtmlPage.of("page.html", html.toByteArray(Charsets.UTF_8)) }.served().readBytes().toString(Charsets.UTF_8)

    /** Where the policy's tag is in [doc], or -1. */
    private fun cspAt(doc: String) = doc.indexOf("http-equiv=\"Content-Security-Policy\"")

    /** Whether the policy ended up inside an HTML comment (or is missing). */
    private fun cspInsideComment(doc: String): Boolean {
        val at = cspAt(doc)
        val openBefore = if (at < 0) -1 else doc.lastIndexOf("<!--", at)
        val closeBetween = if (openBefore < 0) -1 else doc.indexOf("-->", openBefore)
        return at < 0 || openBefore >= 0 && (closeBetween < 0 || closeBetween > at)
    }

    @Test
    fun `the policy is inserted, outside any comment, in a plain document`() {
        val doc = injectCsp("<html><head><title>x</title></head></html>")
        assertThat(cspAt(doc)).isAtLeast(0)
        assertThat(cspInsideComment(doc)).isFalse()
    }

    @Test
    fun `a false head in a comment does not divert the insertion`() {
        val trap = "<!-- <head> -->\n<head>\n  <img src=\"https://attaquant.example/pixel?open=1\">\n</head>"
        val doc = injectCsp(trap)
        assertThat(cspAt(doc)).isAtLeast(0)
        assertWithMessage("a policy in a comment is not applied").that(cspInsideComment(doc)).isFalse()
        // Before the remote picture, or too late to stop it.
        assertThat(cspAt(doc)).isLessThan(doc.indexOf("attaquant.example"))
    }

    @Test
    fun `the policy always comes before the first remote resource`() {
        for (trap in listOf(
            "<img src=\"https://a.example/p.png\">",
            "<!-- <head> --><img src=\"https://a.example/p.png\">",
            "<HEAD><img src=\"https://a.example/p.png\"></HEAD>",
            "<html><body><img src=\"https://a.example/p.png\"></body></html>",
            "<!doctype html><html><head><img src=\"https://a.example/p.png\">",
            "<script>fetch('https://a.example/x')</script>",
        )) {
            val doc = injectCsp(trap)
            assertWithMessage(trap).that(cspAt(doc)).isAtLeast(0)
            assertWithMessage(trap).that(cspInsideComment(doc)).isFalse()
            assertWithMessage("policy after the resource, so useless: $trap").that(cspAt(doc)).isLessThan(doc.indexOf("a.example"))
        }
    }

    @Test
    fun `a DOCTYPE stays the very first node of the document`() {
        for (doc in listOf(
            injectCsp("<!doctype html><html><head></head></html>"),
            injectCsp("<!DOCTYPE HTML><html><head></head></html>"),
            injectCsp("\n  <!doctype html>\n<html></html>"),
        )) {
            val doctype = doc.lowercase().indexOf("<!doctype")
            assertThat(doctype).isAtLeast(0)
            assertWithMessage("before the DOCTYPE, the page is drawn in quirks mode").that(doctype).isLessThan(cspAt(doc))
            // Nothing but blanks before the DOCTYPE.
            assertThat(doc.substring(0, doctype).trim()).isEmpty()
        }
    }

    /** Changed: the folder through the loader (`'self'`), never `file:` (D20) nor a FileProvider's `content:`. */
    @Test
    fun `the policy cuts the network and keeps the page's folder, not the phone's files`() {
        val doc = injectCsp("<html></html>")
        assertThat(doc).contains("default-src 'none'")
        assertThat(doc).contains("connect-src 'none'")
        assertThat(doc).contains("img-src 'self'")
        assertThat(doc).contains("style-src 'self'")
        assertThat(doc).contains("font-src 'self'")
        for (scheme in listOf("https:", "http:", "file:", "content:", "blob:")) {
            assertWithMessage(scheme).that(doc).doesNotContain(scheme)
        }
    }

    /**
     * Inverted: the HTML tokenizer ends a DOCTYPE at its first `>`, inside a quoted identifier too
     * ("abrupt-doctype-public-identifier"). The 2.15.3 placed its policy after `b" "c>d">` — text that opened
     * the `<body>`, where Chromium ignores a policy — and its test asserted it.
     */
    @Test
    fun `a greater-than sign in a public identifier ends the DOCTYPE, and the policy right there`() {
        val trap = "<!DOCTYPE html PUBLIC \"a>b\" \"c>d\"><html><head><img src=\"https://a.example/p.png\"></head></html>"
        val doc = injectCsp(trap)
        assertThat(cspAt(doc)).isLessThan(doc.indexOf("b\" \"c>d\">"))
        assertThat(doc).startsWith("<!DOCTYPE html PUBLIC \"a><meta ")
        assertThat(cspAt(doc)).isLessThan(doc.indexOf("a.example"))
        // A bogus DOCTYPE, quotes and all, ends at its first `>` as well.
        assertThat(injectCsp("<!DOCTYPE html foo=\">\"><img src=\"https://a.example/p.png\">")).startsWith("<!DOCTYPE html foo=\"><meta ")
    }

    @Test
    fun `frames are refused, not only narrowed`() {
        assertThat(injectCsp("<html></html>")).contains("frame-src 'none'")
    }

    @Test
    fun `an empty document still gets the policy`() {
        assertThat(cspAt(injectCsp(""))).isAtLeast(0)
    }

    @Nested
    inner class AttemptsToDefeatTheInjection {
        @Test
        fun `a document carrying its own permissive policy stays held`() {
            // Policies add up: the browser applies their intersection. Ours must be there, and first.
            val trap = "<!doctype html><html><head>" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src * 'unsafe-inline'\">" +
                "<img src=\"https://attaquant.example/p.png\"></head></html>"
            val doc = injectCsp(trap)
            val ours = doc.indexOf("connect-src 'none'")
            assertWithMessage("ours must be there").that(ours).isAtLeast(0)
            assertWithMessage("ours must come before the document's").that(ours).isLessThan(doc.indexOf("default-src *"))
            assertThat(ours).isLessThan(doc.indexOf("attaquant.example"))
        }

        @Test
        fun `a DOCTYPE never closed does not lose the policy`() {
            val doc = injectCsp("<!doctype html <img src=\"https://attaquant.example/p.png\">")
            assertWithMessage("the policy must open the document").that(doc).startsWith("<meta http-equiv=")
            assertThat(cspAt(doc)).isLessThan(doc.indexOf("attaquant.example"))
        }

        @Test
        fun `the case of the DOCTYPE changes nothing`() {
            for (variant in listOf("<!DOCTYPE html>", "<!DoCtYpE html>")) {
                val doc = injectCsp("$variant<img src=\"https://attaquant.example/p.png\">")
                assertWithMessage(variant).that(cspAt(doc)).isAtLeast(0)
                assertWithMessage(variant).that(cspAt(doc)).isLessThan(doc.indexOf("attaquant.example"))
            }
        }

        @Test
        fun `blanks and line breaks before the DOCTYPE do not get in the way`() {
            val doc = injectCsp("\n\n   \t<!doctype html><img src=\"https://attaquant.example/p.png\">")
            assertThat(cspAt(doc)).isAtLeast(0)
            assertThat(cspAt(doc)).isLessThan(doc.indexOf("attaquant.example"))
            assertThat(cspInsideComment(doc)).isFalse()
        }

        @Test
        fun `a remote resource before the DOCTYPE stays covered`() {
            val doc = injectCsp("<img src=\"https://attaquant.example/p.png\"><!doctype html>")
            assertThat(cspAt(doc)).isLessThan(doc.indexOf("attaquant.example"))
        }

        @Test
        fun `the policy allows no remote host, no file and no script, anywhere`() {
            for (directive in listOf(
                "default-src", "img-src", "media-src", "style-src", "font-src", "script-src",
                "frame-src", "form-action", "connect-src", "base-uri",
            )) {
                val at = HtmlCsp.POLICY.indexOf("$directive ")
                assertWithMessage("$directive missing").that(at).isAtLeast(0)
                val end = HtmlCsp.POLICY.indexOf(';', at)
                val value = if (end < 0) HtmlCsp.POLICY.substring(at) else HtmlCsp.POLICY.substring(at, end)
                for (door in listOf("http", "*", "ws", "file", "content", "unsafe-eval")) {
                    assertWithMessage("$directive opens a door: $value").that(value).doesNotContain(door)
                }
            }
            assertThat(HtmlCsp.POLICY).contains("script-src 'none'")
        }
    }
}
