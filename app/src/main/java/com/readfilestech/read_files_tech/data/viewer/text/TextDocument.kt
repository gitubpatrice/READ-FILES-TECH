package com.readfilestech.read_files_tech.data.viewer.text

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Arrays

/** How a text file is written, read from its first bytes and, without a BOM, from whether it is valid UTF-8. */
enum class TextEncoding(val charset: Charset, val bomLength: Int) {
    UTF_8(Charsets.UTF_8, 0),
    UTF_8_BOM(Charsets.UTF_8, 3),
    UTF_16LE(Charsets.UTF_16LE, 2),
    UTF_16BE(Charsets.UTF_16BE, 2),

    /** Not valid UTF-8 and no BOM: a text saved by an older Windows tool, the common case in French. */
    WINDOWS_1252(Charset.forName("windows-1252"), 0),
    ;

    val unitSize: Int get() = if (this == UTF_16LE || this == UTF_16BE) 2 else 1

    companion object {
        internal fun detect(bytes: ByteArray, size: Int): TextEncoding = when {
            startsWith(bytes, size, BOM_UTF_8) -> TextEncoding.UTF_8_BOM
            startsWith(bytes, size, BOM_UTF_16LE) -> TextEncoding.UTF_16LE
            startsWith(bytes, size, BOM_UTF_16BE) -> TextEncoding.UTF_16BE
            isValidUtf8(bytes, size) -> TextEncoding.UTF_8
            else -> TextEncoding.WINDOWS_1252
        }

        private fun startsWith(bytes: ByteArray, size: Int, prefix: IntArray): Boolean =
            size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and BYTE) == prefix[it] }

        /**
         * Valid UTF-8 — a last character cut short included (a log copied while it was written) when the
         * text has whole multi-byte characters before it (Claude review R9): one broken `é` at the end must
         * not turn 30 MB of accents into `Ã©`. A text whose only non-ASCII bytes are its last ones stays
         * Windows-1252: `café` saved by Windows ends in the byte E9, a valid start of a UTF-8 sequence.
         */
        private fun isValidUtf8(bytes: ByteArray, size: Int): Boolean {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(bytes, 0, size)
            val sink = CharBuffer.allocate(DECODE_CHUNK)
            var chars = 0L
            var decoding = true
            while (decoding) {
                val result = decoder.decode(input, sink, false)
                if (result.isError) return false
                decoding = result.isOverflow
                chars += sink.position()
                sink.clear()
            }
            val cutShort = input.hasRemaining()
            return !cutShort || chars < input.position()
        }

        private val BOM_UTF_8 = intArrayOf(0xEF, 0xBB, 0xBF)
        private val BOM_UTF_16LE = intArrayOf(0xFF, 0xFE)
        private val BOM_UTF_16BE = intArrayOf(0xFE, 0xFF)
        private const val BYTE = 0xFF
        private const val DECODE_CHUNK = 8192
    }
}

/**
 * The rows of a text and how to decode them. The start of one row in [TextDocument.ANCHOR_EVERY] is
 * kept; the others are found from it, each in at most [TextDocument.ROW_BYTES] bytes. A start for every
 * row cost 4 bytes per row: a 50 MiB file of line feeds needed 200 MB, over the 256 MB heap of a Galaxy
 * S9 with its 50 MiB of bytes (Claude review R9).
 */
internal class TextRows(
    private val bytes: ByteArray,
    private val size: Int,
    private val anchors: IntArray,
    val count: Int,
    val encoding: TextEncoding,
) {
    /** Row [index], without its line break. */
    fun row(index: Int): String {
        require(index in 0 until count) { "row $index of $count" }
        val start = start(index)
        return decode(start, RowEnds.contentEnd(bytes, start, next(start), encoding))
    }

    /** Whether the row after [index] carries on the same line (this one was cut at [TextDocument.ROW_BYTES]). */
    fun continues(index: Int): Boolean {
        val start = start(index)
        return !RowEnds.endsLine(bytes, start, next(start), index + 1 >= count, encoding)
    }

    /** The rows from [from] on, undecoded, one after the other. */
    fun rawRows(from: Int): RawRows = RawRows(bytes, size, encoding, start(from))

    /** The text of the rows from [from] until [to], line breaks included, decoded at once. */
    fun text(from: Int, to: Int): String {
        require(from in 0 until count && to in from + 1..count) { "rows $from until $to of $count" }
        return decode(start(from), if (to < count) start(to) else size)
    }

    /** The first row from [from] on that starts at least [bytes] bytes after it — [count] when none does. */
    fun rowAfter(from: Int, bytes: Int): Int {
        val start = start(from)
        val target = start.toLong() + bytes
        return when {
            bytes == 0 -> from
            target >= size -> count
            else -> rowAt(from, start, target.toInt())
        }
    }

    /**
     * The first row from [from] (which starts at [fromStart]) on that starts at [target] or after: from
     * the last anchor at or before the target when it is past [from], then row by row.
     */
    private fun rowAt(from: Int, fromStart: Int, target: Int): Int {
        val found = Arrays.binarySearch(anchors, target)
        val anchor = if (found >= 0) found else -found - 2
        var index = from
        var start = fromStart
        if (anchor * ANCHOR_EVERY > from) {
            index = anchor * ANCHOR_EVERY
            start = anchors[anchor]
        }
        while (start < target && index + 1 < count) {
            start = next(start)
            index++
        }
        return if (start < target) count else index
    }

    private fun start(index: Int): Int {
        var start = anchors[index / ANCHOR_EVERY]
        repeat(index % ANCHOR_EVERY) { start = next(start) }
        return start
    }

    private fun next(start: Int): Int = TextDocument.nextRow(bytes, size, start, encoding)

    private fun decode(start: Int, end: Int): String = encoding.charset.decode(ByteBuffer.wrap(bytes, start, end - start)).toString()

    private companion object {
        const val ANCHOR_EVERY = TextDocument.ANCHOR_EVERY
    }
}

