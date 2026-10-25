package com.readfilestech.read_files_tech.data.viewer.text

/**
 * The rows of a text read one after the other from its bytes, not decoded: the current row is a CharSequence
 * of its code units, without its line break as [TextDocument.row] gives it — a UTF-16 unit as it is, a byte of
 * UTF-8 or Windows-1252 as the character of the same value. ASCII stays ASCII and nothing else becomes ASCII:
 * enough for a [CodeTokenizer], whose states only ASCII decides, over 50 MiB without decoding a character.
 */
internal class RawRows(
    private val bytes: ByteArray,
    private val size: Int,
    private val encoding: TextEncoding,
    private var position: Int,
) : CharSequence {
    private val unit = encoding.unitSize
    private var start = position
    private var end = position
    private var last = false

    /** Whether the current row ends its line (it is not a piece of a longer line cut at [TextDocument.ROW_BYTES]). */
    var endsLine = false
        private set

    /** The bytes the current row takes, its line break included. */
    var byteLength = 0
        private set

    /** Moves to the next row — the first one at the first call; false when there is none. */
    fun next(): Boolean {
        if (last) return false
        start = position
        position = TextDocument.nextRow(bytes, size, start, encoding)
        last = position >= size
        endsLine = RowEnds.endsLine(bytes, start, position, last, encoding)
        byteLength = position - start
        end = RowEnds.contentEnd(bytes, start, position, encoding)
        return true
    }

    override val length: Int get() = (end - start) / unit

    override fun get(index: Int): Char {
        val p = start + index * unit
        return when (encoding) {
            TextEncoding.UTF_16LE -> ((bytes[p + 1].toInt() and BYTE) shl BITS or (bytes[p].toInt() and BYTE)).toChar()
            TextEncoding.UTF_16BE -> ((bytes[p].toInt() and BYTE) shl BITS or (bytes[p + 1].toInt() and BYTE)).toChar()
            else -> (bytes[p].toInt() and BYTE).toChar()
        }
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        buildString(endIndex - startIndex) { for (i in startIndex until endIndex) append(get(i)) }

    override fun toString(): String = subSequence(0, length).toString()

    private companion object {
        const val BYTE = 0xFF
        const val BITS = 8
    }
}

/** Where a row's text and its line end: one rule for [TextDocument.row] and [RawRows]. */
internal object RowEnds {
    /**
     * Whether the row from [start] until [end] (where the next one starts) ends its line: the [last] row
     * does, any other one when it ends with a line feed — else it was cut at [TextDocument.ROW_BYTES].
     */
    fun endsLine(bytes: ByteArray, start: Int, end: Int, last: Boolean, encoding: TextEncoding): Boolean =
        last || end - start >= encoding.unitSize && TextDocument.isLineFeed(bytes, end - encoding.unitSize, encoding)

    /** Where the text of the row from [start] until [end] ends: before its LF, and a CR before it. */
    fun contentEnd(bytes: ByteArray, start: Int, end: Int, encoding: TextEncoding): Int {
        val unit = encoding.unitSize
        var content = end
        if (content - start >= unit && TextDocument.isLineFeed(bytes, content - unit, encoding)) content -= unit
        if (content - start >= unit && TextDocument.isUnit(bytes, content - unit, encoding, TextDocument.CR)) content -= unit
        return content
    }
}
