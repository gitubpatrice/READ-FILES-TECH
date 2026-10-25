package com.readfilestech.read_files_tech.data.viewer.document

import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument

/**
 * The plain text of a document, written as its parts are read: UTF-8 bytes, at most [cap] (past it, the
 * document is too large to show — the text viewer's cap, since the text viewer's rows show it), the
 * white space that ends it dropped (the 2.15.3's `trimRight`).
 *
 * A paragraph is a line. One that opens inside another — a text box anchored in a paragraph — ends the
 * line the outer one had started; what the outer one says after it is a line of its own: nothing is
 * glued, nothing lost (the 2.15.3 glued the outer text to the box's first line, and in an OpenDocument
 * lost the rest of the outer paragraph).
 *
 * [dropBlankLines]: a paragraph that holds only white space is not written (the 2.15.3's rule for
 * OpenDocument). [maxNewlines]: no more line feeds in a row than this (its rule for Word: `\n{3,}` became
 * `\n\n`). A carriage return, alone or before a line feed, is a line feed (its rule for OpenDocument,
 * applied to both).
 */
internal class PlainTextBuilder(
    private val cap: Long,
    private val dropBlankLines: Boolean,
    private val maxNewlines: Int = Int.MAX_VALUE,
) {
    private var bytes = ByteArray(minOf(INITIAL_BYTES.toLong(), cap).toInt())
    private var size = 0

    /** [size] after the last character that is not white space: where the text ends. */
    private var textEnd = 0

    /** Line feeds ending what is written. */
    private var newlines = 0
    private var afterCarriageReturn = false

    /** Paragraphs open, nested ones included. */
    private var paragraphs = 0
    private var lineOpen = false
    private var lineStart = 0
    private var newlinesBeforeLine = 0
    private var lineHasText = false

    /** Whether a character that is not white space was written: false, the document has no text to show. */
    var hasText = false
        private set

    val inParagraph: Boolean get() = paragraphs > 0

    fun startParagraph() {
        // Inside another paragraph: the outer one's line ends here — unless it holds no text yet (a text
        // box anchored to an empty paragraph, Word's usual way): then it is no line at all.
        endLine(interrupted = true)
        paragraphs++
        startLine()
    }

    fun endParagraph() {
        endLine()
        if (paragraphs > 0) paragraphs--
    }

    fun text(text: CharSequence) {
        if (!lineOpen) startLine()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            when {
                pair -> writeCodePoint(Character.toCodePoint(c, text[++i]))
                c == '\r' -> {
                    lineFeed()
                    afterCarriageReturn = true
                }
                c == '\n' -> {
                    if (!afterCarriageReturn) lineFeed()
                    afterCarriageReturn = false
                }
                // Half of a surrogate pair, alone: not a character.
                else -> writeCodePoint(if (c.isSurrogate()) REPLACEMENT else c.code)
            }
            i++
        }
    }

    /** [times] spaces (`<text:s text:c="…"/>`): a count the file chooses, refused at once past the cap. */
    fun spaces(times: Long) {
        if (times <= 0) return
        if (!lineOpen) startLine()
        reserve(times)
        repeat(times.toInt()) { bytes[size++] = SPACE }
        newlines = 0
        afterCarriageReturn = false
    }

    suspend fun finish(): TextDocument {
        endLine()
        return TextDocument.of(bytes, textEnd)
    }

    private fun startLine() {
        lineOpen = true
        lineStart = size
        newlinesBeforeLine = newlines
        lineHasText = false
    }

    private fun endLine(interrupted: Boolean = false) {
        if (!lineOpen) return
        lineOpen = false
        afterCarriageReturn = false
        if ((dropBlankLines || interrupted) && !lineHasText) {
            size = lineStart
            newlines = newlinesBeforeLine
        } else {
            lineFeed()
        }
    }

    private fun lineFeed() {
        if (newlines >= maxNewlines) return
        reserve(1)
        bytes[size++] = LINE_FEED
        newlines++
    }

    @Suppress("MagicNumber") // the UTF-8 encoding itself: lead bytes, continuation bytes, their ranges
    private fun writeCodePoint(code: Int) {
        afterCarriageReturn = false
        newlines = 0
        when {
            code < 0x80 -> {
                reserve(1)
                bytes[size++] = code.toByte()
            }
            code < 0x800 -> {
                reserve(2)
                bytes[size++] = (0xC0 or (code shr 6)).toByte()
                bytes[size++] = (0x80 or (code and 0x3F)).toByte()
            }
            code < 0x10000 -> {
                reserve(3)
                bytes[size++] = (0xE0 or (code shr 12)).toByte()
                bytes[size++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
                bytes[size++] = (0x80 or (code and 0x3F)).toByte()
            }
            else -> {
                reserve(4)
                bytes[size++] = (0xF0 or (code shr 18)).toByte()
                bytes[size++] = (0x80 or ((code shr 12) and 0x3F)).toByte()
                bytes[size++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
                bytes[size++] = (0x80 or (code and 0x3F)).toByte()
            }
        }
        if (!Character.isWhitespace(code) && !Character.isSpaceChar(code)) {
            textEnd = size
            lineHasText = true
            hasText = true
        }
    }

    /** Room for [n] more bytes, growing by doubling up to [cap]; past it, the document is too large. */
    private fun reserve(n: Long) {
        val needed = size + n
        if (needed > cap) throw FileTooLargeException(cap)
        if (needed > bytes.size) bytes = bytes.copyOf(maxOf(needed, minOf(bytes.size * 2L, cap)).toInt())
    }

    private companion object {
        const val INITIAL_BYTES = 64 * 1024
        const val LINE_FEED = '\n'.code.toByte()
        const val SPACE = ' '.code.toByte()
        const val REPLACEMENT = 0xFFFD
    }
}
