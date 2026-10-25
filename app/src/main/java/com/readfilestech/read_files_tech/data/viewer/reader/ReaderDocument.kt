package com.readfilestech.read_files_tech.data.viewer.reader

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.html.HtmlPage
import com.readfilestech.read_files_tech.data.viewer.sheet.TextBudget
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * What the reader shows (`ReaderViewerScreen` of the 2.15.3): a book's chapters ([EpubBook]), or a page
 * alone, in reading mode ([ReaderPage]). Open until [close]: a book reads its chapters from its archive
 * when they are first shown.
 */
interface ReaderDocument : Closeable {
    val chapterCount: Int

    /**
     * The title chapter [index] is listed by: the book's table of contents', else its first heading once it
     * was read, else its file's name — the 2.15.3's, which read every chapter at once to find the heading.
     */
    fun title(index: Int): String

    /** Chapter [index], read the first time it is asked for, off the main thread; cancellable. */
    suspend fun chapter(index: Int): ReaderChapter
}

/** The chapters looked at for one with text when a book is opened: a cover, a title page, a dedication… */
private const val OPENING_LOOKAHEAD = 8

/**
 * The chapter a book opens on: its first with text among the first [lookahead] — a cover or a title page
 * made of images has nothing to read, and every Project Gutenberg book begins with one. A chapter that
 * cannot be read ends the search: it is opened, and says why. None with text: the first.
 */
suspend fun ReaderDocument.openingChapter(lookahead: Int = OPENING_LOOKAHEAD): Int {
    for (index in 0 until minOf(chapterCount, lookahead)) {
        val opensHere = try {
            chapter(index).blocks.size > 0
        } catch (expected: IOException) {
            true
        }
        if (opensHere) return index
    }
    return 0
}

/**
 * A page of the HTML viewer in reading mode (the 2.15.3's "Mode lecture (texte désencombré)",
 * `html_viewer_screen.dart:375-384`): its text without the page's clutter, as the reader's blocks. Read
 * within the HTML viewer's own cap, in the encoding the HTML viewer finds — the 2.15.3 read it with no cap,
 * as strict UTF-8, on the UI thread (inventory D22).
 */
class ReaderPage private constructor(private val page: ReaderChapter) : ReaderDocument {
    override val chapterCount: Int get() = 1

    override fun title(index: Int): String = page.title

    override suspend fun chapter(index: Int): ReaderChapter = page

    override fun close() = Unit

    companion object {
        suspend fun read(source: ViewerSource): ReaderPage = read(source, FileCaps.HTML_VIEWER, FileCaps.READER_TEXT_CHARS)

        internal suspend fun read(source: ViewerSource, cap: Long, textChars: Long): ReaderPage {
            val expected = source.length()
            val (bytes, size) = source.open(cap).use { TextDocument.readAll(it, expected, cap) }
            val encoding = TextEncoding.detect(bytes, size)
            val charset = Charset.forName(HtmlPage.charsetOf(bytes, size, encoding))
            val text = InputStreamReader(ByteArrayInputStream(bytes, encoding.bomLength, size - encoding.bomLength), charset)
            val blocks = BlockReader(ReadingMode.PAGE, TextBudget(textChars)).read(HtmlTokenizer(text))
            return ReaderPage(ReaderChapter(source.name, blocks))
        }
    }
}
