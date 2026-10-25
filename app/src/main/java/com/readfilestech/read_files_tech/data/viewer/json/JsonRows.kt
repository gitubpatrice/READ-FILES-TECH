package com.readfilestech.read_files_tech.data.viewer.json

import java.util.BitSet

/**
 * The rows of the tree view (`_JsonNode` of the 2.15.3): the document's value, opened, then the children
 * of each container the user opens. Only the rows' values are kept — a row is laid out when it is shown;
 * the 2.15.3 built a widget for every child of an opened node at once. Closing a container closes all
 * it showed, as the 2.15.3's did (its children's state went with their widgets).
 *
 * Not thread-safe: built once off the main thread, then changed on it only.
 */
class JsonTree(private val document: JsonDocument) {
    private var values = IntArray(INITIAL_ROWS)
    private var ordinals = IntArray(INITIAL_ROWS)
    private val opened = BitSet(document.valueCount)
    private val counts = HashMap<Int, Int>()

    var rowCount: Int = 1
        private set

    init {
        if (document.isContainer(0)) toggle(0)
    }

    /** The value at [row]. */
    fun value(row: Int): Int = values[row]

    /** The position of the value at [row] in its array or object, from 0. */
    fun ordinal(row: Int): Int = ordinals[row]

    fun isOpen(value: Int): Boolean = opened[value]

    /** How many values [container] holds, counted once. */
    fun childCount(container: Int): Int = counts.getOrPut(container) { document.childCount(container) }

    /** Opens the container at [row], or closes it and all it showed; nothing for a scalar. */
    fun toggle(row: Int) {
        val value = values[row]
        if (!document.isContainer(value)) return
        if (opened[value]) close(row, value) else open(row, value)
    }

    private fun open(row: Int, value: Int) {
        opened.set(value)
        val children = childCount(value)
        if (children == 0) return
        ensureRoom(rowCount + children)
        values.copyInto(values, row + 1 + children, row + 1, rowCount)
        ordinals.copyInto(ordinals, row + 1 + children, row + 1, rowCount)
        document.children(value).forEachIndexed { ordinal, child ->
            values[row + 1 + ordinal] = child
            ordinals[row + 1 + ordinal] = ordinal
        }
        rowCount += children
    }

    private fun close(row: Int, value: Int) {
        val next = document.next(value)
        opened.clear(value, next)
        var end = row + 1
        while (end < rowCount && values[end] < next) end++
        values.copyInto(values, row + 1, end, rowCount)
        ordinals.copyInto(ordinals, row + 1, end, rowCount)
        rowCount -= end - row - 1
    }

    private fun ensureRoom(rows: Int) {
        if (rows <= values.size) return
        val size = maxOf(rows, values.size + values.size / 2)
        values = values.copyOf(size)
        ordinals = ordinals.copyOf(size)
    }

    private companion object {
        const val INITIAL_ROWS = 64
    }
}

/**
 * The pretty view (the 2.15.3's "Texte brut", `JsonEncoder.withIndent('  ')`): every value on its line,
 * indented, and a line closing each array or object that holds something — the values as written, where
 * the 2.15.3 printed them again from its decoded copy. A line is made when it is shown.
 */
class JsonPretty(private val document: JsonDocument) {
    /** A value's line, or `-(value + 1)` for the line closing it. */
    private val rows: IntArray

    val rowCount: Int get() = rows.size

    init {
        val count = document.valueCount
        var lines = count
        for (value in 0 until count) if (document.next(value) > value + 1) lines++
        rows = IntArray(lines)
        val open = IntArray(maxDepth(count) + 1)
        var depth = 0
        var line = 0
        for (value in 0 until count) {
            while (depth > 0 && document.next(open[depth - 1]) <= value) rows[line++] = -(open[--depth] + 1)
            rows[line++] = value
            if (document.next(value) > value + 1) open[depth++] = value
        }
        while (depth > 0) rows[line++] = -(open[--depth] + 1)
    }

    /** The text of line [row], its values cut past [maxChars] characters. */
    fun line(row: Int, maxChars: Int): String {
        val entry = rows[row]
        val closing = entry < 0
        val value = if (closing) -entry - 1 else entry
        val text = StringBuilder(INDENT.repeat(minOf(document.depth(value), MAX_INDENT_LEVELS)))
        val kind = document.kind(value)
        val container = kind == JsonKind.OBJECT || kind == JsonKind.ARRAY
        val holds = document.next(value) > value + 1
        if (!closing) document.keyLiteral(value, maxChars)?.let { text.append(it).append(": ") }
        val (opening, closer) = if (kind == JsonKind.OBJECT) '{' to '}' else '[' to ']'
        when {
            closing -> text.append(closer)
            container -> text.append(opening).apply { if (!holds) append(closer) }
            else -> text.append(document.literal(value, maxChars))
        }
        // An opened container's comma is on its closing line.
        if ((closing || !holds) && document.hasNextSibling(value)) text.append(',')
        return text.toString()
    }

    private fun maxDepth(count: Int): Int {
        var deepest = 0
        for (value in 0 until count) deepest = maxOf(deepest, document.depth(value))
        return deepest
    }

    companion object {
        private const val INDENT = "  "

        /** Deeper values are indented no further: the line keeps room for its text. */
        const val MAX_INDENT_LEVELS = 16
    }
}
