package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** A file has more lines than the comparison reads ([FileCaps.DIFF_LINES]). */
class TooManyLinesException(val limit: Int) : IOException("more than $limit lines")

/**
 * A text file's lines for the comparison: its text in UTF-8 — decoded from the encoding the text viewer
 * finds, so a file saved in UTF-16 or Windows-1252 compares with its UTF-8 copy — and where each line
 * ends. A line ends at a LF (as in the text viewer); a last line without one is a line, a LF at the very
 * end opens none. Nothing is decoded but the lines shown.
 */
class TextLines private constructor(private val bytes: ByteArray, private val from: Int, private val ends: IntArray) {
    val count: Int get() = ends.size

    internal fun start(line: Int): Int = if (line == 0) from else ends[line - 1] + 1

    /** Where [line] ends, before its LF — and before its CR too, unless [lineEndings] count. */
    internal fun end(line: Int, lineEndings: Boolean): Int = if (!lineEndings && hasCr(line)) ends[line] - 1 else ends[line]

    /** Whether [line] ends with a CR (a Windows line break). */
    fun hasCr(line: Int): Boolean = ends[line] > start(line) && bytes[ends[line] - 1] == CR

    internal fun sameText(line: Int, other: TextLines, otherLine: Int, lineEndings: Boolean): Boolean {
        val start = start(line)
        val length = end(line, lineEndings) - start
        val otherStart = other.start(otherLine)
        if (other.end(otherLine, lineEndings) - otherStart != length) return false
        var k = 0
        while (k < length && bytes[start + k] == other.bytes[otherStart + k]) k++
        return k == length
    }

    internal fun hash(line: Int, lineEndings: Boolean): Int {
        var h = 0
        for (p in start(line) until end(line, lineEndings)) h = HASH_MULTIPLIER * h + bytes[p]
        // Spread the low bits, which pick the slot.
        return h xor (h ushr HASH_SHIFT)
    }

    /** [line]'s text without its line break; past [maxBytes], cut before a character and marked. */
    fun text(line: Int, maxBytes: Int): String {
        val start = start(line)
        val end = end(line, lineEndings = false)
        if (end - start <= maxBytes) return String(bytes, start, end - start, Charsets.UTF_8)
        var cut = start + maxBytes
        while (cut > start && bytes[cut].toInt() and CONTINUATION_MASK == CONTINUATION) cut--
        return String(bytes, start, cut - start, Charsets.UTF_8) + ELLIPSIS
    }

    companion object {
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val HASH_MULTIPLIER = 31
        private const val HASH_SHIFT = 16
        private const val CONTINUATION_MASK = 0xC0
        private const val CONTINUATION = 0x80
        private const val CANCEL_CHECK_BYTES = 1 shl 20
        private const val ELLIPSIS = "…"

        /**
         * The lines of the first [size] bytes of [bytes] — kept, not copied, when they are UTF-8.
         *
         * @throws TooManyLinesException more than [maxLines].
         */
        suspend fun of(bytes: ByteArray, size: Int, maxLines: Int = FileCaps.DIFF_LINES): TextLines {
            val encoding = TextEncoding.detect(bytes, size)
            val utf8: ByteArray
            val from: Int
            val to: Int
            if (encoding == TextEncoding.UTF_8 || encoding == TextEncoding.UTF_8_BOM) {
                utf8 = bytes
                from = encoding.bomLength
                to = size
            } else {
                utf8 = String(bytes, encoding.bomLength, size - encoding.bomLength, encoding.charset).encodeToByteArray()
                from = 0
                to = utf8.size
            }
            var count = 0
            for (p in from until to) {
                if ((p - from) % CANCEL_CHECK_BYTES == 0) currentCoroutineContext().ensureActive()
                if (utf8[p] == LF) count++
            }
            if (to > from && utf8[to - 1] != LF) count++
            if (count > maxLines) throw TooManyLinesException(maxLines)
            val ends = IntArray(count)
            var line = 0
            for (p in from until to) if (utf8[p] == LF) ends[line++] = p
            if (line < count) ends[line] = to
            return TextLines(utf8, from, ends)
        }
    }
}

