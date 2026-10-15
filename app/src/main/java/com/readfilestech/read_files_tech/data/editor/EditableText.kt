package com.readfilestech.read_files_tech.data.editor

import com.readfilestech.read_files_tech.data.tools.LineDiff
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.ceil

/** Why the code editor does not open a file it could read: said in words, never as this exception's text. */
class NotEditableException(val reason: Reason) : IOException("not editable: $reason") {
    enum class Reason {
        /** A NUL character: a binary file, or a UTF-16 text without its byte order mark. */
        NOT_TEXT,

        /**
         * Bytes that are no character of the file's encoding — a character cut short, a damaged file: the
         * editor would write them back as other bytes.
         */
        NOT_REVERSIBLE,
    }
}

/** [character], typed or pasted, has no bytes in the file's [encoding] (an emoji in Windows-1252): nothing is written. */
class UnencodableTextException(val character: String, val encoding: TextEncoding) :
    IOException("a character has no ${encoding.name} bytes")

/**
 * A text file as the code editor holds it: its [text], decoded in the encoding the text viewer finds
 * ([TextEncoding.detect]), every line break shown as a LF — and the kind of each one (CRLF, LF, or the CR
 * of the Mac OS of before 2001) remembered, so that [encode] writes the file back as it was: same
 * encoding, same byte order mark, same line breaks. A file saved without a change is the same file, to
 * the byte; one line changed changes that line's bytes only.
 *
 * The 2.15.3 read the file as strict UTF-8 and wrote the text back as UTF-8 (`code_editor_screen.dart:76`,
 * `atomic_write.dart`): a Windows-1252 or UTF-16 file did not open, a BOM was dropped at the first save.
 *
 * A file is opened only when that promise holds ([of]): its text has no NUL, and encoding it again gives
 * its bytes back.
 */
