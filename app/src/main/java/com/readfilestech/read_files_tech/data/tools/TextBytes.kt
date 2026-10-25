package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.editor.NotEditableException
import com.readfilestech.read_files_tech.data.editor.UnencodableTextException
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/** Characters, words and lines of a text ([TextBytes.stats]). */
data class TextStats(val characters: Long, val words: Long, val lines: Long)

/** A replacement would make a file of more than [capBytes]: nothing is written. */
class ResultTooLargeException(val capBytes: Long) : IOException("a result over $capBytes bytes")

/** The text does not hold what is to be replaced: nothing is written. */
class NoMatchException : IOException("nothing to replace")

/**
 * A text file read whole for the text tools (`TxtToolsScreen` of the 2.15.3), within the text viewer's cap,
 * and decoded as the text viewer decodes it ([TextEncoding]): the 2.15.3 read it as strict UTF-8, and a
 * Windows-1252 or UTF-16 file failed (as in its viewer, inventory F37). Its bytes are kept, never one String
 * of the whole text: each tool decodes them again by pieces of [PIECE_CHARS], and calls its `progress` with
 * the part done after each — what that throws (a cancellation) stops the work.
 */
class TextBytes private constructor(private val bytes: ByteArray, val size: Int, val encoding: TextEncoding) {
    /** The document the text viewer would show, for Text → PDF: the same bytes, not copied. */
    suspend fun document(): TextDocument = TextDocument.of(bytes, size)

    /**
     * Python's counts of the text decoded (after its byte order mark): `len(text)` characters — code points, an
     * emoji is one, where the 2.15.3 counted UTF-16 units (`txt_tools_screen.dart:125`) —, `len(text.split())`
     * words — runs of characters between Python's white space ([isPythonSpace]) —, and the 2.15.3's lines: the
     * line feeds plus one, none in an empty text (`txt_tools_screen.dart:128`). A lone CR is no line break, as
     * in the text viewer and the PDF made of the text ([TextDocument]): counted as one, the lines said were
     * not the lines shown (GPT review R26 asked for it; refuted for that).
     */
    fun stats(progress: (Float) -> Unit = {}): TextStats {
        var characters = 0L
        var words = 0L
        var feeds = 0L
        var inWord = false
        pieces(strict = false, progress) { text ->
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                i += Character.charCount(codePoint)
                characters++
                if (codePoint == LF) feeds++
                val space = isPythonSpace(codePoint)
                if (!space && !inWord) words++
                inWord = !space
            }
        }
        return TextStats(characters, words, if (characters == 0L) 0 else feeds + 1)
    }

    /**
     * How many times [query] is in the text, as Python's `text.count(query)` and the 2.15.3's `allMatches`
     * (`txt_tools_screen.dart:61`): from the left, never overlapping, case and line breaks as written.
     */
    fun count(query: String, progress: (Float) -> Unit = {}): Long {
        require(query.isNotEmpty()) { "an empty query" }
        var count = 0L
        val carry = StringBuilder()
        pieces(strict = false, progress) { text ->
            carry.append(text)
            var from = 0
            while (true) {
                val at = carry.indexOf(query, from)
                if (at < 0) break
                count++
                from = at + query.length
            }
            carry.delete(0, keptFrom(carry, from, query))
        }
        return count
    }

    /**
     * The text with each [query] replaced by [replacement] — Python's `text.replace(query, replacement)` —
     * written on [out] as the file is written: its encoding, its byte order mark, its line breaks; to the
     * byte, what was not replaced is what the file holds. The number of replacements is given back.
     *
     * The 2.15.3 wrote it over the source file, without asking and with no way back (inventory D12); the tool
     * writes it into a new file, the source untouched.
     *
     * @throws NotEditableException a NUL (not a text), or bytes that are no characters of the file's
     *   encoding: written again, they would come out as other bytes
     * @throws UnencodableTextException a character of [replacement] the file's encoding has no bytes for
     * @throws ResultTooLargeException the result would be more than [capBytes]
     * @throws NoMatchException [query] is not in the text
     */
    fun replaceAll(
        query: String,
        replacement: String,
        out: OutputStream,
        capBytes: Long,
        progress: (Float) -> Unit = {},
    ): Long {
        require(query.isNotEmpty()) { "an empty query" }
        val sink = EncodedSink(out, encoding, capBytes)
        sink.checkEncodable(replacement)
        sink.raw(bytes, minOf(encoding.bomLength, size))
        var count = 0L
        val carry = StringBuilder()
        pieces(strict = true, progress) { text ->
            if (text.indexOf(NUL) >= 0) throw NotEditableException(NotEditableException.Reason.NOT_TEXT)
            carry.append(text)
            var from = 0
            while (true) {
                val at = carry.indexOf(query, from)
                if (at < 0) break
                sink.append(carry, from, at)
                sink.append(replacement, 0, replacement.length)
                count++
                from = at + query.length
            }
            val kept = keptFrom(carry, from, query)
            sink.append(carry, from, kept)
            carry.delete(0, kept)
        }
        sink.append(carry, 0, carry.length)
        sink.finish()
        if (count == 0L) throw NoMatchException()
        return count
    }

    /**
     * Where the text still to search begins once [carry] was searched up to [from]: the last `query.length - 1`
     * characters may start a match the next piece completes; before them, none can.
     */
    private fun keptFrom(carry: CharSequence, from: Int, query: String): Int = maxOf(from, carry.length - (query.length - 1))

    /**
     * The text decoded by pieces of at most [PIECE_CHARS], given in order to [piece] — never a surrogate pair cut
     * in two. Not [strict], a byte that is no character is U+FFFD, as in the viewer; [strict], it is a
     * [NotEditableException]: written again, it would be other bytes.
     */
    private fun pieces(strict: Boolean, progress: (Float) -> Unit, piece: (String) -> Unit) {
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        val decoder = encoding.charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action)
        val start = minOf(encoding.bomLength, size)
        val input = ByteBuffer.wrap(bytes, start, size - start)
        val total = maxOf(1, size - start).toFloat()
        val out = CharBuffer.allocate(PIECE_CHARS)
        var held = ""
        var flushing = false
        var ended = false
        while (!ended) {
            progress((input.position() - start) / total)
            val result = step(decoder, input, out, flushing)
            if (result.isError) throw NotEditableException(NotEditableException.Reason.NOT_REVERSIBLE)
            if (result.isUnderflow && !flushing) {
                flushing = true
                continue
            }
            ended = result.isUnderflow
            out.flip()
            val (now, waiting) = heldBack(held + out, last = ended)
            out.clear()
            held = waiting
            piece(now)
        }
        progress(1f)
    }

    private fun step(decoder: CharsetDecoder, input: ByteBuffer, out: CharBuffer, flushing: Boolean) =
        if (flushing) decoder.flush(out) else decoder.decode(input, out, true)

    companion object {
        /** Characters decoded at a time: the text tools never hold the whole text as characters. */
        const val PIECE_CHARS = 32 * 1024

        private const val LF = 0x0A
        private const val NUL = '\u0000'
        private const val SPACE = 0x20
        private const val ASCII_LIMIT = 0x80
        private val ASCII_CONTROL_SPACES = 0x09..0x0D
        private val ASCII_SEPARATORS = 0x1C..0x1F

        /**
         * [text], a piece just decoded, as (what is given now, what waits for the next piece): a high surrogate at
         * its end, unless it is the [last], waits for its low half. Android's decoders (ICU) may end a piece between
         * the two halves of a pair, where the JVM's return an overflow first.
         */
        internal fun heldBack(text: String, last: Boolean): Pair<String, String> =
            if (!last && text.isNotEmpty() && text.last().isHighSurrogate()) text.dropLast(1) to text.takeLast(1) else text to ""

        /** The file read whole, within the text viewer's cap (`FileCaps.TEXT_VIEWER`). */
        suspend fun read(source: ViewerSource): TextBytes {
            val cap = FileCaps.TEXT_VIEWER
            val (bytes, size) = source.open(cap).use { TextDocument.readAll(it, source.length(), cap) }
            return of(bytes, size)
        }

        /** The first [size] bytes of [bytes] — kept, not copied. */
        fun of(bytes: ByteArray, size: Int = bytes.size): TextBytes = TextBytes(bytes, size, TextEncoding.detect(bytes, size))

        /**
         * Python's `str.isspace()`, which `split()` cuts at: the general category Zs, or the bidirectional class
         * WS, B or S — the tab, the line breaks, the separators U+001C to U+001F, U+0085, the no-break space;
         * not U+200B nor U+FEFF.
         */
        fun isPythonSpace(codePoint: Int): Boolean = if (codePoint < ASCII_LIMIT) {
            codePoint == SPACE || codePoint in ASCII_CONTROL_SPACES || codePoint in ASCII_SEPARATORS
        } else {
            Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt() ||
                when (Character.getDirectionality(codePoint)) {
                    Character.DIRECTIONALITY_WHITESPACE,
                    Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR,
                    Character.DIRECTIONALITY_SEGMENT_SEPARATOR,
                    -> true
                    else -> false
                }
        }
    }
}

