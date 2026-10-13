package com.readfilestech.read_files_tech.data.viewer.markdown

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument

/** The rendered view of a Markdown file, or why it has none. */
sealed interface MarkdownRendering {
    data class Rendered(val blocks: List<MarkdownBlock>) : MarkdownRendering

    /** Longer than [capBytes]. */
    data class TooLong(val capBytes: Long) : MarkdownRendering

    /** Tables that could hold more than [limit] cells. */
    data class TooManyCells(val limit: Int) : MarkdownRendering

    /** More than [limit] brackets open at once in one paragraph. */
    data class TooManyBrackets(val limit: Int) : MarkdownRendering
}

/** A Markdown file: its source, laid out as the text viewer's, and its rendering. */
class MarkdownContent(val source: TextDocument, val rendering: MarkdownRendering)

/**
 * A Markdown file opened for the viewer (`MdViewerScreen` of the 2.15.3), within the text viewer's cap, in
 * the encoding the text viewer finds — the 2.15.3 read UTF-8 strictly, and a file saved by an older Windows
 * tool ended in "Erreur : FormatException", shown as the document. Rendered within its own caps; past
 * them, the source alone, which costs what the text viewer's does.
 */
object MarkdownFile {
    /** [source]'s content; its images from the folder of its file — none for the vault's, which has no file (D-028). */
    suspend fun read(source: ViewerSource): MarkdownContent {
        val (bytes, size) = source.open(FileCaps.TEXT_VIEWER).use {
            TextDocument.readAll(it, source.length(), FileCaps.TEXT_VIEWER)
        }
        return of(bytes, size, MarkdownImages(source.shareable?.parentFile))
    }

    /** The content of the first [size] bytes of [bytes] — kept, not copied. */
    internal suspend fun of(bytes: ByteArray, size: Int = bytes.size, images: MarkdownImages = MarkdownImages(null)): MarkdownContent {
        val document = TextDocument.of(bytes, size)
        if (size > FileCaps.MARKDOWN_RENDERED) return MarkdownContent(document, MarkdownRendering.TooLong(FileCaps.MARKDOWN_RENDERED))
        val text = document.text(0, document.rowCount)
        val rendering = when {
            tableCells(text) > FileCaps.MARKDOWN_TABLE_CELLS -> MarkdownRendering.TooManyCells(FileCaps.MARKDOWN_TABLE_CELLS)
            openBrackets(text) > FileCaps.MARKDOWN_OPEN_BRACKETS -> MarkdownRendering.TooManyBrackets(FileCaps.MARKDOWN_OPEN_BRACKETS)
            else -> MarkdownRendering.Rendered(MarkdownRenderer.render(text, images))
        }
        return MarkdownContent(document, rendering)
    }

    /**
     * The most brackets open at once in one paragraph of [text] — a blank line ends one — escaped or in code,
     * counted all the same. A lone CR ends no line here: counted on, the bound only grows.
     */
    internal fun openBrackets(text: String): Int {
        var open = 0
        var most = 0
        var blank = true
        for (c in text) {
            when (c) {
                '\n' -> {
                    if (blank) open = 0
                    blank = true
                }
                '[' -> {
                    most = maxOf(most, ++open)
                    blank = false
                }
                ']' -> {
                    open = maxOf(0, open - 1)
                    blank = false
                }
                ' ', '\t', '\r' -> Unit
                else -> blank = false
            }
        }
        return most
    }

    /**
     * At least as many cells as the tables of [text] would hold once parsed: for each line that may be a
     * table's delimiter row (`|---|:-:|`, or `---` under a line holding a `|`; in a quote or a list too), its
     * columns times the lines from the one above it until a blank line. GFM pads every row to the header's
     * columns. A delimiter row met inside a table may start another one: the wider of the two counts.
     */
    internal fun tableCells(text: String): Long {
        var cells = 0L
        var previousHasPipe = false
        var columns = 0
        for (line in text.lineSequence()) {
            when {
                line.isBlank() -> columns = 0
                isDelimiterRow(line) && (previousHasPipe || '|' in line) -> {
                    columns = maxOf(columns, line.count { it == '|' } + 1)
                    // The header above and this row.
                    cells += 2L * columns
                }
                columns > 0 -> cells += columns
            }
            previousHasPipe = '|' in line
        }
        return cells
    }

    private fun isDelimiterRow(line: String): Boolean {
        val row = line.trimStart { it in CONTAINER_PREFIX }
        return '-' in row && row.all { it in DELIMITER_ROW }
    }

    /** What a quote or a list item puts before a line of its content. */
    private const val CONTAINER_PREFIX = " \t>"
    private const val DELIMITER_ROW = "|-: \t"
}