class EditableText private constructor(
    val encoding: TextEncoding,
    /** The text, every line break a LF. */
    val text: String,
    /** The kind of each line break of [text], in order: [LF], [CRLF] or [CR]. */
    private val breaks: ByteArray,
) {
    /** The file's bytes for [edited], and the text they hold — what a next save compares with. */
    class Encoded(val bytes: ByteArray, val text: EditableText)

    /**
     * [edited] as the file's bytes: in its encoding, after its byte order mark, each line break of the kind
     * it had. A line break that was not there takes the kind most of the file's have (LF in a file without
     * any). A CR or a CRLF in [edited] — text pasted from elsewhere — is a line break like a LF.
     *
     * @throws UnencodableTextException a character [encoding] has no bytes for
     */
    suspend fun encode(edited: String): Encoded {
        val normal = split(edited).text
        val kinds = separated(normal, breaksFor(normal))
        val joined = joined(normal, kinds)
        // What the next opening reads is what the editor shows: the same lines, each with its line break.
        val again = split(joined)
        check(again.text == normal && again.breaks.contentEquals(kinds)) { "line breaks not read back as written" }
        val bytes = encodeText(encoding, joined)
        return Encoded(bytes, EditableText(encoding, normal, kinds))
    }

    /**
     * The kinds of [edited]'s line breaks. Those of the lines before the first change and after the last one
     * keep their kind, counted from the start and from the end; between them, [middle].
     *
     * The text the two share at their end may begin inside a line — `C` replaced by `N` before `\nD`: its
     * first line break then ends a line that differs, and belongs to the middle (Claude review R22).
     */
    private suspend fun breaksFor(edited: String): ByteArray {
        val count = edited.count { it == '\n' }
        val shortest = minOf(text.length, edited.length)
        var prefix = 0
        while (prefix < shortest && text[prefix] == edited[prefix]) prefix++
        var suffix = 0
        while (suffix < shortest - prefix && text[text.length - 1 - suffix] == edited[edited.length - 1 - suffix]) suffix++
        val before = countBreaks(text, 0, prefix)
        val wholeLines = startsLine(text, text.length - suffix) && startsLine(edited, edited.length - suffix)
        val after = countBreaks(text, text.length - suffix, text.length).let { if (wholeLines || it == 0) it else it - 1 }
        val kinds = ByteArray(count)
        breaks.copyInto(kinds, 0, 0, before)
        breaks.copyInto(kinds, count - after, breaks.size - after, breaks.size)
        middle(edited, kinds, before, breaks.size - after, count - after)
        return kinds
    }

    /**
     * The line breaks between the first change and the last, from [from] to [oldEnd] in the file and to
     * [newEnd] in [edited]: a line found again ([LineDiff]) keeps its own; between two found, the lines
     * changed keep those of the lines they replace, in order, and a line added takes the file's most common
     * kind. Edits are not one change: two of them far apart, a line removed in one, re-assigned the line
     * breaks of every line between them (Claude review R22). A file of one kind needs no search.
     */
    private suspend fun middle(edited: String, kinds: ByteArray, from: Int, oldEnd: Int, newEnd: Int) {
        val common = commonKind()
        if (breaks.none { it != breaks[0] }) {
            for (i in from until newEnd) kinds[i] = common
            return
        }
        val ids = HashMap<String, Int>()
        val old = lineIds(text, from, oldEnd, ids)
        val matches = matches(old, lineIds(edited, from, newEnd, ids))
        var lastOld = -1
        var lastNew = -1
        for (j in 0..matches.size) {
            val o = if (j == matches.size) old.size else matches[j]
            if (o < 0) continue
            // The gap since the last line found: its lines paired in order, the ones in excess added.
            for (n in lastNew + 1 until j) {
                val paired = lastOld + 1 + (n - lastNew - 1)
                kinds[from + n] = if (paired < o) breaks[from + paired] else common
            }
            if (j < matches.size) kinds[from + j] = breaks[from + o]
            lastOld = o
            lastNew = j
        }
    }

    /**
     * For each line of [new], the line of [old] it is, or -1: the diff tool's shortest edit script
     * ([LineDiff]), its work bounded — past it, the rest is lines changed, paired in order.
     */
    private suspend fun matches(old: IntArray, new: IntArray): IntArray {
        val script = LineDiff.script(old, new, DIFF_WORK)
        val matches = IntArray(new.size) { -1 }
        var o = 0
        var n = 0
        for (run in 0 until script.size) {
            val length = script.length(run)
            when (script.op(run)) {
                LineDiff.SAME -> repeat(length) { matches[n++] = o++ }
                LineDiff.REMOVED -> o += length
                else -> n += length
            }
        }
        return matches
    }

    /** Ids of the lines [from] to [to] of [text] (each ended by its line break), equal text, equal id. */
    private fun lineIds(text: String, from: Int, to: Int, ids: HashMap<String, Int>): IntArray {
        var start = 0
        repeat(from) { start = text.indexOf('\n', start) + 1 }
        return IntArray(to - from) {
            val end = text.indexOf('\n', start)
            val line = text.substring(start, end)
            start = end + 1
            ids.getOrPut(line) { ids.size }
        }
    }

    /** The most frequent kind of line break; on a tie, the first one's; LF when there is none. */
    private fun commonKind(): Byte {
        val counts = IntArray(KINDS)
        breaks.forEach { counts[it.toInt()]++ }
        val most = counts.max()
        return if (most == 0) LF else breaks.first { counts[it.toInt()] == most }
    }

    companion object {
        const val LF: Byte = 0
        const val CRLF: Byte = 1
        const val CR: Byte = 2
        private const val KINDS = 3
        private const val NONE: Byte = -1

        /** Steps of the search for the lines found again: a tenth of the diff tool's, a save not to be waited for. */
        private const val DIFF_WORK = LineDiff.WORK / 10
        private const val NUL = '\u0000'
        private const val BOM = '\uFEFF'

        /**
         * The editor's text of a whole file's [bytes].
         *
         * @throws NotEditableException a NUL in it, or bytes that encoding the text again would not give back
         */
        fun of(bytes: ByteArray): EditableText {
            val encoding = TextEncoding.detect(bytes, bytes.size)
            val decoded = String(bytes, encoding.bomLength, bytes.size - encoding.bomLength, encoding.charset)
            if (decoded.indexOf(NUL) >= 0) throw NotEditableException(NotEditableException.Reason.NOT_TEXT)
            val split = split(decoded)
            // A byte its encoding does not decode became U+FFFD: written back, it would be U+FFFD's bytes.
            val again = try {
                encodeText(encoding, joined(split.text, split.breaks))
            } catch (_: UnencodableTextException) {
                null
            }
            if (again == null || !again.contentEquals(bytes)) throw NotEditableException(NotEditableException.Reason.NOT_REVERSIBLE)
            return EditableText(encoding, split.text, split.breaks)
        }

        private class Split(val text: String, val breaks: ByteArray)

        /** [text] with each CRLF, CR and LF made a LF, and the kind each one was. */
        private fun split(text: String): Split {
            val kinds = ByteArray(countBreaksAnyKind(text))
            val out = StringBuilder(text.length)
            var k = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val kind = when {
                    c == '\r' && text.getOrNull(i + 1) == '\n' -> CRLF
                    c == '\r' -> CR
                    c == '\n' -> LF
                    else -> null
                }
                if (kind == null) {
                    out.append(c)
                } else {
                    out.append('\n')
                    kinds[k++] = kind
                }
                i += if (kind == CRLF) 2 else 1
            }
            return Split(out.toString(), kinds)
        }

        /** Line breaks of any kind in [text]: each LF, and each CR not before one (a CRLF is one). */
        private fun countBreaksAnyKind(text: String): Int =
            text.indices.count { i -> text[i] == '\n' || (text[i] == '\r' && text.getOrNull(i + 1) != '\n') }

        private fun countBreaks(text: String, from: Int, to: Int): Int = (from until to).count { text[it] == '\n' }

        private fun startsLine(text: String, at: Int): Boolean = at == 0 || text[at - 1] == '\n'

        /**
         * [kinds] with each LF that follows a CR, an empty line between, made a CRLF: written `\r` then `\n`,
         * the two would be read back as one CRLF — a line gone, unseen until the file is opened again (Claude
         * review R22: Enter at the start of a line after a CR line, in a file of LF).
         */
        private fun separated(text: String, kinds: ByteArray): ByteArray {
            var k = 0
            var previous = NONE
            for (c in text) {
                if (c != '\n') {
                    previous = NONE
                    continue
                }
                if (kinds[k] == LF && previous == CR) kinds[k] = CRLF
                previous = kinds[k++]
            }
            return kinds
        }

        /** @throws UnencodableTextException a surrogate of [text] without its pair, which no encoding holds */
        private fun requirePairs(text: String, encoding: TextEncoding) {
            val lone = firstLoneSurrogate(text)
            if (lone >= 0) throw UnencodableTextException(text[lone].toString(), encoding)
        }

        /** The index of [text]'s first surrogate without its pair, or -1. */
        internal fun firstLoneSurrogate(text: CharSequence): Int {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> i += 2
                    c.isSurrogate() -> return i
                    else -> i++
                }
            }
            return -1
        }

        /** [text], each LF written as the line break of its kind. */
        private fun joined(text: String, kinds: ByteArray): String {
            val out = StringBuilder(text.length + kinds.size)
            var k = 0
            for (c in text) {
                if (c != '\n') {
                    out.append(c)
                } else {
                    out.append(
                        when (kinds[k++]) {
                            CRLF -> "\r\n"
                            CR -> "\r"
                            else -> "\n"
                        },
                    )
                }
            }
            return out.toString()
        }

        /**
         * [text] in [encoding], after its byte order mark: U+FEFF encoded by the same charset gives the very
         * bytes [TextEncoding.detect] read (EF BB BF, FF FE, FE FF). A character the encoding has no bytes for
         * is refused, never replaced by `?` as `String.toByteArray` would.
         *
         * A surrogate without its pair (pasted from an app that cut a string between the two) is refused
         * before any encoder sees it: Android's encoders (ICU) may hold one left at the very end and give it
         * only to [java.nio.charset.CharsetEncoder.flush] — dropped, had its result been ignored (R22).
         */
        private fun encodeText(encoding: TextEncoding, text: String): ByteArray {
            requirePairs(text, encoding)
            val encoder = encoding.charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = CharBuffer.wrap(if (encoding.bomLength > 0) BOM + text else text)
            // At most this many: no overflow to grow from.
            val out = ByteBuffer.allocate(ceil(encoder.maxBytesPerChar().toDouble() * input.remaining()).toInt())
            val result = encoder.encode(input, out, true)
            if (result.isError) {
                throw UnencodableTextException(input.subSequence(0, result.length()).toString(), encoding)
            }
            check(!result.isOverflow) { "the bytes outgrew their bound" }
            val end = encoder.flush(out)
            if (end.isError) throw UnencodableTextException(text.takeLast(1), encoding)
            check(!end.isOverflow) { "the bytes outgrew their bound" }
            return out.array().copyOf(out.position())
        }
    }
}