/**
 * Characters into the bytes of [encoding] on [out], refused rather than replaced, and at most [cap] bytes.
 * The characters are held until there are [FLUSH_CHARS] of them, never the whole text.
 */
private class EncodedSink(private val out: OutputStream, private val encoding: TextEncoding, private val cap: Long) {
    private val encoder = encoding.charset.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private val pending = StringBuilder()
    private val buffer = ByteBuffer.allocate(BUFFER)
    private var written = 0L

    /** @throws UnencodableTextException the first character of [text] the encoding has no bytes for — a lone surrogate too */
    fun checkEncodable(text: String) {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val character = String(Character.toChars(codePoint))
            if (!encoder.canEncode(character)) throw UnencodableTextException(character, encoding)
            i += character.length
        }
    }

    /** The first [length] bytes of [bytes], as they are: the file's byte order mark. */
    fun raw(bytes: ByteArray, length: Int) {
        count(length)
        out.write(bytes, 0, length)
    }

    fun append(text: CharSequence, start: Int, end: Int) {
        pending.append(text, start, end)
        if (pending.length >= FLUSH_CHARS) drain(last = false)
    }

    fun finish() {
        drain(last = true)
        while (encoder.flush(buffer).isOverflow) emit()
        emit()
    }

    private fun drain(last: Boolean) {
        var n = pending.length
        // A high surrogate waits for its low half, which the next characters bring.
        if (!last && n > 0 && pending[n - 1].isHighSurrogate()) n--
        val input = CharBuffer.wrap(pending, 0, n)
        do {
            val result = encoder.encode(input, buffer, last)
            // The replacement was checked: what fails now is the file's own text, decoded but not encodable back.
            if (result.isError) throw NotEditableException(NotEditableException.Reason.NOT_REVERSIBLE)
            if (result.isOverflow) emit()
        } while (result.isOverflow)
        pending.delete(0, n)
    }

    private fun emit() {
        buffer.flip()
        count(buffer.remaining())
        out.write(buffer.array(), 0, buffer.limit())
        buffer.clear()
    }

    private fun count(bytes: Int) {
        written += bytes
        if (written > cap) throw ResultTooLargeException(cap)
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val FLUSH_CHARS = 16 * 1024
    }
}
