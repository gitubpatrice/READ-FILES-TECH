package com.readfilestech.read_files_tech.ui.viewer.reader

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.reader.ReaderPage
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The reader's route: an EPUB by its extension, a page by the HTML viewer's reading mode — `.html` keeps one viewer. */
class ReaderOpeningTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `an epub opens in the reader, a page reaches it only by the HTML viewer's reading mode`() {
        assertThat(ViewerKind.forExtension("epub")).isEqualTo(ViewerKind.READER)
        assertThat(ViewerKind.forExtension("html")).isEqualTo(ViewerKind.HTML)
        assertThat(Routes.viewer(ViewerKind.READER, "/d/page.html")).isEqualTo("viewer/READER?path=%2Fd%2Fpage.html")
    }

    @Test
    fun `the reader opens a page of the HTML viewer's extensions in reading mode, anything else as a book`() {
        val page = File(dir, "Page.HTM").apply { writeText("<p>text</p>") }
        runBlocking { ReaderOpening.open(StorageFileSource(page)) }.use { document ->
            assertThat(document).isInstanceOf(ReaderPage::class.java)
            assertThat(document.title(0)).isEqualTo("Page.HTM")
        }
        // The same text under a book's name is no book: never read as a page.
        val notBook = File(dir, "page.epub").apply { writeText("<p>text</p>") }
        assertThrows<UnreadableFileException> { runBlocking { ReaderOpening.open(StorageFileSource(notBook)) } }
    }
}
