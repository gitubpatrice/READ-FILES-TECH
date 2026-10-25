package com.readfilestech.read_files_tech.ui.tools.editor

/**
 * The rule of the editor's text field on what is typed or pasted, apart from Compose to be tested on the
 * JVM: a CR or a CRLF becomes a LF, the editor's one kind of line break.
 */
internal object LineBreakInput {
    /** [text] for the characters from [start] to [end]. */
    class Rewrite(val start: Int, val end: Int, val text: String)

    /**
     * The places to look at, from the [changed] ranges (start, end): each widened by one character each way
     * within [length] — a CR typed before a LF already there, a LF after a CR —, those that then touch or
     * overlap joined, in order. Joined, a CR's fate is read before the next place is rewritten.
     */
    fun places(changed: List<IntArray>, length: Int): List<IntArray> {
        val widened = changed.map { intArrayOf(maxOf(it[0] - 1, 0), minOf(it[1] + 1, length)) }.sortedBy { it[0] }
        val joined = ArrayList<IntArray>()
        for (range in widened) {
            val last = joined.lastOrNull()
            if (last != null && range[0] <= last[1]) last[1] = maxOf(last[1], range[1]) else joined += range.copyOf()
        }
        return joined
    }

    /**
     * What to write in [text] between [from] and [to]: from its first CR to its last, each CR made a LF and a
     * CR before a LF dropped — a CRLF is one line break, as when a file is opened; the character after the
     * place is read for it. Only that span: a cursor after it stays after it. Null when there is no CR.
     */
    fun rewrite(text: CharSequence, from: Int, to: Int): Rewrite? {
        var first = -1
        var last = -1
        for (i in from until to) {
            if (text[i] != '\r') continue
            if (first < 0) first = i
            last = i
        }
        if (first < 0) return null
        val out = StringBuilder(last + 1 - first)
        for (i in first..last) {
            val c = text[i]
            when {
                c != '\r' -> out.append(c)
                i + 1 < text.length && text[i + 1] == '\n' -> Unit
                else -> out.append('\n')
            }
        }
        return Rewrite(first, last + 1, out.toString())
    }

    /**
     * Where [position] of [text] lands once [rewrites] are made: before it, each CR dropped (the one of a
     * CRLF) takes a character away; a CR made a LF moves nothing. The field would otherwise put a cursor
     * that lies inside a rewritten span at its end (Claude review R23: `f(|)` committed with CRLF around).
     */
    fun moved(position: Int, text: CharSequence, rewrites: List<Rewrite>): Int {
        var dropped = 0
        for (rewrite in rewrites) {
            for (i in rewrite.start until minOf(rewrite.end, position)) {
                if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') dropped++
            }
        }
        return position - dropped
    }
}
