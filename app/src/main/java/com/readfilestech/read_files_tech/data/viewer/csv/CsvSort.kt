package com.readfilestech.read_files_tech.data.viewer.csv

import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/**
 * The keys of one column, built once on IO: for each row its kind — empty, a number, text — and its
 * number, or where its text lies in the file and its first characters. 17 bytes a row, whatever the
 * cells hold: a text is read from the file ([FieldChars]) only when two cells begin alike, never copied.
 */
class CsvColumnKeys internal constructor(
    val column: Int,
    private val kinds: ByteArray,
    private val keys: LongArray,
    private val prefixes: LongArray,
) {
    internal fun kind(row: Int): Byte = kinds[row]

    internal fun number(row: Int): Double = Double.fromBits(keys[row])

    internal fun start(row: Int): Int = (keys[row] ushr Int.SIZE_BITS).toInt()

    internal fun end(row: Int): Int = keys[row].toInt()

    /** A text's first characters, lower-cased, and how many it has — see [CsvSort.prefixOf]. */
    internal fun prefix(row: Int): Long = prefixes[row]
}

/**
 * The sort of the 2.15.3 (`_sort`: by a column, numeric when both cells are numbers, again to reverse),
 * made a total order. The 2.15.3 compared two numbers as numbers and anything else as strings, which
 * is no order at all (`9` < `10` as numbers, `10` < `1a` and `1a` < `9` as strings): Java's sort would
 * throw on it, and Dart's gave whatever came. Here empty cells come first, then numbers by value
 * ([CsvNumbers]), then text, case ignored as the explorer's names are (`Zoé` no longer before
 * `albert`); equal cells keep the file's order, in either direction (the 2.15.3's sort was not
 * stable). Cancelled at once when a new sort replaces it.
 */
object CsvSort {
    internal const val EMPTY: Byte = 0
    internal const val NUMBER: Byte = 1
    internal const val TEXT: Byte = 2

    /** Longer cells are read this far to decide whether they are a number: none is that long. */
    private const val NUMBER_CHARS = 1024

    suspend fun keys(table: CsvTable, column: Int): CsvColumnKeys {
        require(column in 0 until table.columns) { "column $column of ${table.columns}" }
        val context = currentCoroutineContext()
        val kinds = ByteArray(table.rows)
        val keys = LongArray(table.rows)
        val prefixes = LongArray(table.rows)
        val chars = table.fieldChars()
        val decimalComma = table.separator != ','
        for (row in 0 until table.rows) {
            if (row % CANCEL_CHECK_EVERY == 0) context.ensureActive()
            // A row shorter than the column has an empty cell there: its kind stays EMPTY.
            table.scan(row) { index, start, end ->
                if (index == column) {
                    val head = table.fieldText(start, end, NUMBER_CHARS + 1)
                    val number = if (head.length <= NUMBER_CHARS) CsvNumbers.parse(head, decimalComma) else null
                    when {
                        number != null -> {
                            kinds[row] = NUMBER
                            keys[row] = number.toRawBits()
                        }
                        head.isEmpty() -> kinds[row] = EMPTY
                        else -> {
                            kinds[row] = TEXT
                            keys[row] = (start.toLong() shl Int.SIZE_BITS) or (end.toLong() and INT_MASK)
                            prefixes[row] = prefixOf(chars, start, end)
                        }
                    }
                }
                index < column
            }
        }
        return CsvColumnKeys(column, kinds, keys, prefixes)
    }

    /**
     * The first [PREFIX_CHARS] characters of a text cell, lower-cased, 16 bits each from the highest, then
     * how many it has — [PREFIX_CHARS] + 1 for "more". Compared unsigned, two prefixes order their cells
     * as [compareText] would, but for two longer cells that begin alike: a column of a few distinct
     * words (a category, a town) sorts without reading them again.
     */
    internal fun prefixOf(chars: FieldChars, start: Int, end: Int): Long {
        chars.reset(start, end)
        var prefix = 0L
        var count = 0
        var next = chars.next()
        while (next >= 0 && count <= PREFIX_CHARS) {
            val lower = Character.toLowerCase(next).toLong() and CHAR_MASK
            if (count < PREFIX_CHARS) prefix = prefix or (lower shl (CHAR_BITS * (PREFIX_CHARS - count)))
            count++
            next = if (count <= PREFIX_CHARS) chars.next() else -1
        }
        return prefix or count.toLong()
    }

