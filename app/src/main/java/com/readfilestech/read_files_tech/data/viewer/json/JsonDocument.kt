package com.readfilestech.read_files_tech.data.viewer.json

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding

/** What a JSON value is, told by its first character. */
enum class JsonKind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

/** The bounds of what is built from one file. */
internal data class JsonLimits(val values: Int = FileCaps.JSON_VALUES, val depth: Int = FileCaps.JSON_DEPTH)

/**
 * A JSON file's values, indexed for the viewer without being decoded: in document order, each value keeps
 * the offset of its key (a member) or of itself, the index of the value after it and all it holds, and its
 * depth — 10 bytes, beside the file's bytes. A key or a value is decoded only when it is shown or copied.
 *
 * The 2.15.3 decoded the file with Dart's recursive `json.decode` (`json_viewer_screen.dart:41`): a file
 * nested a million deep overflowed the stack, numbers were rounded to a double (a 10 000-digit one became
 * `Infinity`), and of two members of one name only the last was kept. Here every value is shown as written.
 */
class JsonDocument private constructor(
    private val text: JsonText,
    private val starts: IntArray,
    private val nexts: IntArray,
    private val depths: ShortArray,
) {
    val valueCount: Int get() = starts.size

    /** The number of arrays and objects around [value]: 0 for the document's own value. */
    fun depth(value: Int): Int = depths[value].toInt()

    /** The index of the first value after [value] and all it holds. */
    fun next(value: Int): Int = nexts[value]

    fun kind(value: Int): JsonKind = when (text.unitAt(text.valueStart(starts[value]))) {
        '{'.code -> JsonKind.OBJECT
        '['.code -> JsonKind.ARRAY
        '"'.code -> JsonKind.STRING
        't'.code, 'f'.code -> JsonKind.BOOLEAN
        'n'.code -> JsonKind.NULL
        else -> JsonKind.NUMBER
    }

    fun isContainer(value: Int): Boolean = kind(value).let { it == JsonKind.OBJECT || it == JsonKind.ARRAY }

    /** Whether a value follows [value] in the same array or object. */
    fun hasNextSibling(value: Int): Boolean {
        val next = nexts[value]
        return next < valueCount && depths[next] == depths[value]
    }

    /** The values [container] holds, in order. */
    fun children(container: Int): Sequence<Int> {
        val end = nexts[container]
        return generateSequence((container + 1).takeIf { it < end }) { child -> nexts[child].takeIf { it < end } }
    }

    fun childCount(container: Int): Int = children(container).count()

    /** The key of a member as written, quotes and escapes included; null for an element of an array. */
    fun keyLiteral(value: Int, maxChars: Int): String? {
        val start = starts[value]
        return if (text.isMember(start)) text.clip(start, text.skipString(start), maxChars) else null
    }

    /** A scalar as written — a string with its quotes and escapes; past [maxChars], cut and marked. */
    fun literal(value: Int, maxChars: Int): String {
        val start = text.valueStart(starts[value])
        return text.clip(start, text.tokenEnd(start), maxChars)
    }

    /**
     * What copying a scalar gives: a string's characters, unescaped and without its quotes; a number or a
     * literal as written. Null when longer than [maxChars].
     */
    fun copyText(value: Int, maxChars: Int): String? {
        val start = text.valueStart(starts[value])
        if (kind(value) == JsonKind.STRING) return text.string(start, maxChars).takeIf { it.length <= maxChars }
        // A number or a literal is ASCII: a character a unit.
        val end = text.tokenEnd(start)
        return if (end - start <= maxChars * text.unit) text.clip(start, end, maxChars) else null
    }

    companion object {
        /** Marks a text cut for the screen. */
        const val ELLIPSIS = "…"

        /**
         * The values of the first [size] bytes of [bytes] (kept, not copied), or a [JsonRefused]: read twice,
         * without recursion — once to check the text and count its values, once to fill arrays of that size.
         */
        internal suspend fun parse(bytes: ByteArray, size: Int, encoding: TextEncoding, limits: JsonLimits): JsonDocument {
            val text = JsonText(bytes, size, encoding)
            val count = JsonScanner(text, size, encoding.bomLength, limits).scan(JsonScanner.Counting)
            val filling = JsonScanner.Filling(count)
            JsonScanner(text, size, encoding.bomLength, limits).scan(filling)
            return JsonDocument(text, filling.starts, filling.nexts, filling.depths)
        }
    }
}