/**
 * A text file opened for the viewer (`TxtViewerScreen` of the 2.15.3): its bytes once, its rows indexed
 * in one pass, and a row decoded only when it is shown. A 50 MiB log costs its bytes and a sparse index
 * — not a String twice its size inside one SelectableText, which the 2.15.3 built (INVENTAIRE F38).
 *
 * A row is a line, or a piece of [ROW_BYTES] bytes of a longer one (a minified script is one line): a
 * text view lays out what it is given whole. A piece ends at a character boundary — never inside a UTF-8
 * sequence or a UTF-16 surrogate pair, never between the CR and the LF of a line break. The 2.15.3 read
 * with a strict UTF-8 decoder, and a text saved in Windows-1252 or UTF-16 ended in "Erreur de lecture :
 * FormatException" (INVENTAIRE F37). A line ends at a LF only: a lone CR (the Mac OS of before 2001)
 * is not one.
 */
class TextDocument private constructor(private val rows: TextRows) {
    val rowCount: Int get() = rows.count

    val encoding: TextEncoding get() = rows.encoding

    /** Row [index], without its line break. */
    fun row(index: Int): String = rows.row(index)

    /** Whether the row after [index] carries on the same line (this one was cut at [ROW_BYTES]). */
    fun continues(index: Int): Boolean = rows.continues(index)

    /** The text of the rows from [from] until [to], line breaks included, decoded at once. */
    internal fun text(from: Int, to: Int): String = rows.text(from, to)

    /** The first row from [from] on that starts at least [bytes] bytes after it; [rowCount] when none does. */
    internal fun rowAfter(from: Int, bytes: Int): Int = rows.rowAfter(from, bytes)

    /** The rows from [from] on, read from the bytes without decoding them, one after the other (CodeColouring). */
    internal fun rawRows(from: Int): RawRows {
        require(from in 0 until rowCount) { "row $from of $rowCount" }
        return rows.rawRows(from)
    }

