package com.readfilestech.read_files_tech.data.viewer.text

/**
 * Colours XML — and the HTML around the code of a PHP file, for [PhpTokenizer]: tag names, attributes and
 * their values, comments, CDATA, processing instructions and declarations. A state carries whichever is open:
 * a comment, a tag, an attribute's value can all go on over lines.
 *
 * [phpHost]: a `<?` (but `<?xml`) opens PHP code wherever it is — PHP does not know HTML, and runs code
 * written inside an HTML comment or an attribute's value.
 */
// TooManyFunctions: a hand-written lexer is one small function per kind of token.
@Suppress("TooManyFunctions")
internal class XmlTokenizer(private val phpHost: Boolean = false) : CodeTokenizer {
    private var row: CharSequence = ""
    private var spans: MutableList<CodeSpan>? = null
    private var at = 0

    /** The state where the last [scan] stopped. */
    var state = TEXT
        private set

    override fun tokenize(row: CharSequence, state: Int, endsLine: Boolean, spans: MutableList<CodeSpan>?): Int {
        scan(row, 0, state, spans)
        return this.state
    }

    /** Reads [row] from [from], entered in [state], to its end or to a `<?` opening PHP code: returns where it stopped. */
    fun scan(row: CharSequence, from: Int, state: Int, spans: MutableList<CodeSpan>?): Int {
        this.row = row
        this.spans = spans
        this.state = state
        at = from
        while (at < row.length && !opensCode()) {
            when (this.state) {
                COMMENT -> inside(at, "-->", CodeStyle.COMMENT)
                CDATA -> cdata()
                INSTRUCTION -> inside(at, "?>", CodeStyle.META)
                DECLARATION -> inside(at, ">", CodeStyle.META)
                TAG -> tag()
                VALUE_DOUBLE, VALUE_SINGLE -> value(at)
                else -> text()
            }
        }
        return at
    }

    private fun opensCode(): Boolean = phpHost && row.hasAt(at, "<?") && !row.hasAt(at, "<?xml")

    private fun text() {
        val lt = indexOf('<', at)
        if (lt != at) {
            at = if (lt < 0) row.length else lt
            return
        }
        val start = at
        val next = row.peek(at + 1)
        when {
            row.hasAt(at, COMMENT_OPEN) -> enter(COMMENT, COMMENT_OPEN.length) { inside(start, "-->", CodeStyle.COMMENT) }
            row.hasAt(at, CDATA_OPEN) -> {
                spans.mark(at, at + CDATA_OPEN.length, CodeStyle.META)
                at += CDATA_OPEN.length
                state = CDATA
            }
            next == '!' -> enter(DECLARATION, 2) { inside(start, ">", CodeStyle.META) }
            next == '?' -> enter(INSTRUCTION, 2) { inside(start, "?>", CodeStyle.META) }
            isNameStart(next) || next == '/' && isNameStart(row.peek(at + 2)) -> {
                at += if (next == '/') 2 else 1
                val nameStart = at
                at = nameEnd(at)
                spans.mark(nameStart, at, CodeStyle.TAG)
                state = TAG
            }
            else -> at++
        }
    }

    private inline fun enter(mode: Int, opening: Int, body: () -> Unit) {
        at += opening
        state = mode
        body()
    }

    /** Inside a construct that [close] ends, written from [start]; marked [style] to its end or the row's. */
    private fun inside(start: Int, close: String, style: CodeStyle) {
        while (at < row.length && !opensCode()) {
            if (row.hasAt(at, close)) {
                at += close.length
                state = TEXT
                break
            }
            at++
        }
        spans.mark(start, at, style)
    }

    private fun cdata() {
        while (at < row.length && !opensCode()) {
            if (row.hasAt(at, CDATA_CLOSE)) {
                spans.mark(at, at + CDATA_CLOSE.length, CodeStyle.META)
                at += CDATA_CLOSE.length
                state = TEXT
                return
            }
            at++
        }
    }

