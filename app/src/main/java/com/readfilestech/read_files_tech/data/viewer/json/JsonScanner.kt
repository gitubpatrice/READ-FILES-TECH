package com.readfilestech.read_files_tech.data.viewer.json

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Why a scan stopped short of a tree: [offset] is the byte where it did. */
internal class JsonRefused(val reason: Reason, val offset: Int) : Exception(reason.name) {
    enum class Reason { SYNTAX, TOO_DEEP, TOO_MANY_VALUES }
}

/**
 * One pass over a JSON text (RFC 8259), without recursion: the arrays and objects open around the value
 * read are an explicit stack of at most [JsonLimits.depth]. Each value is reported to a [Sink] in document
 * order — its index, the offset it records (its key's, for a member) and its depth — and each array or
 * object again when it closes, with the index of the value after it.
 *
 * Strict: no comment, no trailing comma, no NaN or Infinity, no control character in a string. The text is
 * read in its code units ([JsonText]) from [start], the byte after its BOM.
 */
// TooManyFunctions: one function per production of the grammar (RFC 8259), as a recursive descent has.
@Suppress("TooManyFunctions")
internal class JsonScanner(
    private val text: JsonText,
    private val size: Int,
    start: Int,
    private val limits: JsonLimits,
) {
    interface Sink {
        fun value(index: Int, start: Int, depth: Int)

        fun close(index: Int, next: Int)
    }

    /** The first pass: checks and counts, keeps nothing. */
    object Counting : Sink {
        override fun value(index: Int, start: Int, depth: Int) = Unit

        override fun close(index: Int, next: Int) = Unit
    }

    /** The second pass, into arrays of the size the first one counted. */
    class Filling(count: Int) : Sink {
        val starts = IntArray(count)
        val nexts = IntArray(count)
        val depths = ShortArray(count)

        override fun value(index: Int, start: Int, depth: Int) {
            starts[index] = start
            nexts[index] = index + 1
            depths[index] = depth.toShort()
        }

        override fun close(index: Int, next: Int) {
            nexts[index] = next
        }
    }

    private val unit = text.unit
    private val open = IntArray(limits.depth)
    private val objects = BooleanArray(limits.depth)
    private var depth = 0
    private var count = 0
    private var p = start

    /** The number of values, once the whole text is read and found valid. */
    suspend fun scan(sink: Sink): Int {
        skipSpace()
        var start = p
        while (start != END) {
            if (count % CANCEL_CHECK_VALUES == 0) currentCoroutineContext().ensureActive()
            if (count == limits.values) throw JsonRefused(JsonRefused.Reason.TOO_MANY_VALUES, p)
            val index = count++
            sink.value(index, start, depth)
            start = nextValue(value(index), sink)
        }
        return count
    }

    /** Reads the value at [p]; true when it opened an array or an object. */
    private fun value(index: Int): Boolean = when (unitAt(p)) {
        '{'.code -> open(index, isObject = true)
        '['.code -> open(index, isObject = false)
        else -> {
            scalar()
            false
        }
    }

    private fun scalar() {
        when (unitAt(p)) {
            '"'.code -> string()
            't'.code -> word("true")
            'f'.code -> word("false")
            'n'.code -> word("null")
            else -> number()
        }
    }

    private fun open(index: Int, isObject: Boolean): Boolean {
        if (depth == limits.depth) throw JsonRefused(JsonRefused.Reason.TOO_DEEP, p)
        open[depth] = index
        objects[depth] = isObject
        depth++
        p += unit
        return true
    }

    /**
     * Past the commas and the closing brackets after a value, to the next one: the offset it records, [p]
     * at its first character — or [END] after the document's value.
     */
    private fun nextValue(opened: Boolean, sink: Sink): Int {
        var first = opened
        while (true) {
            skipSpace()
            if (depth == 0) {
                if (p < size) fail()
                return END
            }
            val isObject = objects[depth - 1]
            val c = unitAt(p)
            when {
                c == (if (isObject) '}' else ']').code -> {
                    p += unit
                    depth--
                    sink.close(open[depth], count)
                    first = false
                }
                first || c == ','.code -> {
                    if (!first) {
                        p += unit
                        skipSpace()
                    }
                    return if (isObject) member() else p
                }
                else -> fail()
            }
        }
    }

    /** A member's key and colon: its key's offset, [p] at its value. */
    private fun member(): Int {
        val key = p
        if (unitAt(p) != '"'.code) fail()
        string()
        skipSpace()
        if (unitAt(p) != ':'.code) fail()
        p += unit
        skipSpace()
        return key
    }

    private fun string() {
        p += unit
        while (true) {
            val c = unitAt(p)
            when {
                c == '"'.code -> {
                    p += unit
                    return
                }
                c == '\\'.code -> {
                    p += unit
                    escape()
                }
                // A control character, or the end of the text (-1).
                c < SPACE -> fail()
                else -> p += unit
            }
        }
    }

    private fun escape() {
        val c = unitAt(p)
        p += unit
        if (c == 'u'.code) {
            repeat(HEX_DIGITS) {
                if (!isHex(unitAt(p))) fail()
                p += unit
            }
        } else if (c !in SIMPLE_ESCAPES) {
            p -= unit
            fail()
        }
    }

    private fun number() {
        if (unitAt(p) == '-'.code) p += unit
        when (unitAt(p)) {
            '0'.code -> p += unit
            in '1'.code..'9'.code -> digits()
            else -> fail()
        }
        if (unitAt(p) == '.'.code) {
            p += unit
            digits()
        }
        if (unitAt(p) == 'e'.code || unitAt(p) == 'E'.code) {
            p += unit
            if (unitAt(p) == '+'.code || unitAt(p) == '-'.code) p += unit
            digits()
        }
    }

    /** One digit or more. */
    private fun digits() {
        if (unitAt(p) !in '0'.code..'9'.code) fail()
        while (unitAt(p) in '0'.code..'9'.code) p += unit
    }

    private fun word(word: String) {
        word.forEach { c ->
            if (unitAt(p) != c.code) fail()
            p += unit
        }
    }

    private fun skipSpace() {
        while (true) {
            when (unitAt(p)) {
                SPACE, TAB, LF, CR -> p += unit
                else -> return
            }
        }
    }

    private fun isHex(c: Int): Boolean = c in '0'.code..'9'.code || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code

    private fun unitAt(at: Int): Int = text.unitAt(at)

    private fun fail(): Nothing = throw JsonRefused(JsonRefused.Reason.SYNTAX, p)

    private companion object {
        const val END = -1
        const val SPACE = 0x20
        const val TAB = 0x09
        const val LF = 0x0A
        const val CR = 0x0D
        const val HEX_DIGITS = 4
        const val CANCEL_CHECK_VALUES = 65_536
        val SIMPLE_ESCAPES = "\"\\/bfnrt".map { it.code }.toSet()
    }
}
