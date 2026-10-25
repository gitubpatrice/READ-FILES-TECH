package com.readfilestech.read_files_tech.data.viewer.reader

/** How a block is shown: the 2.15.3's `ReaderBlock.type` (h1, h2, h3, p, quote, li). */
enum class BlockKind { HEADING_1, HEADING_2, HEADING_3, PARAGRAPH, QUOTE, ITEM }

/**
 * The blocks of a chapter or a page, in order: their texts in one string, a block's end and kind in two
 * arrays — some bytes per block beside its text, where a list of objects would cost tens.
 */
class ReaderBlocks internal constructor(
    private val text: String,
    private val ends: IntArray,
    private val kinds: ByteArray,
    private val from: Int,
    private val to: Int,
) {
    val size: Int get() = to - from

    /** The characters of these blocks' texts. */
    internal val chars: Int get() = if (size == 0) 0 else ends[to - 1] - (if (from == 0) 0 else ends[from - 1])

    fun kind(index: Int): BlockKind = KINDS[kinds[from + checked(index)].toInt()]

    fun text(index: Int): String {
        val at = from + checked(index)
        return text.substring(if (at == 0) 0 else ends[at - 1], ends[at])
    }

    /** The text of the first level-1 or level-2 heading: what the 2.15.3 titled a chapter by. */
    val firstHeading: String?
        get() = (0 until size).firstOrNull { kind(it) == BlockKind.HEADING_1 || kind(it) == BlockKind.HEADING_2 }?.let(::text)

    /** The blocks [start] to [end] (excluded), sharing these ones' text. */
    internal fun slice(start: Int, end: Int): ReaderBlocks {
        require(start in 0..end && end <= size) { "no such blocks" }
        return ReaderBlocks(text, ends, kinds, from + start, from + end)
    }

    private fun checked(index: Int): Int {
        if (index !in 0 until size) throw IndexOutOfBoundsException("block $index of $size")
        return index
    }

    companion object {
        private val KINDS = BlockKind.entries.toTypedArray()

        /** No block: a chapter or a page with no text to show. */
        val EMPTY = ReaderBlocks("", IntArray(0), ByteArray(0), 0, 0)
    }
}

/** One chapter of a book, or a page: the title it is listed by, and its blocks. */
class ReaderChapter(val title: String, val blocks: ReaderBlocks)
