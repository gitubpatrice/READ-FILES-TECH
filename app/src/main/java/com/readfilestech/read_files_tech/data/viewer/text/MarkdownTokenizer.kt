package com.readfilestech.read_files_tech.data.viewer.text

/**
 * Colours the source of a Markdown file, as the 2.15.3 did with highlight.js's `markdown`: headings, list
 * markers, quotes, rules, fenced code, and on a line its code, emphasis and links. A state carries a fenced
 * code block (its fence) and whether a row goes on with a line cut before it, where no block starts. What is
 * inline ends on its row, and looks [MAX_INLINE] characters ahead at most for its end.
 */
// TooManyFunctions: a hand-written lexer is one small function per kind of token.
@Suppress("TooManyFunctions")
internal class MarkdownTokenizer : CodeTokenizer {
    private var row: CharSequence = ""
    private var spans: MutableList<CodeSpan>? = null

    override fun tokenize(row: CharSequence, state: Int, endsLine: Boolean, spans: MutableList<CodeSpan>?): Int {
        this.row = row
        this.spans = spans
        val lineStart = state and CUT == 0
        val next = when {
            state and FENCE != 0 -> {
                val closes = lineStart && closesFence(state)
                spans.mark(0, row.length, if (closes) CodeStyle.META else CodeStyle.STRING)
                if (closes) 0 else state and CUT.inv()
            }
            lineStart -> block()
            else -> {
                inline(0)
                0
            }
        }
        return if (endsLine) next else next or CUT
    }

    /** A line's start: a block it opens, the state after it. */
    private fun block(): Int {
        val indent = indent()
        val c = row.peek(indent)
        val run = run(indent, c)
        when {
            indent >= CODE_INDENT -> inline(0)
            opensFence(indent, c, run) -> {
                spans.mark(0, row.length, CodeStyle.META)
                return FENCE or (if (c == '~') TILDE else 0) or (minOf(run, MAX_FENCE) shl LENGTH_SHIFT)
            }
            c == '#' && run <= MAX_HEADING && isBlankOrEnd(row.peek(indent + run)) ->
                spans.mark(0, row.length, CodeStyle.SECTION)
            c == '>' -> spans.mark(0, row.length, CodeStyle.COMMENT)
            isRule(indent, c) -> spans.mark(0, row.length, CodeStyle.META)
            c == '=' && indent + run == trimmedLength() -> spans.mark(0, row.length, CodeStyle.SECTION)
            else -> {
                val marker = listMarker(indent, c)
                spans.mark(indent, indent + marker, CodeStyle.BULLET)
                inline(indent + marker)
            }
        }
        return 0
    }

    /** Three backticks or tildes at least — and no backtick after backticks, which open a code span. */
    private fun opensFence(indent: Int, c: Char, run: Int): Boolean = run >= FENCE_MIN && when (c) {
        '~' -> true
        '`' -> row.find("`", indent + run) < 0
        else -> false
    }

    /** A line that closes the fence of [state]: as long a run of its character at least, and nothing after. */
    private fun closesFence(state: Int): Boolean {
        val indent = indent()
        val c = if (state and TILDE != 0) '~' else '`'
        val run = run(indent, c)
        return indent < CODE_INDENT && run >= (state shr LENGTH_SHIFT) && indent + run == trimmedLength()
    }

    /** `---`, `***` or `___`, three at least, spaces between allowed, nothing else. */
    private fun isRule(indent: Int, c: Char): Boolean {
        val rest = indent until row.length
        return c in RULE_MARKS && rest.all { row[it] == c || isBlank(row[it]) } && rest.count { row[it] == c } >= FENCE_MIN
    }

    /** The length of the list marker at [indent] (`-`, `*`, `+`, `1.`, `1)`), its space included; 0 for none. */
    private fun listMarker(indent: Int, c: Char): Int {
        val end = when {
            c in BULLETS -> indent + 1
            isDigit(c) -> ordinalEnd(indent)
            else -> -1
        }
        return when {
            end < 0 -> 0
            end == row.length -> end - indent
            isBlank(row[end]) -> end + 1 - indent
            else -> 0
        }
    }

    /** Where `1.` or `1)` at [indent] ends; -1 for none. */
    private fun ordinalEnd(indent: Int): Int {
        var digits = indent
        while (digits < row.length && isDigit(row[digits]) && digits - indent < MAX_ORDINAL) digits++
        return if (row.peek(digits) == '.' || row.peek(digits) == ')') digits + 1 else -1
    }

    // CyclomaticComplexMethod: one branch per kind of inline mark, in the order they must be told apart.
    @Suppress("CyclomaticComplexMethod")
    private fun inline(from: Int) {
        // Nothing inline goes on to the next row: without spans, there is nothing to find.
        if (spans == null) return
        var i = from
        while (i < row.length) {
            val c = row[i]
            i = when {
                c == '\\' -> i + 2
                c == '`' -> code(i)
                (c == '*' || c == '_') && opensEmphasis(i, c) -> emphasis(i, c)
                c == '[' || c == '!' && row.peek(i + 1) == '[' -> link(i)
                c == '<' && (row.hasAt(i + 1, "http://") || row.hasAt(i + 1, "https://") || row.hasAt(i + 1, "mailto:")) -> autolink(i)
                else -> i + 1
            }
        }
    }

