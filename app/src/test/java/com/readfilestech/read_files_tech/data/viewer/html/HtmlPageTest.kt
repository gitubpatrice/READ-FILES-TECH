package com.readfilestech.read_files_tech.data.viewer.html

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.SourceFile
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

class HtmlPageTest {
    @TempDir
    lateinit var dir: File

    private fun page(bytes: ByteArray) = runBlocking { HtmlPage.of("page.html", bytes) }

    private fun charsetOf(html: String, charset: String = "windows-1252") = page(html.toByteArray(charset(charset))).charset

    /**
     * The 2.15.3 read a page as strict UTF-8 (`readAsString`): a Windows-1252 page, Word's "Save as Web
     * Page" in French, was refused whole. The WebView is now told the page's encoding, as a browser finds it.
     */
    @Test
    fun `the encoding a page declares, else UTF-8 when valid, else Windows-1252`() {
        // ASCII text: what the bytes alone would say is UTF-8, so only the declaration says Windows-1252.
        assertThat(charsetOf("<meta charset=\"windows-1252\"><p>Summer</p>")).isEqualTo("windows-1252")
        // Word: http-equiv with bare values, the charset inside `content`.
        assertThat(
            charsetOf("<html><head><meta http-equiv=Content-Type content=\"text/html; charset=windows-1252\"><p>a")
        ).isEqualTo("windows-1252")
        assertThat(charsetOf("<META HTTP-EQUIV='content-type' CONTENT='text/html;charset=ISO-8859-15'>é")).isEqualTo("ISO-8859-15")
        assertThat(charsetOf("<meta charset=Shift_JIS>a")).isEqualTo("Shift_JIS")
        // None declared: what the bytes are.
        assertThat(charsetOf("<p>Été</p>", "UTF-8")).isEqualTo("UTF-8")
        assertThat(charsetOf("<p>Été</p>")).isEqualTo("windows-1252")
        assertThat(charsetOf("<p>plain ASCII</p>")).isEqualTo("UTF-8")
    }

    @Test
    fun `a charset in a comment or in another tag's attribute is not the page's`() {
        // A `>` inside: a comment ends at its `-->`, not at the first `>`.
        assertThat(charsetOf("<!-- 1 > 0 <meta charset=\"koi8-r\"> --><p>Été</p>")).isEqualTo("windows-1252")
        assertThat(charsetOf("<div title='<meta charset=\"koi8-r\">'>Été</div>")).isEqualTo("windows-1252")
        assertThat(charsetOf("<meta name=x content=\"charset=koi8-r\"><p>Été</p>")).isEqualTo("windows-1252")
    }

    /** UTF-16 declared without a BOM is UTF-8 (HTML standard); a label nobody knows, or a forged one, is skipped. */
    @Test
    fun `labels that are not usable are skipped`() {
        assertThat(charsetOf("<meta charset=utf-16><p>Été</p>", "UTF-8")).isEqualTo("UTF-8")
        // Word's own label for UTF-16, here on a page that is not.
        assertThat(charsetOf("<meta http-equiv=Content-Type content=\"text/html; charset=unicode\"><p>Été</p>", "UTF-8")).isEqualTo("UTF-8")
        assertThat(charsetOf("<meta charset=\"no-such-charset\"><p>Été</p>")).isEqualTo("windows-1252")
        assertThat(charsetOf("<meta charset=\"utf-8; x=y\"><p>Été</p>")).isEqualTo("windows-1252")
        assertThat(charsetOf("<meta charset=\"koi8-r\"><meta charset=\"utf-8\">")).isEqualTo("KOI8-R")
    }

