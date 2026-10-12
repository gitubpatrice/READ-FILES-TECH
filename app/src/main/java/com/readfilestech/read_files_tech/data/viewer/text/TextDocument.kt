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
}

/** The rows of a text: where each starts in its bytes, and how to decode one. */
internal class TextRows(
    private val bytes: ByteArray,
    private val size: Int,
    private val starts: IntArray,
    val count: Int,
    val encoding: TextEncoding,
) {
    /** Row [index], without its line break. */
    fun row(index: Int): String {
        require(index in 0 until count) { "row $index of $count" }
        val start = starts[index]
        return encoding.charset.decode(ByteBuffer.wrap(bytes, start, end(index) - start)).toString().removeSuffix("\n").removeSuffix("\r")
    }

    /** Whether the row after [index] carries on the same line (this one was cut at [TextDocument.ROW_BYTES]). */
    fun continues(index: Int): Boolean {
        if (index + 1 >= count) return false
        val unit = encoding.unitSize
        val end = end(index)
        return end - starts[index] < unit || !TextDocument.isLineFeed(bytes, end - unit, encoding)
    }

    /** The text of the rows from [from] until [to], line breaks included, decoded at once. */
    fun text(from: Int, to: Int): String {
        require(from in 0 until count && to in from + 1..count) { "rows $from until $to of $count" }
        val start = starts[from]
        return encoding.charset.decode(ByteBuffer.wrap(bytes, start, end(to - 1) - start)).toString()
    }

    /** The first row from [from] on that starts at least [bytes] bytes after it — [count] when none does. */
    fun rowAfter(from: Int, bytes: Int): Int {
        val target = starts[from].toLong() + bytes
        if (target > Int.MAX_VALUE) return count
        val found = Arrays.binarySearch(starts, from, count, target.toInt())
        return if (found >= 0) found else -found - 1
    }

    private fun end(index: Int): Int = if (index + 1 < count) starts[index + 1] else size
}