    /** The rows of [table] in the order of [keys], stable; [ascending] false is the exact reverse but for equal cells. */
    suspend fun order(table: CsvTable, keys: CsvColumnKeys, ascending: Boolean): IntArray {
        val rows = IntArray(table.rows) { it }
        val a = table.fieldChars()
        val b = table.fieldChars()
        val direction = if (ascending) 1 else -1
        sortStable(rows, currentCoroutineContext()) { x, y -> direction * compare(keys, x, y, a, b) }
        return rows
    }

    private fun compare(keys: CsvColumnKeys, x: Int, y: Int, a: FieldChars, b: FieldChars): Int {
        val kind = keys.kind(x)
        return when {
            kind != keys.kind(y) -> kind - keys.kind(y)
            kind == NUMBER -> keys.number(x).compareTo(keys.number(y))
            kind == TEXT -> {
                val prefix = keys.prefix(x)
                val byPrefix = java.lang.Long.compareUnsigned(prefix, keys.prefix(y))
                if (byPrefix != 0 || (prefix and CHAR_MASK) <= PREFIX_CHARS) {
                    byPrefix
                } else {
                    a.reset(keys.start(x), keys.end(x))
                    b.reset(keys.start(y), keys.end(y))
                    compareText(a, b)
                }
            }
            else -> 0
        }
    }

    /** Character by character, case ignored, over the first [FileCaps.CSV_CELL_CHARS]: what is shown of a cell. */
    internal fun compareText(a: FieldChars, b: FieldChars): Int {
        var result = 0
        var read = 0
        while (result == 0 && read < FileCaps.CSV_CELL_CHARS) {
            val x = a.next()
            val y = b.next()
            if (x < 0 || y < 0) {
                result = x.compareTo(y)
                break
            }
            result = Character.toLowerCase(x).compareTo(Character.toLowerCase(y))
            read++
        }
        return result
    }

    /**
     * A merge sort of [rows] by [compare], stable: runs of [RUN] sorted by insertion, then merged in
     * passes. Checks [context] every [CANCEL_CHECK_EVERY] rows written, so that a new sort or a new
     * search does not wait for this one.
     */
    internal fun sortStable(rows: IntArray, context: CoroutineContext, compare: (Int, Int) -> Int) {
        val size = rows.size
        for (from in 0 until size step RUN) {
            context.ensureActive()
            insertionSort(rows, from, minOf(from + RUN, size), compare)
        }
        var source = rows
        var target = IntArray(size)
        var width = RUN
        while (width < size) {
            var low = 0
            while (low < size) {
                val middle = minOf(low + width, size)
                val high = minOf(low + 2 * width, size)
                merge(source, target, low, middle, high, context, compare)
                low = high
            }
            val swap = source
            source = target
            target = swap
            width *= 2
        }
        if (source !== rows) source.copyInto(rows)
    }

    private fun insertionSort(rows: IntArray, from: Int, until: Int, compare: (Int, Int) -> Int) {
        for (i in from + 1 until until) {
            val row = rows[i]
            var j = i - 1
            while (j >= from && compare(rows[j], row) > 0) {
                rows[j + 1] = rows[j]
                j--
            }
            rows[j + 1] = row
        }
    }

    @Suppress("LongParameterList") // the two arrays and the three bounds of one merge, and how to compare
    private fun merge(
        source: IntArray,
        target: IntArray,
        low: Int,
        middle: Int,
        high: Int,
        context: CoroutineContext,
        compare: (Int, Int) -> Int,
    ) {
        var left = low
        var right = middle
        for (k in low until high) {
            if (k % CANCEL_CHECK_EVERY == 0) context.ensureActive()
            // `<= 0`: on a tie the left one, earlier in the file, comes first — the sort is stable.
            target[k] = if (right >= high || left < middle && compare(source[left], source[right]) <= 0) source[left++] else source[right++]
        }
    }

    private const val RUN = 32
    private const val CANCEL_CHECK_EVERY = 1 shl 14
    private const val INT_MASK = 0xFFFF_FFFFL
    private const val PREFIX_CHARS = 3
    private const val CHAR_BITS = 16
    private const val CHAR_MASK = 0xFFFFL
}