/** What a row of the comparison shows. */
enum class DiffKind { SAME, REMOVED, ADDED, SKIPPED }

/**
 * A row of the comparison: a line of the first file, of the second or of both, [lineA] and [lineB]
 * counted from 1 (0 where the line is not in that file) — or, [DiffKind.SKIPPED], [skipped] unchanged
 * lines not shown.
 */
data class DiffRow(val kind: DiffKind, val lineA: Int, val lineB: Int, val text: String, val skipped: Int = 0)

/**
 * Two files compared line by line, counted on the whole files: the lines [removed] from the first and
 * [added] in the second, and the first [FileCaps.DIFF_ROWS] rows — each change with [LineDiff.CONTEXT]
 * unchanged lines around it, longer unchanged runs folded. [approximate]: the files differ too much for the
 * work allowed ([LineDiff.WORK]), and part of the comparison shows unchanged lines as removed and added.
 */
data class LineComparison(
    val removed: Int,
    val added: Int,
    val rows: List<DiffRow>,
    val truncated: Boolean,
    val approximate: Boolean,
) {
    val sameLines: Boolean get() = removed == 0 && added == 0
}

/**
 * The comparison of the diff tool (`DiffScreen` of the 2.15.3). Myers' algorithm in linear space ("An
 * O(ND) Difference Algorithm and Its Variations", 1986: the middle snake, as diff-match-patch writes it),
 * over the lines numbered by their text: a shortest edit script, in time (N + M) · D and memory N + M.
 *
 * The 2.15.3 (inventory F5) read both files whole on the main thread (`diff_screen.dart:58-59`), built
 * the n × m table of a longest common subsequence there (`:111-137`) — so it compared only the first 500
 * lines of each, and said the rest was cut — never caught a failure (an endless spinner), split on `\n`
 * only, so every line of a CRLF file differed from its LF copy, and never said two files were the same
 * (`:336-343` could not be reached). Here the whole files are compared, off the main thread, cancellable;
 * a line's CR counts only when asked.
 */
object LineDiff {
    /** Unchanged lines shown before and after a change. */
    const val CONTEXT = 3

    /**
     * Steps of the search allowed — about a second on a phone: past it, what remains to compare is shown
     * as removed and added, and the result says it is approximate. Two files with a few thousand changes
     * stay well under it; two unrelated files of a million lines would take hours.
     */
    const val WORK = 100_000_000L

    /** Whether the first [aSize] bytes of [a] are the first [bSize] of [b]. */
    fun sameBytes(a: ByteArray, aSize: Int, b: ByteArray, bSize: Int): Boolean {
        if (aSize != bSize) return false
        var p = 0
        while (p < aSize && a[p] == b[p]) p++
        return p == aSize
    }

    suspend fun compare(
        a: TextLines,
        b: TextLines,
        lineEndings: Boolean,
        maxRows: Int = FileCaps.DIFF_ROWS,
        work: Long = WORK,
    ): LineComparison {
        val ids = LineIds(a, b, lineEndings)
        val idsA = ids.of(a)
        val idsB = ids.of(b)
        val myers = Myers(idsA, idsB, work)
        val script = myers.run()
        val rows = Rows(a, b, lineEndings, maxRows)
        rows.build(script)
        return LineComparison(script.count(REMOVED), script.count(ADDED), rows.shown, rows.truncated, myers.approximate)
    }

    internal const val SAME = 0
    internal const val REMOVED = 1
    internal const val ADDED = 2

    /** The edit script of [a] into [b], as runs: what the JVM tests read. */
    internal suspend fun script(a: IntArray, b: IntArray, work: Long = WORK): Script = Myers(a, b, work).run()
}