    /** A code span: closed by a run of as many backticks. */
    private fun code(start: Int): Int {
        val run = run(start, '`')
        var i = start + run
        while (i < row.length && i < start + MAX_INLINE) {
            if (row[i] == '`') {
                val closing = run(i, '`')
                if (closing == run) {
                    spans.mark(start, i + run, CodeStyle.STRING)
                    return i + run
                }
                i += closing
            } else {
                i++
            }
        }
        return start + run
    }

    /** Not before a space; an underscore not inside a word (`snake_case`). */
    private fun opensEmphasis(i: Int, c: Char): Boolean {
        val inWord = c == '_' && i > 0 && isWordChar(row[i - 1])
        return !isBlankOrEnd(row.peek(i + run(i, c))) && !inWord
    }

    /** `**strong**` (or `__`), else `*emphasis*` (or `_`). */
    private fun emphasis(start: Int, c: Char): Int {
        val run = run(start, c)
        val strong = if (run >= 2) closing(start + 2, c, 2) else -1
        val single = if (strong < 0) closing(start + 1, c, 1) else -1
        return when {
            strong >= 0 -> marked(start, strong + 2, CodeStyle.STRONG)
            single >= 0 -> marked(start, single + 1, CodeStyle.EMPHASIS)
            else -> start + run
        }
    }

    /** Marks [start] until [end] in [style]; returns [end], where the next mark may start. */
    private fun marked(start: Int, end: Int, style: CodeStyle): Int {
        spans.mark(start, end, style)
        return end
    }

    /** Where [count] times [c] close what opened before [from]: after a non-space; an underscore not before a letter. */
    private fun closing(from: Int, c: Char, count: Int): Int {
        for (i in from + 1 until minOf(row.length, from + MAX_INLINE)) if (closesAt(i, c, count)) return i
        return -1
    }

    private fun closesAt(i: Int, c: Char, count: Int): Boolean {
        val run = row[i] == c && (count == 1 || row.peek(i + 1) == c)
        val boundary = c == '*' || !isWordChar(row.peek(i + count))
        return run && !isBlank(row[i - 1]) && boundary
    }

    /** `[text](destination)`, or `![description](source)`: the text as a string, the destination as a link. */
    private fun link(start: Int): Int {
        val open = if (row[start] == '!') start + 1 else start
        val close = row.find("]", open, start + MAX_INLINE)
        if (close < 0) return start + 1
        spans.mark(start, close + 1, CodeStyle.STRING)
        val end = if (row.peek(close + 1) == '(') row.find(")", close + 1, close + MAX_INLINE) else -1
        return if (end < 0) close + 1 else marked(close + 1, end + 1, CodeStyle.LINK)
    }

    /** `<https://…>`, no space inside. */
    private fun autolink(start: Int): Int {
        val limit = minOf(row.length, start + MAX_INLINE)
        var i = start + 1
        while (i < limit && row[i] !in AUTOLINK_ENDS) i++
        return if (i < limit && row[i] == '>') marked(start, i + 1, CodeStyle.LINK) else start + 1
    }

    private fun isBlankOrEnd(c: Char): Boolean = c == NONE || isBlank(c)

    /** The spaces a line starts with, up to [CODE_INDENT]. */
    private fun indent(): Int {
        var i = 0
        while (i < row.length && i < CODE_INDENT && row[i] == ' ') i++
        return i
    }

    /** How many [c] are written from [from] on. */
    private fun run(from: Int, c: Char): Int {
        var i = from
        while (i < row.length && row[i] == c) i++
        return i - from
    }

    /** The row's length without its trailing spaces. */
    private fun trimmedLength(): Int {
        var end = row.length
        while (end > 0 && isBlank(row[end - 1])) end--
        return end
    }

    private companion object {
        // A state: the row goes on with a cut line (bit 0); in a fenced block (bit 1) of tildes (bit 2), its
        // fence this long (bits 3-7).
        const val CUT = 1
        const val FENCE = 2
        const val TILDE = 4
        const val LENGTH_SHIFT = 3
        const val MAX_FENCE = 31
        const val FENCE_MIN = 3
        const val CODE_INDENT = 4
        const val MAX_HEADING = 6
        const val MAX_ORDINAL = 9
        const val RULE_MARKS = "-*_"
        const val BULLETS = "-*+"

        /** What ends an autolink: its `>`, or a space or a `<` that make it none. */
        const val AUTOLINK_ENDS = "> \t<"

        /** How far ahead an inline mark looks for its end: a row of 4 KiB of openers stays linear. */
        const val MAX_INLINE = 512
    }
}
