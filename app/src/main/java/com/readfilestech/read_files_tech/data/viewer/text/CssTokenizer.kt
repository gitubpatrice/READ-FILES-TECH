package com.readfilestech.read_files_tech.data.viewer.text

/**
 * Colours CSS: comments, strings, at-rules, selectors, property names, and the numbers and colours of values.
 * A state carries a comment, the depth of the braces (bounded) and whether a value is being read — a value goes
 * on over lines. Inside braces, a statement is a nested rule's selector (in an `@media`) when a `{` comes on its
 * row before any `;` or `}`, a declaration otherwise. A string ends on its row.
 */
// TooManyFunctions: a hand-written lexer is one small function per kind of token.
@Suppress("TooManyFunctions")
internal class CssTokenizer : CodeTokenizer {
    private var row: CharSequence = ""
    private var spans: MutableList<CodeSpan>? = null
    private var at = 0
    private var comment = false
    private var value = false
    private var depth = 0

    /** Where a statement starts: the next token tells a selector from a declaration. */
    private var statementStart = true
    private var selector = false

    /** In an at-rule's prelude (`@media screen and (…)`): its words are not properties. */
    private var atRule = false

    override fun tokenize(row: CharSequence, state: Int, endsLine: Boolean, spans: MutableList<CodeSpan>?): Int {
        this.row = row
        this.spans = spans
        at = 0
        comment = state and COMMENT != 0
        value = state and VALUE != 0
        depth = state shr DEPTH_SHIFT
        statementStart = !value
        atRule = false
        while (at < row.length) {
            if (comment) comment(at) else token()
        }
        return (if (comment) COMMENT else 0) or (if (value) VALUE else 0) or (depth shl DEPTH_SHIFT)
    }

    // CyclomaticComplexMethod: one branch per kind of token, in the order they must be told apart.
    @Suppress("CyclomaticComplexMethod")
    private fun token() {
        val c = row[at]
        when {
            c == '/' && row.peek(at + 1) == '*' -> {
                val start = at
                at += 2
                comment = true
                comment(start)
            }
            c == '"' || c == '\'' -> string(c)
            c == '{' || c == '}' || c == ';' -> {
                if (c == '{') depth = minOf(depth + 1, MAX_DEPTH)
                if (c == '}') depth = maxOf(depth - 1, 0)
                value = false
                statementStart = true
                at++
            }
            isBlank(c) -> at++
            c == '@' && isWordChar(row.peek(at + 1)) -> {
                statementStart = false
                selector = false
                atRule = true
                keyword(nameEnd(at + 1))
            }
            c == '!' && row.hasAt(at + 1, "important") -> keyword(at + IMPORTANT)
            else -> {
                if (statementStart) {
                    selector = depth == 0 || braceComesFirst()
                    statementStart = false
                    atRule = false
                }
                when {
                    value -> valueToken(c)
                    selector -> selectorToken(c)
                    else -> declarationToken(c)
                }
            }
        }
    }

    private fun keyword(end: Int) {
        spans.mark(at, end, CodeStyle.KEYWORD)
        at = end
    }

    private fun comment(start: Int) {
        val end = row.find("*/", at)
        at = if (end < 0) row.length else end + 2
        comment = end < 0
        spans.mark(start, at, CodeStyle.COMMENT)
    }

    private fun string(quote: Char) {
        val start = at
        at++
        while (at < row.length && row[at] != quote) at += if (row[at] == '\\') 2 else 1
        at = minOf(at + 1, row.length)
        spans.mark(start, at, CodeStyle.STRING)
    }

    private fun selectorToken(c: Char) {
        val start = at
        when {
            c == '.' || c == '#' || c == ':' -> {
                at++
                if (row.peek(at) == ':') at++
                at = nameEnd(at)
                spans.mark(start, at, CodeStyle.SELECTOR)
            }
            c == '*' || isNameChar(c) -> {
                at = maxOf(nameEnd(at), at + 1)
                spans.mark(start, at, CodeStyle.SELECTOR)
            }
            c == '[' -> at = row.find("]", at).let { if (it < 0) row.length else it + 1 }
            else -> at++
        }
    }

    private fun declarationToken(c: Char) {
        when {
            c == ':' -> {
                value = true
                at++
            }
            isNameChar(c) -> {
                val start = at
                at = nameEnd(at)
                if (!atRule) spans.mark(start, at, CodeStyle.PROPERTY)
            }
            else -> at++
        }
    }

    private fun valueToken(c: Char) {
        val start = at
        when {
            c == '#' && isWordChar(row.peek(at + 1)) -> {
                at = row.wordEnd(at + 1)
                spans.mark(start, at, CodeStyle.NUMBER)
            }
            isDigit(c) || c in SIGNS && isDigit(row.peek(at + 1)) -> {
                at++
                while (at < row.length && (isWordChar(row[at]) || row[at] in UNITS)) at++
                spans.mark(start, at, CodeStyle.NUMBER)
            }
            isNameChar(c) -> {
                at = nameEnd(at)
                if (at - start == URL.length && row.hasAt(start, URL) && row.peek(at) == '(') url()
            }
            else -> at++
        }
    }

    /** `url(…)` unquoted, from its `(`: its `;` and `/` (a data: URI) are not the declaration's. */
    private fun url() {
        val close = row.find(")", at)
        val end = if (close < 0) row.length else close
        spans.mark(at + 1, end, CodeStyle.STRING)
        at = end
    }

    /** Whether a `{` comes on the row, from here, before any `;` or `}`: a nested rule's selector. */
    private fun braceComesFirst(): Boolean {
        var i = at
        while (i < row.length && row[i] !in STATEMENT_ENDS) i++
        return row.peek(i) == '{'
    }

    private fun isNameChar(c: Char): Boolean = isWordChar(c) || c == '-'

    private fun nameEnd(from: Int): Int {
        var end = from
        while (end < row.length && isNameChar(row[end])) end++
        return end
    }

    private companion object {
        // A state: a comment open (bit 0), a value being read (bit 1), the depth of the braces (bits 2-7).
        const val COMMENT = 1
        const val VALUE = 2
        const val DEPTH_SHIFT = 2
        const val MAX_DEPTH = 63
        const val IMPORTANT = 10
        const val URL = "url"

        /** What may start a number before its first digit, and end it after its last. */
        const val SIGNS = ".-+"
        const val UNITS = ".%"
        const val STATEMENT_ENDS = "{;}"
    }
}
