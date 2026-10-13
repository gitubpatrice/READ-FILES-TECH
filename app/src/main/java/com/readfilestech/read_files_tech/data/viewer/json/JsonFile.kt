package com.readfilestech.read_files_tech.data.viewer.json

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding

/** Why a JSON file is shown as its text rather than as a tree. */
sealed interface JsonRefusal {
    /** Not JSON from [line], [column] on (from 1, in characters). */
    data class Syntax(val line: Int, val column: Int) : JsonRefusal

    /** Arrays and objects nested deeper than [limit]. */
    data class TooDeep(val limit: Int) : JsonRefusal

    /** More than [limit] values. */
    data class TooManyValues(val limit: Int) : JsonRefusal
}

/** What the JSON viewer shows: the values as a tree, or the text and why it is not one. */
sealed interface JsonContent {
    class Tree(val document: JsonDocument) : JsonContent

    class Text(val text: TextDocument, val refusal: JsonRefusal) : JsonContent
}

/**
 * A JSON file opened for the viewer (`JsonViewerScreen` of the 2.15.3), within the text viewer's cap. The
 * text is read in the encoding the text viewer finds — PowerShell 5.1 writes JSON in UTF-16 (`Out-File`) or
 * in the ANSI code page (`Set-Content`), with a BOM in UTF-8 — where the 2.15.3 read UTF-8 strictly. A file
 * that is not JSON, or that is past the caps, is shown as its text, saying where and why: the 2.15.3
 * showed "JSON invalide" and the parser's exception, and nothing of the file.
 */
object JsonFile {
    suspend fun read(source: ViewerSource): JsonContent {
        val (bytes, size) = source.open(FileCaps.TEXT_VIEWER).use {
            TextDocument.readAll(it, source.length(), FileCaps.TEXT_VIEWER)
        }
        return of(bytes, size)
    }

    /** The content of the first [size] bytes of [bytes] — kept, not copied. */
    internal suspend fun of(bytes: ByteArray, size: Int = bytes.size, limits: JsonLimits = JsonLimits()): JsonContent {
        val encoding = TextEncoding.detect(bytes, size)
        return try {
            JsonContent.Tree(JsonDocument.parse(bytes, size, encoding, limits))
        } catch (refused: JsonRefused) {
            val refusal = when (refused.reason) {
                JsonRefused.Reason.SYNTAX -> position(bytes, size, encoding, refused.offset)
                JsonRefused.Reason.TOO_DEEP -> JsonRefusal.TooDeep(limits.depth)
                JsonRefused.Reason.TOO_MANY_VALUES -> JsonRefusal.TooManyValues(limits.values)
            }
            JsonContent.Text(TextDocument.of(bytes, size), refusal)
        }
    }

    /** The line and the column of the byte [offset], in characters (a UTF-16 pair or a UTF-8 sequence is one). */
    private fun position(bytes: ByteArray, size: Int, encoding: TextEncoding, offset: Int): JsonRefusal.Syntax {
        val text = JsonText(bytes, size, encoding)
        val unit = text.unit
        var line = 1
        var column = 1
        var p = encoding.bomLength
        while (p < offset && p + unit <= size) {
            val c = text.unitAt(p)
            when {
                c == LF -> {
                    line++
                    column = 1
                }
                // Not the start of a character: a UTF-8 continuation byte, the second half of a UTF-16 pair.
                encoding.unitSize == 1 && encoding != TextEncoding.WINDOWS_1252 && c and CONTINUATION_MASK == CONTINUATION -> Unit
                encoding.unitSize == 2 && c in LOW_SURROGATES -> Unit
                else -> column++
            }
            p += unit
        }
        return JsonRefusal.Syntax(line, column)
    }

    private const val LF = 0x0A
    private const val CONTINUATION_MASK = 0xC0
    private const val CONTINUATION = 0x80
    private val LOW_SURROGATES = Character.MIN_LOW_SURROGATE.code..Character.MAX_LOW_SURROGATE.code
}