    companion object {
        /** The longest row, in bytes, before it is cut (at a character boundary). */
        const val ROW_BYTES = 4096

        /** One row start kept in this many (TextRows). */
        internal const val ANCHOR_EVERY = 16

        /** Reads [source] whole, within the 2.15.3's cap (`FileCaps.TEXT_VIEWER`). */
        suspend fun read(source: ViewerSource): TextDocument {
            val expected = source.length()
            val (bytes, size) = source.open(FileCaps.TEXT_VIEWER).use { readAll(it, expected, FileCaps.TEXT_VIEWER) }
            return of(bytes, size)
        }

        /** The document of the first [size] bytes of [bytes] — kept, not copied. */
        suspend fun of(bytes: ByteArray, size: Int = bytes.size): TextDocument {
            val encoding = TextEncoding.detect(bytes, size)
            return TextDocument(indexRows(bytes, size, encoding))
        }

        /**
         * The bytes of [input] and how many there are, read in pieces of [READ_CHUNK], within [cap]; the
         * CSV viewer reads its files the same way.
         */
        internal suspend fun readAll(input: InputStream, expected: Long, cap: Long): Pair<ByteArray, Int> {
            val job = currentCoroutineContext()
            return readAll(input, expected, cap) { job.ensureActive() }
        }

        /**
         * The same, for work that cannot suspend — the CSV merge reads its files one by one while it writes its
         * output, inside the output's atomic write: [checkpoint] is called before each piece, and what it throws
         * (a cancellation) stops the reading.
         */
        internal fun readAll(input: InputStream, expected: Long, cap: Long, checkpoint: () -> Unit): Pair<ByteArray, Int> {
            // One byte more than announced: a file that grew is read on, up to the cap.
            var buffer = ByteArray((expected.coerceIn(0, cap) + 1).toInt())
            var size = 0
            while (true) {
                checkpoint()
                if (size == buffer.size) {
                    // The stream's own cap stops it first; a source that would not is stopped here.
                    if (buffer.size > cap) throw FileTooLargeException(cap)
                    buffer = buffer.copyOf(minOf(buffer.size.toLong() * 2, cap + 1).toInt())
                }
                // By pieces: a stream over a channel (Files.newInputStream) reads through a temporary
                // direct buffer of the size asked, and keeps it for its thread — one read of 50 MB left
                // 50 MB on the heap after the file was shown (measured by the corpus, emulator 36).
                val n = input.read(buffer, size, minOf(READ_CHUNK, buffer.size - size))
                if (n == -1) break
                size += n
            }
            return buffer to size
        }

        /** Counts the rows, then keeps one start in [ANCHOR_EVERY]. */
        private suspend fun indexRows(bytes: ByteArray, size: Int, encoding: TextEncoding): TextRows {
            var count = 1
            var start = encoding.bomLength
            while (true) {
                if (count % CANCEL_CHECK_ROWS == 0) currentCoroutineContext().ensureActive()
                start = nextRow(bytes, size, start, encoding)
                if (start >= size) break
                count++
            }
            val anchors = IntArray((count + ANCHOR_EVERY - 1) / ANCHOR_EVERY)
            start = encoding.bomLength
            anchors[0] = start
            for (index in 1 until count) {
                if (index % CANCEL_CHECK_ROWS == 0) currentCoroutineContext().ensureActive()
                start = nextRow(bytes, size, start, encoding)
                if (index % ANCHOR_EVERY == 0) anchors[index / ANCHOR_EVERY] = start
            }
            return TextRows(bytes, size, anchors, count, encoding)
        }

        /**
         * Where the row after the one starting at [start] starts: after its line feed, or where it reaches
         * [ROW_BYTES] at a character boundary — [size] when it is the last row.
         */
        internal fun nextRow(bytes: ByteArray, size: Int, start: Int, encoding: TextEncoding): Int {
            val unit = encoding.unitSize
            var p = start
            var next = -1
            while (next < 0 && p + unit <= size) {
                when {
                    isLineFeed(bytes, p, encoding) -> next = p + unit
                    p - start >= ROW_BYTES && isBoundary(bytes, size, p, start, encoding) -> next = p
                    else -> p += unit
                }
            }
            return if (next < 0) size else next
        }

        internal fun isLineFeed(bytes: ByteArray, p: Int, encoding: TextEncoding): Boolean = isUnit(bytes, p, encoding, LF)

        internal fun isUnit(bytes: ByteArray, p: Int, encoding: TextEncoding, value: Byte): Boolean = when (encoding) {
            TextEncoding.UTF_16LE -> bytes[p] == value && bytes[p + 1] == ZERO
            TextEncoding.UTF_16BE -> bytes[p] == ZERO && bytes[p + 1] == value
            else -> bytes[p] == value
        }

        /**
         * Whether a row may start at [p]: not between the CR and the LF of a line break (the next row would
         * be an empty line that is not there — R9), not inside a UTF-8 sequence, not between the two halves
         * of a UTF-16 surrogate pair. A run of UTF-8 continuation bytes is at most 3 long in valid UTF-8 (in
         * Windows-1252 they are letters, and any byte is a boundary); a lone UTF-16 surrogate is no pair
         * (a file of them was one row of 50 MiB — R9).
         */
        private fun isBoundary(bytes: ByteArray, size: Int, p: Int, start: Int, encoding: TextEncoding): Boolean {
            val unit = encoding.unitSize
            if (p + 2 * unit <= size && isUnit(bytes, p, encoding, CR) && isLineFeed(bytes, p + unit, encoding)) return false
            return when (encoding) {
                TextEncoding.UTF_16LE -> !(isSurrogate(bytes[p + 1], LOW_SURROGATE) && isSurrogate(bytes[p - 1], HIGH_SURROGATE))
                TextEncoding.UTF_16BE -> !(isSurrogate(bytes[p], LOW_SURROGATE) && isSurrogate(bytes[p - 2], HIGH_SURROGATE))
                TextEncoding.WINDOWS_1252 -> true
                TextEncoding.UTF_8, TextEncoding.UTF_8_BOM ->
                    (bytes[p].toInt() and CONTINUATION_MASK) != CONTINUATION || p - start >= ROW_BYTES + MAX_CONTINUATIONS
            }
        }

        private fun isSurrogate(high: Byte, kind: Int): Boolean = (high.toInt() and SURROGATE_MASK) == kind

        private const val LF: Byte = 0x0A
        internal const val CR: Byte = 0x0D
        private const val ZERO: Byte = 0
        private const val CONTINUATION_MASK = 0xC0
        private const val CONTINUATION = 0x80
        private const val MAX_CONTINUATIONS = 3
        private const val SURROGATE_MASK = 0xFC
        private const val HIGH_SURROGATE = 0xD8
        private const val LOW_SURROGATE = 0xDC
        private const val READ_CHUNK = 64 * 1024
        private const val CANCEL_CHECK_ROWS = 4096
    }
}
