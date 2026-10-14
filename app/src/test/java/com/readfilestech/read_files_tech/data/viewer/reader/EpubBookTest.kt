package com.readfilestech.read_files_tech.data.viewer.reader

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.zip.Forged
import com.readfilestech.read_files_tech.data.viewer.zip.ZipForge
import com.readfilestech.read_files_tech.data.viewer.zip.jdkZip
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * EPUB books (`ReaderService.readEpub` of the 2.15.3, which had no Dart test), written here entry by entry:
 * what a real book holds, and what a hostile one does to the 2.15.3 (inventory D22).
 */
class EpubBookTest {
    @TempDir
    lateinit var dir: File

    private var made = 0

    /** A book of [files] (name to content), its container naming [opf]. */
    private fun book(files: Map<String, String>, opf: String = OPF, container: Boolean = true): File {
        val entries = buildList {
            add("mimetype" to "application/epub+zip".toByteArray())
            if (container) add(CONTAINER_NAME to container(opf).toByteArray())
            files.forEach { (name, content) -> add(name to content.toByteArray()) }
        }
        return jdkZip(File(dir, "book${made++}.epub"), entries)
    }

    private fun open(file: File, limits: EpubLimits = EpubLimits()): EpubBook =
        runBlocking { EpubBook.open(StorageFileSource(file), limits) }

    private fun texts(book: EpubBook, index: Int): List<String> {
        val blocks = runBlocking { book.chapter(index) }.blocks
        return (0 until blocks.size).map(blocks::text)
    }

    @Test
    fun `an EPUB 2 book reads its spine in order, titled by its NCX, its chapters' DTD and entities read`() {
        val file = book(
            mapOf(
                OPF to opf(listOf("c1" to "text/c1.xhtml", "c2" to "text/c2.xhtml", "ncx" to "toc.ncx"), listOf("c2", "c1"), toc = "ncx"),
                "OEBPS/text/c1.xhtml" to xhtml("<h1>Heading one</h1><p>caf&eacute; &amp; th&eacute;</p>"),
                "OEBPS/text/c2.xhtml" to xhtml("<p>First in the spine</p>"),
                "OEBPS/toc.ncx" to ncx("text/c1.xhtml#start" to "Chapter One", "text/c2.xhtml" to "Opening"),
            ),
        )
        open(file).use { book ->
            assertThat(book.chapterCount).isEqualTo(2)
            assertThat((0 until 2).map(book::title)).containsExactly("Opening", "Chapter One").inOrder()
            assertThat(texts(book, 0)).containsExactly("First in the spine")
            assertThat(texts(book, 1)).containsExactly("Heading one", "café & thé").inOrder()
            // The table of contents' title, not the heading.
            assertThat(runBlocking { book.chapter(1) }.title).isEqualTo("Chapter One")
        }
    }

    @Test
    fun `an EPUB 3 book is titled by its nav, before its NCX`() {
        val nav = xhtml(
            "<nav epub:type=\"landmarks\"><ol><li><a href=\"c1.xhtml\">Landmark</a></li></ol></nav>" +
                "<nav epub:type=\"toc\"><ol><li><a href=\"c1.xhtml\"><span>The </span>   <em>first</em></a>" +
                "<ol><li><a href=\"c1.xhtml#s2\">Section</a></li></ol></li><li><a href=\"c2.xhtml\">Second</a></li></ol></nav>",
        )
        val file = book(
            mapOf(
                OPF to opf(
                    listOf("c1" to "c1.xhtml", "c2" to "c2.xhtml", "nav" to "nav.xhtml", "ncx" to "toc.ncx"),
                    listOf("c1", "c2"),
                    toc = "ncx",
                    navId = "nav",
                ),
                "OEBPS/c1.xhtml" to xhtml("<p>one</p>"),
                "OEBPS/c2.xhtml" to xhtml("<p>two</p>"),
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/toc.ncx" to ncx("c1.xhtml" to "From the NCX"),
            ),
        )
        open(file).use { book -> assertThat((0 until 2).map(book::title)).containsExactly("The first", "Second").inOrder() }
    }