/**
 * A text file opened for the viewer (`TxtViewerScreen` of the 2.15.3): its bytes once, the start of
 * each row found in one pass, and a row decoded only when it is shown. A 50 MiB log costs its bytes and
 * its index — not a String twice its size inside one SelectableText, which the 2.15.3 built and which
 * froze the screen long before its 50 MiB cap.
 *
 * A row is a line, or a piece of [ROW_BYTES] bytes of a longer one (a minified script is one line): a
 * text view lays out what it is given whole. The 2.15.3 read with a strict UTF-8 decoder, and a text
 * saved in Windows-1252 or UTF-16 ended in "Erreur de lecture : FormatException" (INVENTAIRE F37).
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

    companion object {
        /** The longest row, in bytes, before it is cut (at a character boundary). */
        const val ROW_BYTES = 4096

        /** Reads [source] whole, within the 2.15.3's cap (`FileCaps.TEXT_VIEWER`). */
        suspend fun read(source: ViewerSource): TextDocument {
            val expected = source.length()
            val (bytes, size) = source.open(FileCaps.TEXT_VIEWER).use { readAll(it, expected, FileCaps.TEXT_VIEWER) }
            return of(bytes, size)
        }

        /** The document of the first [size] bytes of [bytes] — kept, not copied. */
        suspend fun of(bytes: ByteArray, size: Int = bytes.size): TextDocument {
            val encoding = detect(bytes, size)
            val rows = indexRows(bytes, size, encoding)
            return TextDocument(rows)
        }

        private suspend fun readAll(input: InputStream, expected: Long, cap: Long): Pair<ByteArray, Int> {
            // One byte more than announced: a file that grew is read on, up to the cap.
            var buffer = ByteArray((expected.coerceIn(0, cap) + 1).toInt())
            var size = 0
            while (true) {
                currentCoroutineContext().ensureActive()
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

        internal fun detect(bytes: ByteArray, size: Int): TextEncoding = when {
            startsWith(bytes, size, BOM_UTF_8) -> TextEncoding.UTF_8_BOM
            startsWith(bytes, size, BOM_UTF_16LE) -> TextEncoding.UTF_16LE
            startsWith(bytes, size, BOM_UTF_16BE) -> TextEncoding.UTF_16BE
            isValidUtf8(bytes, size) -> TextEncoding.UTF_8
            else -> TextEncoding.WINDOWS_1252
        }

        private fun startsWith(bytes: ByteArray, size: Int, prefix: IntArray): Boolean =
            size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and BYTE) == prefix[it] }

        private fun isValidUtf8(bytes: ByteArray, size: Int): Boolean {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(bytes, 0, size)
            val sink = CharBuffer.allocate(DECODE_CHUNK)
            var valid = true
            var decoding = true
            while (valid && decoding) {
                val result = decoder.decode(input, sink, true)
                valid = !result.isError
                decoding = result.isOverflow
                sink.clear()
            }
            return valid && !decoder.flush(sink).isError
        }

        /**
         * Row starts: after each line feed, and where a row reaches [ROW_BYTES] at a character boundary.
         * Counted first, then written in an array of that size: a growing array would cost its copy too,
         * at 10 million lines 96 MB instead of 40 (a 50 MiB log of short lines).
         */
        private suspend fun indexRows(bytes: ByteArray, size: Int, encoding: TextEncoding): TextRows {
            val count = scanRows(bytes, size, encoding, null)
            val starts = IntArray(count)
            scanRows(bytes, size, encoding, starts)
            return TextRows(bytes, size, starts, count, encoding)
        }

        /** The rows of the text, written in [starts] when given; their number. */
        private suspend fun scanRows(bytes: ByteArray, size: Int, encoding: TextEncoding, starts: IntArray?): Int {
            var count = 0
            fun add(start: Int) {
                if (starts != null) starts[count] = start
                count++
            }
            val unit = encoding.unitSize
            var rowStart = encoding.bomLength
            add(rowStart)
            var p = rowStart
            while (p + unit <= size) {
                if (p % CANCEL_CHECK_EVERY < unit) currentCoroutineContext().ensureActive()
                when {
                    isLineFeed(bytes, p, encoding) -> {
                        p += unit
                        if (p < size) add(p)
                        rowStart = p
                    }
                    p - rowStart >= ROW_BYTES && isBoundary(bytes, p, rowStart, encoding) -> {
                        add(p)
                        rowStart = p
                        p += unit
                    }
                    else -> p += unit
                }
            }
            return count
        }

        internal fun isLineFeed(bytes: ByteArray, p: Int, encoding: TextEncoding): Boolean = when (encoding) {
            TextEncoding.UTF_16LE -> bytes[p] == LF && bytes[p + 1] == ZERO
            TextEncoding.UTF_16BE -> bytes[p] == ZERO && bytes[p + 1] == LF
            else -> bytes[p] == LF
        }

        /**
         * Whether a row may start at [p]: not inside a UTF-8 sequence nor between the two halves of a
         * UTF-16 surrogate pair. A run of UTF-8 continuation bytes is at most 3 long in valid UTF-8; in
         * Windows-1252 they are ordinary letters, and any byte is a boundary.
         */
        private fun isBoundary(bytes: ByteArray, p: Int, rowStart: Int, encoding: TextEncoding): Boolean = when (encoding) {
            TextEncoding.UTF_16LE -> (bytes[p + 1].toInt() and SURROGATE_MASK) != LOW_SURROGATE
            TextEncoding.UTF_16BE -> (bytes[p].toInt() and SURROGATE_MASK) != LOW_SURROGATE
            TextEncoding.WINDOWS_1252 -> true
            TextEncoding.UTF_8, TextEncoding.UTF_8_BOM ->
                (bytes[p].toInt() and CONTINUATION_MASK) != CONTINUATION || p - rowStart >= ROW_BYTES + MAX_CONTINUATIONS
        }

        private val BOM_UTF_8 = intArrayOf(0xEF, 0xBB, 0xBF)
        private val BOM_UTF_16LE = intArrayOf(0xFF, 0xFE)
        private val BOM_UTF_16BE = intArrayOf(0xFE, 0xFF)
        private const val BYTE = 0xFF
        private const val LF: Byte = 0x0A
        private const val ZERO: Byte = 0
        private const val CONTINUATION_MASK = 0xC0
        private const val CONTINUATION = 0x80
        private const val MAX_CONTINUATIONS = 3
        private const val SURROGATE_MASK = 0xFC
        private const val LOW_SURROGATE = 0xDC
        private const val DECODE_CHUNK = 8192
        private const val READ_CHUNK = 64 * 1024
        private const val CANCEL_CHECK_EVERY = 1 shl 20
    }
}
