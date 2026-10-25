package com.readfilestech.read_files_tech.data.viewer.reader

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The reader on books made by real software — Project Gutenberg's Ebookmaker (`tools/corpus/epub/`, see its
 * `corpus.json`): an EPUB 2 with its NCX and the XHTML 1.1 DOCTYPE, an EPUB 3 with its nav — and the HTML
 * viewer's reading mode on a page CPython's pydoc wrote (`tools/corpus/html/`).
 */
class ReaderCorpusTest {
    @TempDir
    lateinit var dir: File

    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus")

    private fun book(name: String): EpubBook {
        val file = File(corpus, "epub/$name")
        check(file.isFile) { "missing corpus file: $file" }
        return runBlocking { EpubBook.open(StorageFileSource(file)) }
    }

    private fun ReaderBlocks.texts(): List<String> = (0 until size).map(::text)

    /** Ebookmaker writes the boilerplate in `<div>`s and the author under the title in one: the 2.15.3 dropped both. */
    @Test
    fun `The Raven, EPUB 2 - its NCX titles, its cover empty, and the text outside paragraphs read`() {
        book("gutenberg-1065-raven-epub2.epub").use { book ->
            assertThat(book.chapterCount).isEqualTo(3)
            assertThat(book.title(1)).isEqualTo("The Raven")
            assertThat(book.title(2)).isEqualTo("THE FULL PROJECT GUTENBERG™ LICENSE")
            assertThat(runBlocking { book.chapter(0) }.blocks.size).isEqualTo(0)
            val blocks = runBlocking { book.chapter(1) }.blocks
            val texts = blocks.texts()
            assertThat(texts).containsAtLeast(
                "The Project Gutenberg eBook of The Raven",
                "Title: The Raven",
                "Release date: October 1, 1997 [eBook #1065]\nMost recently updated: September 30, 2026",
                "*** START OF THE PROJECT GUTENBERG EBOOK THE RAVEN ***",
                "The Raven",
                "by Edgar Allan Poe",
            ).inOrder()
            assertThat(texts.first { it.startsWith("This eBook is for the use of anyone") }).endsWith("before using this eBook.")
            assertThat(blocks.kind(texts.lastIndexOf("The Raven"))).isEqualTo(BlockKind.HEADING_1)
        }
    }

    @Test
    fun `Candide, EPUB 3 - every chapter of its spine, titled by its nav, in French`() {
        book("gutenberg-4650-candide-epub3.epub").use { book ->
            assertThat(book.chapterCount).isEqualTo(CANDIDE_SPINE)
            val first = (0 until book.chapterCount).first { book.title(it).startsWith("CHAPITRE I.") }
            assertThat(book.title(first))
                .isEqualTo("CHAPITRE I. Comment Candide fut élevé dans un beau château, et comment il fut chassé d’icelui.")
            val blocks = runBlocking { book.chapter(first) }.blocks
            assertThat(blocks.kind(0)).isEqualTo(BlockKind.HEADING_2)
            assertThat(
                blocks.text(0)
            ).isEqualTo("CHAPITRE I.\nComment Candide fut élevé dans un beau château, et comment il fut chassé d’icelui.")
            assertThat(
                blocks.text(1)
            ).startsWith("Il y avait en Vestphalie, dans le château de M. le baron de Thunder-ten-tronckh, un jeune garçon")
        }
    }

    @Test
    fun `Alice, EPUB 2 - its chapters titled by its NCX`() {
        book("gutenberg-11-alice-epub2.epub").use { book ->
            assertThat(book.chapterCount).isEqualTo(ALICE_SPINE)
            assertThat((0 until book.chapterCount).map(book::title)).containsAtLeast(
                "CHAPTER I. Down the Rabbit-Hole",
                "CHAPTER II. The Pool of Tears",
            ).inOrder()
        }
    }

    /** The 2.15.3's reading mode read the page as strict UTF-8, with no cap, on the UI thread (inventory D22). */
    @Test
    fun `the reading mode of a page pydoc wrote shows its text, without its markup`() {
        val page = runBlocking { ReaderPage.read(StorageFileSource(File(corpus, "html/python-pydoc-utf8.html"))) }
        assertThat(page.chapterCount).isEqualTo(1)
        assertThat(page.title(0)).isEqualTo("python-pydoc-utf8.html")
        val texts = runBlocking { page.chapter(0) }.blocks.texts().map { it.replace(NO_BREAK_SPACE, ' ') }
        assertThat(texts).contains(
            "Rapport d'été — Année 2026.\n \nÉté à la française : éàèçùôîïëœ — c'était parfait ! Ça coûte 12 € ; « guillemets ».\n" +
                "Voir https://example.org/rapport et le fichier rapport_files/annexe.png.",
        )
        assertThat(texts.joinToString()).doesNotContain("<")
    }

    @Test
    fun `a page over the reading mode's cap is refused`() {
        val page = File(dir, "page.html").apply { writeText("<p>" + "a".repeat(2_000) + "</p>") }
        val refused = assertThrows<FileTooLargeException> {
            // The text kept would fit: the page's bytes are what is refused.
            runBlocking { ReaderPage.read(StorageFileSource(page), 1_000, 1_000_000) }
        }
        assertThat(refused.capBytes).isEqualTo(1_000)
    }

    private companion object {
        const val CANDIDE_SPINE = 36
        const val ALICE_SPINE = 14
        const val NO_BREAK_SPACE = '\u00A0'
    }
}