/** Runs of lines kept, removed or added, in order: each its operation and its length, in one Int. */
internal class Script {
    private var runs = IntArray(INITIAL)
    var size: Int = 0
        private set

    fun op(run: Int): Int = runs[run] and OP_MASK

    fun length(run: Int): Int = runs[run] ushr OP_BITS

    fun add(op: Int, length: Int) {
        if (length == 0) return
        if (size > 0 && op(size - 1) == op) {
            runs[size - 1] += length shl OP_BITS
            return
        }
        if (size == runs.size) runs = runs.copyOf(size * 2)
        runs[size++] = (length shl OP_BITS) or op
    }

    /** The lines of runs [from] until [to] that are [op]. */
    fun count(op: Int, from: Int = 0, to: Int = size): Int = (from until to).sumOf { if (op(it) == op) length(it) else 0 }

    private companion object {
        const val INITIAL = 16
        const val OP_BITS = 2
        const val OP_MASK = 3
    }
}

/** Each distinct line text of two files numbered once: lines are then compared as Ints. */
private class LineIds(private val a: TextLines, private val b: TextLines, private val lineEndings: Boolean) {
    private val mask: Int
    private val slots: IntArray
    private val slotHashes: IntArray

    /** The first line seen of each number: `line` of [a], or `-(line + 1)` of [b]. */
    private val firsts = IntArray(a.count + b.count)
    private var next = 0

    init {
        var size = MIN_SLOTS
        while (size < 2 * (a.count + b.count)) size *= 2
        mask = size - 1
        slots = IntArray(size)
        slotHashes = IntArray(size)
    }

    suspend fun of(lines: TextLines): IntArray {
        val ids = IntArray(lines.count)
        for (line in 0 until lines.count) {
            if (line % CANCEL_CHECK_LINES == 0) currentCoroutineContext().ensureActive()
            ids[line] = id(lines, line)
        }
        return ids
    }

    private fun id(lines: TextLines, line: Int): Int {
        val hash = lines.hash(line, lineEndings)
        var slot = hash and mask
        while (true) {
            val entry = slots[slot]
            if (entry == 0) {
                slots[slot] = next + 1
                slotHashes[slot] = hash
                firsts[next] = if (lines === a) line else -(line + 1)
                return next++
            }
            if (slotHashes[slot] == hash && sameAsFirst(entry - 1, lines, line)) return entry - 1
            slot = (slot + 1) and mask
        }
    }

    private fun sameAsFirst(id: Int, lines: TextLines, line: Int): Boolean {
        val first = firsts[id]
        return if (first >= 0) a.sameText(first, lines, line, lineEndings) else b.sameText(-first - 1, lines, line, lineEndings)
    }

    private companion object {
        const val MIN_SLOTS = 16
        const val CANCEL_CHECK_LINES = 65_536
    }
}

/** Myers' linear-space search, within [budget] steps. */
private class Myers(private val a: IntArray, private val b: IntArray, private var budget: Long) {
    private val script = Script()

    var approximate = false
        private set

    suspend fun run(): Script {
        diff(0, a.size, 0, b.size)
        return script
    }

    /** Lines [aLo] until [aHi] of the first file into [bLo] until [bHi] of the second. */
    private suspend fun diff(aLo: Int, aHi: Int, bLo: Int, bHi: Int) {
        var prefix = 0
        while (aLo + prefix < aHi && bLo + prefix < bHi && a[aLo + prefix] == b[bLo + prefix]) prefix++
        var suffix = 0
        while (aHi - suffix > aLo + prefix && bHi - suffix > bLo + prefix && a[aHi - 1 - suffix] == b[bHi - 1 - suffix]) suffix++
        script.add(LineDiff.SAME, prefix)
        middle(aLo + prefix, aHi - suffix, bLo + prefix, bHi - suffix)
        script.add(LineDiff.SAME, suffix)
    }