    /** A BOM wins over everything, and stays the first bytes; the tag goes after it, in the BOM's encoding. */
    @Test
    fun `a page with a BOM keeps it first and gets the tag in its own encoding`() {
        val utf8 = page(BOM_UTF_8 + "<!doctype html><meta charset=windows-1252><p>é".toByteArray(Charsets.UTF_8))
        assertThat(utf8.charset).isEqualTo("UTF-8")
        val served8 = utf8.served().readBytes()
        assertThat(served8.copyOf(BOM_UTF_8.size)).isEqualTo(BOM_UTF_8)
        assertThat(
            String(served8, BOM_UTF_8.size, served8.size - BOM_UTF_8.size, Charsets.UTF_8)
        ).startsWith("<!doctype html>${HtmlCsp.TAG}<meta charset")

        val bom16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val utf16 = page(bom16 + "  <!DOCTYPE html><p>é".toByteArray(Charsets.UTF_16LE))
        assertThat(utf16.charset).isEqualTo("UTF-16LE")
        val served16 = utf16.served().readBytes()
        assertThat(served16.copyOf(2)).isEqualTo(bom16)
        assertThat(String(served16, 2, served16.size - 2, Charsets.UTF_16LE)).isEqualTo("  <!DOCTYPE html>${HtmlCsp.TAG}<p>é")
    }

    /** The page's own bytes are served untouched around the tag: a Windows-1252 `é` stays one byte E9. */
    @Test
    fun `the page is served as its own bytes, the tag inserted`() {
        val bytes = "<!DOCTYPE html><p>Été</p>".toByteArray(charset("windows-1252"))
        val served = page(bytes).served().readBytes()
        val tag = HtmlCsp.TAG.toByteArray(Charsets.US_ASCII)
        assertThat(served).isEqualTo(bytes.copyOf(15) + tag + bytes.copyOfRange(15, bytes.size))
        // Twice: a WebView asks again after a rotation.
        assertThat(page(bytes).let { it.served().readBytes().contentEquals(it.served().readBytes()) }).isTrue()
    }

    /** The source view and the colour bar read the page as the text viewer reads a text. */
    @Test
    fun `the source is the page as text`() {
        val source = page("<p style=\"color:#ff0000\">\r\nÉté</p>".toByteArray(charset("windows-1252"))).source
        assertThat((0 until source.rowCount).map(source::row)).containsExactly("<p style=\"color:#ff0000\">", "Été</p>").inOrder()
    }

    /** The cap of the 2.15.3 (`FileCaps.htmlViewer`, 20 MiB), refused before reading — while reading too. */
    @Test
    fun `a page over the cap is refused`() {
        val file = File(dir, "big.html")
        RandomAccessFile(file, "rw").use { it.setLength(FileCaps.HTML_VIEWER + 1) }
        assertThrows<FileTooLargeException> { runBlocking { HtmlPage.read(StorageFileSource(file)) } }
        val growing = object : ViewerSource by StorageFileSource(file) {
            override fun length() = 10L

            override fun open(cap: Long): InputStream = StorageFileSource(file).open(Long.MAX_VALUE)
        }
        assertThrows<FileTooLargeException> { runBlocking { HtmlPage.read(growing) } }
    }

    /** The loader serves the page by its name, and the files of its folder — only for a file of the storage. */
    @Test
    fun `the page, then its folder's files, and no folder for a source no other app may read`() {
        val page = File(dir, "page.html").apply { writeText("<img src=\"page_files/a.png\">") }
        File(dir, "page_files").mkdir()
        val picture = File(dir, "page_files/a.png").apply { writeBytes(byteArrayOf(1)) }
        File(dir, "page_files/style.css").writeText("p{}")
        val read = runBlocking { HtmlPage.read(StorageFileSource(page)) }

        assertThat(read.name).isEqualTo("page.html")
        assertThat(read.resource("page.html")).isEqualTo(HtmlResource.Page)
        assertThat(read.resource("page_files/a.png")).isEqualTo(HtmlResource.Sibling(picture.toPath().toRealPath().toFile(), "image/png"))
        assertThat((read.resource("page_files/style.css") as HtmlResource.Sibling).mimeType).isEqualTo("text/css")
        assertThat(read.resource("page_files/missing.png")).isNull()

        // The vault's (D-028): no file another app may read, so no folder — the page alone, from memory.
        val vault = object : ViewerSource by StorageFileSource(page) {
            override val shareable: File? = null

            override fun acquireFile(cap: Long): SourceFile = error("never a file")
        }
        val fromVault = runBlocking { HtmlPage.read(vault) }
        assertThat(fromVault.resource("page.html")).isEqualTo(HtmlResource.Page)
        assertWithMessage("a file next to a vault page").that(fromVault.resource("page_files/a.png")).isNull()
    }

    private companion object {
        val BOM_UTF_8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