    /** The 2.15.3's titles: the first h1 or h2, else the file's name — found here once the chapter is read. */
    @Test
    fun `without a table of contents a chapter is titled by its first heading once read, else by its file's name`() {
        val file = book(
            mapOf(
                OPF to opf(listOf("a" to "Chapter%20one.xhtml", "b" to "notes.xhtml"), listOf("a", "b")),
                "OEBPS/Chapter one.xhtml" to xhtml("<h3>Small</h3><h2>The heading</h2><h1>Later</h1>"),
                "OEBPS/notes.xhtml" to xhtml("<p>No heading</p>"),
            ),
        )
        open(file).use { book ->
            assertThat(book.title(0)).isEqualTo("Chapter one")
            assertThat(runBlocking { book.chapter(0) }.title).isEqualTo("The heading")
            assertThat(book.title(0)).isEqualTo("The heading")
            assertThat(runBlocking { book.chapter(1) }.title).isEqualTo("notes")
        }
    }

    /** Inventory D22: the 2.15.3 decompressed a chapter again each time the spine named it, all at once. */
    @Test
    fun `a file the spine names again, by one id or another, is one chapter, read once`() {
        val file = book(
            mapOf(
                OPF to opf(
                    listOf("c1" to "c1.xhtml", "same" to "./c1.xhtml", "c2" to "c2.xhtml"),
                    List(SPINE_REPEATS) { "c1" } + "same" + "c2" + "c1",
                ),
                "OEBPS/c1.xhtml" to xhtml("<p>one</p>"),
                "OEBPS/c2.xhtml" to xhtml("<p>two</p>"),
            ),
        )
        open(file).use { book ->
            assertThat(book.chapterCount).isEqualTo(2)
            val first = runBlocking { book.chapter(0) }
            assertThat(runBlocking { book.chapter(0) }).isSameInstanceAs(first)
        }
    }

    @Test
    fun `a reference resolves against its own file's folder, never out of the archive`() {
        assertThat(EpubPaths.resolve("OEBPS/package", "../text/c1.xhtml#p3")).isEqualTo("OEBPS/text/c1.xhtml")
        assertThat(EpubPaths.resolve("OEBPS", "./a/./b%20c.xhtml?x=1")).isEqualTo("OEBPS/a/b c.xhtml")
        assertThat(EpubPaths.resolve("", "OEBPS/content.opf")).isEqualTo("OEBPS/content.opf")
        listOf(
            "../../c.xhtml", "%2E%2E/%2e%2e/c.xhtml", "..\\..\\c.xhtml", "/OEBPS/c.xhtml", "//host/c.xhtml", "file:///etc/passwd",
            "C:/c.xhtml", "http://example.org/c.xhtml", "a%00.xhtml", "bad%zz.xhtml", "", "#only", "..",
        ).forEach { assertThat(EpubPaths.resolve("OEBPS", it)).isNull() }
    }

    /** The 2.15.3 kept only the chapters in the OPF's own folder: this book showed nothing. */
    @Test
    fun `a book whose package is in a folder beside its chapters reads them, and none from out of the archive`() {
        val hrefs = listOf(
            "ok" to "../text/c1.xhtml",
            "up" to "../../../outside.xhtml",
            "escaped" to "%2E%2E/%2E%2E/%2E%2E/outside.xhtml",
            "absolute" to "/OEBPS/text/c1.xhtml",
            "scheme" to "file:///OEBPS/text/c1.xhtml",
            "missing" to "../text/none.xhtml",
            "picture" to "../images/p.png",
        )
        val file = book(
            mapOf(
                "OEBPS/package/book.opf" to opf(hrefs, hrefs.map { it.first }),
                "OEBPS/text/c1.xhtml" to xhtml("<p>found</p>"),
                "OEBPS/images/p.png" to "not a chapter",
                "outside.xhtml" to xhtml("<p>outside</p>"),
            ),
            opf = "OEBPS/package/book.opf",
        )
        open(file).use { book ->
            assertThat(book.chapterCount).isEqualTo(1)
            assertThat(texts(book, 0)).containsExactly("found")
        }
    }

