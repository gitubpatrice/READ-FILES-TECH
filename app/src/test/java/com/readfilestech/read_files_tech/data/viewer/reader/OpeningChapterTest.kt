package com.readfilestech.read_files_tech.data.viewer.reader

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Where a book opens: past a cover or a title page with no text, never far, never past a chapter that fails. */
class OpeningChapterTest {
    /** A book whose chapters are given: null is a chapter that cannot be read; [read] lists those asked for. */
    private class Book(private val texts: List<String?>) : ReaderDocument {
        val read = mutableListOf<Int>()

        override val chapterCount: Int get() = texts.size

        override fun title(index: Int): String = "chapter $index"

        override suspend fun chapter(index: Int): ReaderChapter {
            read += index
            val text = texts[index] ?: throw UnreadableFileException("damaged chapter")
            val blocks = if (text.isEmpty()) {
                ReaderBlocks.EMPTY
            } else {
                ReaderBlocks(text, intArrayOf(text.length), byteArrayOf(BlockKind.PARAGRAPH.ordinal.toByte()), 0, 1)
            }
            return ReaderChapter(title(index), blocks)
        }

        override fun close() = Unit
    }

    private fun opening(book: Book, lookahead: Int = 8) = runBlocking { book.openingChapter(lookahead) }

    @Test
    fun `a book opens on its first chapter with text, past a cover and a title page`() {
        val book = Book(listOf("", "", "Chapter one", "Chapter two"))
        assertThat(opening(book)).isEqualTo(2)
        assertThat(book.read).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `a book whose first chapter has text opens on it, reading nothing more`() {
        val book = Book(listOf("Chapter one", ""))
        assertThat(opening(book)).isEqualTo(0)
        assertThat(book.read).containsExactly(0)
    }

    @Test
    fun `a chapter that cannot be read ends the search there, to say why`() {
        assertThat(opening(Book(listOf("", null, "Chapter one")))).isEqualTo(1)
    }

    @Test
    fun `the search stops at its lookahead, and the book then opens on its first chapter`() {
        val book = Book(listOf("", "", "", "Chapter one"))
        assertThat(opening(book, lookahead = 3)).isEqualTo(0)
        assertThat(book.read).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `a book of no chapter opens on the first`() {
        assertThat(opening(Book(emptyList()))).isEqualTo(0)
    }
}