    /** What lies between a common start and a common end: no line of it begins or ends both. */
    private suspend fun middle(aLo: Int, aHi: Int, bLo: Int, bHi: Int) {
        val n = aHi - aLo
        val m = bHi - bLo
        when {
            n == 0 || m == 0 -> replace(n, m)
            n == 1 -> if (!keepOne(a[aLo], b, bLo, bHi, LineDiff.ADDED)) replace(n, m)
            m == 1 -> if (!keepOne(b[bLo], a, aLo, aHi, LineDiff.REMOVED)) replace(n, m)
            else -> {
                val split = bisect(aLo, n, bLo, m)
                if (split < 0) {
                    replace(n, m)
                } else {
                    val x = (split ushr Int.SIZE_BITS).toInt()
                    val y = split.toInt()
                    diff(aLo, aLo + x, bLo, bLo + y)
                    diff(aLo + x, aHi, bLo + y, bHi)
                }
            }
        }
    }

    private fun replace(n: Int, m: Int) {
        script.add(LineDiff.REMOVED, n)
        script.add(LineDiff.ADDED, m)
    }

    /**
     * One line against [lo] until [hi] of [other]: kept where [other] has it first, the rest of [other]
     * [otherOp] — or false, nothing written, when [other] has it not.
     */
    private fun keepOne(line: Int, other: IntArray, lo: Int, hi: Int, otherOp: Int): Boolean {
        var at = lo
        while (at < hi && other[at] != line) at++
        if (at < hi) {
            script.add(otherOp, at - lo)
            script.add(LineDiff.SAME, 1)
            script.add(otherOp, hi - at - 1)
        }
        return at < hi
    }

    /**
     * The middle snake of the two ranges, both of at least two lines, packed `(x shl 32) or y` — the point
     * where a shortest path crosses the middle — or -1: no line in common, or the budget spent.
     */
    // CyclomaticComplexMethod, LongMethod, NestedBlockDepth, ReturnCount: the paper's two walks side by side, as written.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ReturnCount")
    private suspend fun bisect(aLo: Int, n: Int, bLo: Int, m: Int): Long {
        val maxD = (n + m + 1) / 2
        val offset = maxD
        val length = 2 * maxD
        val v1 = IntArray(length) { -1 }
        val v2 = IntArray(length) { -1 }
        v1[offset + 1] = 0
        v2[offset + 1] = 0
        val delta = n - m
        // Odd: the forward walk meets the reverse one; even: the reverse meets the forward.
        val front = delta % 2 != 0
        var k1start = 0
        var k1end = 0
        var k2start = 0
        var k2end = 0
        for (d in 0 until maxD) {
            currentCoroutineContext().ensureActive()
            if (budget < 0) {
                approximate = true
                return -1
            }
            var k1 = -d + k1start
            while (k1 <= d - k1end) {
                val k1Offset = offset + k1
                var x1 = if (k1 == -d || (k1 != d && v1[k1Offset - 1] < v1[k1Offset + 1])) v1[k1Offset + 1] else v1[k1Offset - 1] + 1
                var y1 = x1 - k1
                val x1Start = x1
                while (x1 < n && y1 < m && a[aLo + x1] == b[bLo + y1]) {
                    x1++
                    y1++
                }
                budget -= 1 + x1 - x1Start
                v1[k1Offset] = x1
                if (x1 > n) {
                    k1end += 2
                } else if (y1 > m) {
                    k1start += 2
                } else if (front) {
                    val k2Offset = offset + delta - k1
                    if (k2Offset in 0 until length && v2[k2Offset] != -1 && x1 >= n - v2[k2Offset]) return pack(x1, y1)
                }
                k1 += 2
            }
            var k2 = -d + k2start
            while (k2 <= d - k2end) {
                val k2Offset = offset + k2
                var x2 = if (k2 == -d || (k2 != d && v2[k2Offset - 1] < v2[k2Offset + 1])) v2[k2Offset + 1] else v2[k2Offset - 1] + 1
                var y2 = x2 - k2
                val x2Start = x2
                while (x2 < n && y2 < m && a[aLo + n - x2 - 1] == b[bLo + m - y2 - 1]) {
                    x2++
                    y2++
                }
                budget -= 1 + x2 - x2Start
                v2[k2Offset] = x2
                if (x2 > n) {
                    k2end += 2
                } else if (y2 > m) {
                    k2start += 2
                } else if (!front) {
                    val k1Offset = offset + delta - k2
                    if (k1Offset in 0 until length && v1[k1Offset] != -1) {
                        val x1 = v1[k1Offset]
                        val y1 = offset + x1 - k1Offset
                        if (x1 >= n - x2) return pack(x1, y1)
                    }
                }
                k2 += 2
            }
        }
        return -1
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl Int.SIZE_BITS) or y.toLong()
}