    @Test
    fun `what is no book is unreadable`() {
        val chapter = mapOf("OEBPS/c1.xhtml" to xhtml("<p>one</p>"))
        val doctype = "<?xml version=\"1.0\"?><!DOCTYPE package [<!ENTITY x \"y\">]>" + opf(listOf("c1" to "c1.xhtml"), listOf("c1"))
        listOf(
            book(chapter + (OPF to opf(listOf("c1" to "c1.xhtml"), listOf("c1"))), container = false),
            book(chapter, opf = "OEBPS/none.opf"),
            book(chapter + (OPF to doctype)),
            book(chapter + (OPF to "<package><manifest><item id=")),
            // Two container.xml: which one a reader takes is chance, so none is taken.
            File(dir, "two.epub").apply {
                val entries = listOf(CONTAINER_NAME to container(OPF), CONTAINER_NAME to container(OPF)) + chapter.toList() +
                    (OPF to opf(listOf("c1" to "c1.xhtml"), listOf("c1")))
                writeBytes(ZipForge.write(entries.map { (name, content) -> Forged(name, content.toByteArray()) }))
            },
            File(dir, "random.epub").apply { writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() }) },
            File(dir, "empty.epub").apply { writeBytes(ByteArray(0)) },
        ).forEach { file -> assertThrows<UnreadableFileException>(file.name) { open(file) } }
    }

    @Test
    fun `a chapter over its cap is refused before a byte is inflated, the book and its other chapters read`() {
        val file = book(
            mapOf(
                OPF to opf(listOf("big" to "big.xhtml", "small" to "small.xhtml"), listOf("big", "small")),
                "OEBPS/big.xhtml" to xhtml("<p>" + "a".repeat(2_000) + "</p>"),
                "OEBPS/small.xhtml" to xhtml("<p>small</p>"),
            ),
        )
        open(file, EpubLimits(chapterBytes = 1_000)).use { book ->
            assertThat(assertThrows<FileTooLargeException> { runBlocking { book.chapter(0) } }.capBytes).isEqualTo(1_000)
            assertThat(texts(book, 1)).containsExactly("small")
        }
    }

    /** Inventory D22: no cumulative cap. Here, all a book's parts decompress to at most its budget; a chapter kept costs none. */
    @Test
    fun `what a book decompresses is bounded in all, a chapter kept read once`() {
        val chapter = xhtml("<p>" + "x".repeat(400) + "</p>")
        val opf = opf((1..4).map { "c$it" to "c$it.xhtml" }, (1..4).map { "c$it" })
        val file = book((1..4).associate { "OEBPS/c$it.xhtml" to chapter } + (OPF to opf))
        // The container, the package, and two chapters: not a third.
        open(file, EpubLimits(decompressedBytes = container(OPF).length + opf.length + 2L * chapter.length)).use { book ->
            texts(book, 0)
            texts(book, 1)
            repeat(3) { texts(book, 0) }
            assertThrows<FileTooLargeException> { texts(book, 2) }
        }
    }

    @Test
    fun `a manifest or a spine past the items cap is too many items`() {
        val many = (1..11).map { "c$it" to "c$it.xhtml" }
        val manifest = book(mapOf(OPF to opf(many, listOf("c1"))))
        assertThat(assertThrows<TooManyItemsException> { open(manifest, EpubLimits(items = 10)) }.limit).isEqualTo(10)
        val spine = book(mapOf(OPF to opf(listOf("c1" to "c1.xhtml"), (1..11).map { "c$it" })))
        assertThrows<TooManyItemsException> { open(spine, EpubLimits(items = 10)) }
        // The same idref again and again is one item.
        val oneId = opf(listOf("c1" to "c1.xhtml"), List(SPINE_REPEATS) { "c1" })
        open(book(mapOf(OPF to oneId, "OEBPS/c1.xhtml" to xhtml(""))), EpubLimits(items = 10)).close()
    }

    @Test
    fun `a table of contents past its cap gives the titles read so far, and the book opens`() {
        val points = (1..3).map { "c$it.xhtml" to "Title $it" } + List(2_000) { "c3.xhtml" to "padding $it" }
        val file = book(
            (1..3).associate { "OEBPS/c$it.xhtml" to xhtml("<h1>Heading $it</h1>") } +
                (OPF to opf((1..3).map { "c$it" to "c$it.xhtml" } + ("ncx" to "toc.ncx"), (1..3).map { "c$it" }, toc = "ncx")) +
                ("OEBPS/toc.ncx" to ncx(*points.toTypedArray())),
        )
        open(file, EpubLimits(packageBytes = 4_096)).use { book ->
            assertThat(book.title(0)).isEqualTo("Title 1")
            assertThat(texts(book, 0)).containsExactly("Heading 1")
        }
        // Cut short: the titles before the cut stay.
        val damaged = book(
            mapOf(
                OPF to opf(listOf("c1" to "c1.xhtml", "ncx" to "toc.ncx"), listOf("c1"), toc = "ncx"),
                "OEBPS/c1.xhtml" to xhtml("<p>one</p>"),
                "OEBPS/toc.ncx" to "<ncx><navMap><navPoint><navLabel><text>Kept</text></navLabel><content src=\"c1.xhtml\"/>",
            ),
        )
        open(damaged).use { book -> assertThat(book.title(0)).isEqualTo("Kept") }
    }

    @Test
    fun `the text kept is bounded, the chapter read least recently dropped and read again when it comes back`() {
        val chapters = (1..3).associate { "OEBPS/c$it.xhtml" to xhtml("<p>" + "y".repeat(300) + "</p>") }
        val file = book(chapters + (OPF to opf((1..3).map { "c$it" to "c$it.xhtml" }, (1..3).map { "c$it" })))
        open(file, EpubLimits(textChars = 700)).use { book ->
            val first = runBlocking { book.chapter(0) }
            runBlocking { book.chapter(1) }
            assertThat(runBlocking { book.chapter(1) }).isSameInstanceAs(runBlocking { book.chapter(1) })
            runBlocking { book.chapter(2) }
            val again = runBlocking { book.chapter(0) }
            assertThat(again).isNotSameInstanceAs(first)
            assertThat(again.blocks.text(0)).isEqualTo(first.blocks.text(0))
        }
    }

    @Test
    fun `a book under DRM is a format not opened, obfuscated fonts are no DRM`() {
        val chapter = mapOf(OPF to opf(listOf("c1" to "c1.xhtml"), listOf("c1")), "OEBPS/c1.xhtml" to xhtml("<p>one</p>"))
        val protected = book(chapter + (ENCRYPTION_NAME to encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc")))
        assertThrows<UnsupportedFormatException> { open(protected) }
        val fonts = book(chapter + (ENCRYPTION_NAME to encryption("http://www.idpf.org/2008/embedding")))
        open(fonts).use { book -> assertThat(texts(book, 0)).containsExactly("one") }
    }

    private companion object {
        const val CONTAINER_NAME = "META-INF/container.xml"
        const val ENCRYPTION_NAME = "META-INF/encryption.xml"
        const val OPF = "OEBPS/content.opf"
        const val SPINE_REPEATS = 100_000

        fun container(opf: String) = "<?xml version=\"1.0\"?><container version=\"1.0\" " +
            "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>" +
            "<rootfile full-path=\"$opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"

        /** An OPF of [items] (id to href; `.ncx` an NCX, [navId] the nav), its spine [spine]. */
        fun opf(items: List<Pair<String, String>>, spine: List<String>, toc: String? = null, navId: String? = null): String =
            buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">")
                append("<metadata/><manifest>")
                items.forEach { (id, href) ->
                    val type = if (href.endsWith(
                            ".ncx"
                        )
                    ) {
                        "application/x-dtbncx+xml"
                    } else if (href.endsWith(".png")) "image/png" else "application/xhtml+xml"
                    val properties = if (id == navId) " properties=\"nav\"" else ""
                    append("<item id=\"$id\" href=\"$href\" media-type=\"$type\"$properties/>")
                }
                append("</manifest><spine").append(toc?.let { " toc=\"$it\"" }.orEmpty()).append(">")
                spine.forEach { append("<itemref idref=\"$it\"/>") }
                append("</spine></package>")
            }

        /** An XHTML 1.1 chapter, as EPUB 2 writes one: a DOCTYPE naming its DTD. */
        fun xhtml(body: String) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" " +
            "\"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
            "xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>Not shown</title></head><body>$body</body></html>"

        /** An NCX as EPUB 2 writes one, its DOCTYPE naming the NCX DTD. */
        fun ncx(vararg points: Pair<String, String>) = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" ")
            append(
                "\"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd\"><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">"
            )
            append("<head/><docTitle><text>The book</text></docTitle><navMap>")
            points.forEachIndexed { i, (src, label) ->
                append("<navPoint id=\"p$i\" playOrder=\"$i\"><navLabel><text>$label</text></navLabel>")
                append("<content src=\"$src\"/></navPoint>")
            }
            append("</navMap></ncx>")
        }

        fun encryption(algorithm: String) =
            "<?xml version=\"1.0\"?><encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" " +
                "xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\"><enc:EncryptedData><enc:EncryptionMethod Algorithm=\"$algorithm\"/>" +
                "<enc:CipherData><enc:CipherReference URI=\"OEBPS/c1.xhtml\"/></enc:CipherData></enc:EncryptedData></encryption>"
    }
}