    private fun tag() {
        val c = row[at]
        when {
            c == '>' -> {
                at++
                state = TEXT
            }
            c == '/' && row.peek(at + 1) == '>' -> {
                at += 2
                state = TEXT
            }
            c == '"' || c == '\'' -> {
                val start = at
                at++
                state = if (c == '"') VALUE_DOUBLE else VALUE_SINGLE
                value(start)
            }
            isNameStart(c) -> {
                val start = at
                at = nameEnd(at)
                spans.mark(start, at, CodeStyle.ATTRIBUTE)
            }
            else -> at++
        }
    }

    private fun value(start: Int) {
        val quote = if (state == VALUE_DOUBLE) '"' else '\''
        while (at < row.length && !opensCode()) {
            if (row[at] == quote) {
                at++
                state = TAG
                break
            }
            at++
        }
        spans.mark(start, at, CodeStyle.STRING)
    }

    private fun isNameStart(c: Char): Boolean = isWordChar(c) && !isDigit(c) || c == ':'

    private fun isNameChar(c: Char): Boolean = isWordChar(c) || c in NAME_MARKS

    private fun nameEnd(from: Int): Int {
        var end = from
        while (end < row.length && isNameChar(row[end])) end++
        return end
    }

    private fun indexOf(c: Char, from: Int): Int {
        for (i in from until row.length) if (row[i] == c) return i
        return -1
    }

    companion object {
        // A state is one of these modes; the first row of a file enters in TEXT, 0.
        const val TEXT = 0
        private const val COMMENT = 1
        private const val CDATA = 2
        private const val INSTRUCTION = 3
        private const val DECLARATION = 4
        private const val TAG = 5
        private const val VALUE_DOUBLE = 6
        private const val VALUE_SINGLE = 7

        /** The bits a state takes. */
        const val MASK = 0x7
        private const val COMMENT_OPEN = "<!--"
        private const val CDATA_OPEN = "<![CDATA["
        private const val CDATA_CLOSE = "]]>"

        /** What a name holds besides a word's characters. */
        private const val NAME_MARKS = "-:."
    }
}

/**
 * Colours PHP: the HTML of the file as [XmlTokenizer] does, the code between `<?php` (or `<?=`, `<?`) and
 * `?>` as [CLikeTokenizer] does — the 2.15.3 coloured the whole file as PHP code, its HTML's words included.
 * A state carries both, and which one the row is in.
 */
internal class PhpTokenizer : CodeTokenizer {
    private val html = XmlTokenizer(phpHost = true)
    private val code = CLikeTokenizer(CLikeSyntax.PHP)

    override fun tokenize(row: CharSequence, state: Int, endsLine: Boolean, spans: MutableList<CodeSpan>?): Int {
        var inCode = state and IN_CODE != 0
        var markup = state and XmlTokenizer.MASK
        var codeState = state ushr CODE_SHIFT
        var at = 0
        while (at < row.length) {
            if (inCode) {
                at = code.scan(row, at, codeState, spans)
                codeState = code.state
                if (at < row.length) {
                    // At `?>`.
                    spans.mark(at, at + 2, CodeStyle.META)
                    at += 2
                    inCode = false
                }
            } else {
                at = html.scan(row, at, markup, spans)
                markup = html.state
                if (at < row.length) {
                    // At `<?`.
                    val opening = openingLength(row, at)
                    spans.mark(at, at + opening, CodeStyle.META)
                    at += opening
                    inCode = true
                    codeState = CLikeTokenizer.CODE
                }
            }
        }
        if (inCode) codeState = code.endRow(codeState, endsLine)
        return markup or (if (inCode) IN_CODE else 0) or (codeState shl CODE_SHIFT)
    }

    /** `<?php` (in any case), `<?=` or `<?`. */
    private fun openingLength(row: CharSequence, at: Int): Int = when {
        at + PHP_OPEN.length <= row.length && (2 until PHP_OPEN.length).all {
            row[at + it].lowercaseChar() == PHP_OPEN[it]
        } -> PHP_OPEN.length
        row.hasAt(at, "<?=") -> ECHO_OPEN
        else -> 2
    }

    private companion object {
        // A state: the HTML's state (bits 0-2), whether the row is in code (bit 3), the code's state from bit 4.
        const val IN_CODE = 1 shl 3
        const val CODE_SHIFT = 4
        const val PHP_OPEN = "<?php"
        const val ECHO_OPEN = 3
    }
}