/** The rows shown of a script: changes with their context, long unchanged runs folded, at most [maxRows]. */
private class Rows(private val a: TextLines, private val b: TextLines, private val lineEndings: Boolean, private val maxRows: Int) {
    val shown = ArrayList<DiffRow>()
    var truncated = false
        private set

    fun build(script: Script) {
        var aPos = 0
        var bPos = 0
        var run = 0
        while (run < script.size && !truncated) {
            if (script.op(run) == LineDiff.SAME) {
                val length = script.length(run)
                same(aPos, bPos, length, leading = run == 0, trailing = run == script.size - 1)
                aPos += length
                bPos += length
                run++
            } else {
                // A change: its removed lines are consecutive in the first file, its added ones in the second.
                var end = run
                while (end < script.size && script.op(end) != LineDiff.SAME) end++
                val removed = script.count(LineDiff.REMOVED, run, end)
                val added = script.count(LineDiff.ADDED, run, end)
                repeat(minOf(removed, room())) { add(DiffRow(DiffKind.REMOVED, aPos + it + 1, 0, text(a, aPos + it))) }
                repeat(minOf(added, room())) { add(DiffRow(DiffKind.ADDED, 0, bPos + it + 1, text(b, bPos + it))) }
                aPos += removed
                bPos += added
                run = end
            }
        }
    }

    /** An unchanged run: [LineDiff.CONTEXT] lines after the change before it and before the change after it, the rest folded. */
    private fun same(aStart: Int, bStart: Int, length: Int, leading: Boolean, trailing: Boolean) {
        // Two identical files: nothing to show.
        if (leading && trailing) return
        val head = if (leading) 0 else minOf(LineDiff.CONTEXT, length)
        val tail = if (trailing) 0 else minOf(LineDiff.CONTEXT, length - head)
        repeat(minOf(head, room())) { add(sameRow(aStart + it, bStart + it)) }
        val hidden = length - head - tail
        if (hidden > 0) add(DiffRow(DiffKind.SKIPPED, 0, 0, "", skipped = hidden))
        val from = length - tail
        repeat(minOf(tail, room())) { add(sameRow(aStart + from + it, bStart + from + it)) }
    }

    /** Rows that may still be added, plus one: the one that finds the bound reached, and says so. */
    private fun room(): Int = minOf(maxRows - shown.size, Int.MAX_VALUE - 1) + 1

    private fun sameRow(aLine: Int, bLine: Int) = DiffRow(DiffKind.SAME, aLine + 1, bLine + 1, text(a, aLine))

    private fun text(lines: TextLines, line: Int): String =
        lines.text(line, TextDocument.ROW_BYTES) + if (lineEndings && lines.hasCr(line)) CR_MARK else ""

    private fun add(row: DiffRow) {
        if (shown.size == maxRows) truncated = true else shown += row
    }

    private companion object {
        /** A line's CR, shown when line endings count. */
        val CR_MARK = Char(0x240D).toString()
    }
}
