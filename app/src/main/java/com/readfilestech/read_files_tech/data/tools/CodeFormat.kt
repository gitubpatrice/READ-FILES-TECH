package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.json.JsonContent
import com.readfilestech.read_files_tech.data.viewer.json.JsonDocument
import com.readfilestech.read_files_tech.data.viewer.json.JsonFile
import com.readfilestech.read_files_tech.data.viewer.json.JsonKind
import com.readfilestech.read_files_tech.data.viewer.json.JsonPretty
import com.readfilestech.read_files_tech.data.viewer.json.JsonRefusal
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.File

/** What the format tool makes of a text, and the extension of the file it saves. */
enum class FormatAction(val extension: String) {
    JSON_INDENTED("json"),
    JSON_MINIFIED("json"),
    CSS_MINIFIED("css"),
    JS_MINIFIED("js"),
}

/** A text ready to be formatted, or why it cannot be. */
sealed interface Formattable {
    /**
     * Ready: [write] makes the result into a sink, as often as asked — the preview, then the file — and
     * calls [checkpoint] every so often, which throws to stop it (a coroutine's `ensureActive`).
     */
    class Ready internal constructor(private val writer: (Appendable, () -> Unit) -> Unit) : Formattable {
        fun write(out: Appendable, checkpoint: () -> Unit) = writer(out, checkpoint)
    }

    /** Not JSON, or past the JSON viewer's caps — said as the viewer says it. */
    class NotJson(val refusal: JsonRefusal) : Formattable
}

/**
 * The format tool (`FormatScreen` of the 2.15.3) over a text of at most [FileCaps.TEXT_VIEWER] bytes —
 * typed, or a file read within the text viewer's cap — in the encoding the text viewer finds.
 *
 * JSON is read by the JSON viewer's scanner, with its caps (`FileCaps.JSON_VALUES`, `JSON_DEPTH`): no
 * recursion, every key and value written as it was — the 2.15.3 decoded with `json.decode` and wrote its
 * copy back (`format_screen.dart:61-65`): numbers rounded to a double, of two members of one name the last
 * only, a file nested deep a stack overflow. The indented form is the viewer's own ([JsonPretty]). CSS and
 * JS are minified by [Minifiers], which knows strings, comments and regular expressions (inventory F10).
 * The result is never held whole: it is written into the sink it is asked for.
 */
object CodeFormat {
    suspend fun prepare(action: FormatAction, bytes: ByteArray, size: Int): Formattable {
        require(size <= FileCaps.TEXT_VIEWER) { "text over the text viewer's cap" }
        return when (action) {
            FormatAction.JSON_INDENTED, FormatAction.JSON_MINIFIED -> when (val content = JsonFile.of(bytes, size)) {
                is JsonContent.Text -> Formattable.NotJson(content.refusal)
                is JsonContent.Tree -> {
                    val document = content.document
                    // No key or value is longer, in characters, than the text is in bytes: none is cut.
                    if (action == FormatAction.JSON_INDENTED) {
                        Formattable.Ready { out, checkpoint -> indented(document, size, out, checkpoint) }
                    } else {
                        Formattable.Ready { out, checkpoint -> minified(document, size, out, checkpoint) }
                    }
                }
            }
            FormatAction.CSS_MINIFIED -> ToolTexts.decode(bytes, size).let { css ->
                Formattable.Ready { out, checkpoint -> Minifiers.css(css, out, checkpoint) }
            }
            FormatAction.JS_MINIFIED -> ToolTexts.decode(bytes, size).let { js ->
                Formattable.Ready { out, checkpoint -> Minifiers.js(js, out, checkpoint) }
            }
        }
    }

    /** Each value on its line, indented by two spaces a level, as the 2.15.3's `JsonEncoder.withIndent('  ')`. */
    private fun indented(document: JsonDocument, longest: Int, out: Appendable, checkpoint: () -> Unit) {
        val lines = JsonPretty(document)
        for (row in 0 until lines.rowCount) {
            if (row % CANCEL_CHECK_VALUES == 0) checkpoint()
            if (row > 0) out.append('\n')
            out.append(lines.line(row, longest))
        }
    }

    /** The values without a space between them. */
    private fun minified(document: JsonDocument, longest: Int, out: Appendable, checkpoint: () -> Unit) {
        // The arrays and objects open around the value written, and holding something.
        val open = IntArray(FileCaps.JSON_DEPTH + 1)
        var depth = 0
        for (value in 0 until document.valueCount) {
            if (value % CANCEL_CHECK_VALUES == 0) checkpoint()
            while (depth > 0 && document.next(open[depth - 1]) <= value) out.append(closer(document, open[--depth]))
            if (depth > 0 && value != open[depth - 1] + 1) out.append(',')
            document.keyLiteral(value, longest)?.let { out.append(it).append(':') }
            when (document.kind(value)) {
                JsonKind.OBJECT, JsonKind.ARRAY -> {
                    out.append(if (document.kind(value) == JsonKind.OBJECT) '{' else '[')
                    if (document.next(value) > value + 1) open[depth++] = value else out.append(closer(document, value))
                }
                else -> out.append(document.literal(value, longest))
            }
        }
        while (depth > 0) out.append(closer(document, open[--depth]))
    }

    private fun closer(document: JsonDocument, container: Int): Char = if (document.kind(container) == JsonKind.OBJECT) '}' else ']'

    private const val CANCEL_CHECK_VALUES = 65_536
}

/** The text files the format and compare tools read. */
object ToolTexts {
    /** [file]'s bytes and how many there are, read whole within the text viewer's cap ([FileCaps.TEXT_VIEWER]). */
    suspend fun read(file: File): Pair<ByteArray, Int> {
        val source = StorageFileSource(file)
        return source.open(FileCaps.TEXT_VIEWER).use { TextDocument.readAll(it, source.length(), FileCaps.TEXT_VIEWER) }
    }

    /** [bytes]' text (the first [size]), in the encoding the text viewer finds, without its BOM. */
    fun decode(bytes: ByteArray, size: Int): String {
        val encoding = TextEncoding.detect(bytes, size)
        return String(bytes, encoding.bomLength, size - encoding.bomLength, encoding.charset)
    }
}

/**
 * The first [limit] characters of a text written into it, and how long the whole text is: one writing
 * gives what the screen shows and whether it may be copied whole, without holding the rest.
 */
class TextPreview(private val limit: Int) : Appendable {
    private val kept = StringBuilder()

    /** The length of everything written, in characters. */
    var length: Long = 0
        private set

    /** Whether [text] is everything written. */
    val isWhole: Boolean get() = length <= limit

    /** What was kept — never ending on the first half of a surrogate pair. */
    val text: String
        get() = if (!isWhole && kept.isNotEmpty() && kept.last().isHighSurrogate()) kept.substring(0, kept.length - 1) else kept.toString()

    override fun append(c: Char): Appendable {
        if (kept.length < limit) kept.append(c)
        length++
        return this
    }

    override fun append(csq: CharSequence?): Appendable = append(csq ?: NULL, 0, (csq ?: NULL).length)

    override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
        val room = limit - kept.length
        if (room > 0) kept.append(csq ?: NULL, start, minOf(end, start + room))
        length += end - start
        return this
    }

    private companion object {
        /** What `Appendable.append(null)` writes, by its contract. */
        const val NULL = "null"
    }
}
