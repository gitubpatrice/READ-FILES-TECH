package com.readfilestech.read_files_tech.data.tools

/**
 * CSS and JavaScript minified by removing comments and the spaces nothing needs — and nothing else: no
 * name is shortened, no string, template, regular expression or `url(…)` is touched, byte for byte. The
 * text is read once, from left to right, by a tokenizer that knows where those begin and end.
 *
 * The 2.15.3 minified with regular expressions (`format_screen.dart:80-96`, inventory F10): `//` was cut
 * to the end of the line inside a string or a URL (`"http://…"`), the spaces around `+` and `-` went in
 * `calc(1px + 2px)`, which CSS then refuses, and every line break went, so a script relying on automatic
 * semicolons (`return` alone on its line, a statement without `;`) changed meaning or broke. Here a line
 * break stays wherever there was one between two tokens; it is a byte, as a space is.
 */
object Minifiers {
    /** [text] minified into [out]; [checkpoint] is called every so often, and throws to stop it. */
    fun css(text: String, out: Appendable, checkpoint: () -> Unit) = CssMinifier(text, out).run(checkpoint)

    /** [text] minified into [out]; [checkpoint] is called every so often, and throws to stop it. */
    fun js(text: String, out: Appendable, checkpoint: () -> Unit) = JsMinifier(text, out).run(checkpoint)

    /** Every so many characters read, the checkpoint is called. */
    internal const val CANCEL_CHECK_CHARS = 65_536
}

/**
 * CSS Syntax 3's tokens, as far as minifying needs: comments, strings, escapes, `url(` without quotes.
 * A run of spaces and comments goes after `{ } ; , : (` and before `{ } ; , )`, and is one space
 * elsewhere — around `+`, `-`, `>`, `~` too: `calc()` needs them; and before a `:`, as `div :hover` is
 * not `div:hover`. The `;` before a `}` goes.
 */
private class CssMinifier(private val css: String, private val out: Appendable) {
    private var i = 0
    private var last = NONE
    private var space = false
    private var semicolon = false

    fun run(checkpoint: () -> Unit) {
        var nextCheck = 0
        while (i < css.length) {
            if (i >= nextCheck) {
                checkpoint()
                nextCheck = i + Minifiers.CANCEL_CHECK_CHARS
            }
            step(css[i])
        }
        // A `;` goes before a `}` only: the last one of the sheet (`@import …;`) stays.
        if (semicolon) write(';')
    }

    private fun step(c: Char) {
        when {
            c in SPACES -> {
                space = true
                i++
            }
            c == '/' && css.getOrNull(i + 1) == '*' -> {
                val end = css.indexOf("*/", i + 2)
                i = if (end < 0) css.length else end + 2
                space = true
            }
            c == '"' || c == '\'' -> emit(stringEnd(i))
            // An escape — `.a\:b`, `\ ` — is its backslash and what it escapes, a space included.
            c == '\\' -> emit(minOf(i + 2, css.length))
            c == ';' -> {
                if (semicolon) write(';')
                semicolon = true
                space = false
                i++
            }
            else -> emit(if (isUnquotedUrl()) urlEnd() else i + 1)
        }
    }

    /** Writes the token from [i] until [end], after the `;` and the space held back if they are needed. */
    private fun emit(end: Int) {
        val first = css[i].code
        if (semicolon) {
            semicolon = false
            if (first != '}'.code) {
                write(';')
                space = false
            }
        }
        if (spaceNeeded(first)) out.append(' ')
        space = false
        out.append(css, i, end)
        last = css[end - 1].code
        i = end
    }

    private fun spaceNeeded(first: Int): Boolean = space && last != NONE && last !in NO_SPACE_AFTER && first !in NO_SPACE_BEFORE

    private fun write(c: Char) {
        out.append(c)
        last = c.code
    }

    /** After the string at [start]; a line break ends it unclosed (CSS's bad string), before the break. */
    private fun stringEnd(start: Int): Int {
        val quote = css[start]
        var p = start + 1
        while (p < css.length && css[p] != quote && css[p] !in BREAKS) p += if (css[p] == '\\') 2 else 1
        return if (p < css.length && css[p] == quote) p + 1 else minOf(p, css.length)
    }

    /** Whether [i] begins `url(` without quotes — whose content is then written as it is. */
    private fun isUnquotedUrl(): Boolean {
        if (!css.regionMatches(i, URL, 0, URL.length, ignoreCase = true) || i > 0 && isNameChar(css[i - 1])) return false
        var p = i + URL.length
        while (p < css.length && css[p] in SPACES) p++
        return p >= css.length || css[p] != '"' && css[p] != '\''
    }

    /** After the `)` of the unquoted `url(…)` at [i]. */
    private fun urlEnd(): Int {
        var p = i + URL.length
        while (p < css.length && css[p] != ')') p += if (css[p] == '\\') 2 else 1
        return minOf(p + 1, css.length)
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_' || c == '\\' || c.code >= NON_ASCII

    private companion object {
        const val NONE = -1
        const val NON_ASCII = 0x80
        const val URL = "url("
        val SPACES = charArrayOf(' ', '\t', '\n', '\r', Char(FORM_FEED))
        val BREAKS = charArrayOf('\n', '\r', Char(FORM_FEED))
        val NO_SPACE_AFTER = "{};,:(".map(Char::code).toSet()
        val NO_SPACE_BEFORE = "{};,)".map(Char::code).toSet()
    }
}

/**
 * ECMAScript's tokens, as far as minifying needs: line and block comments, the three kinds of string,
 * template literals with their `${…}` nested to any depth, and regular expressions — told from a division
 * by the token before the `/`, as a parser does without parsing: after a value (a name, a number, a
 * string, `)`, `]`) it divides, after an operator, `(`, `,`, `{`, `}`, `;` or a keyword such as `return` it
 * begins a regular expression (one that does not close on its line is a division after all).
 *
 * A run of spaces and comments becomes one line break if it held one — automatic semicolons depend on
 * them — or else nothing, or one space where the tokens either side would otherwise join (`a b`, `a - -b`,
 * `/re/ in x`, `1 .x`).
 */
// TooManyFunctions: one small function per kind of token, as a tokenizer has (JsonScanner, HtmlTokenizer).
@Suppress("TooManyFunctions")
private class JsMinifier(private val js: String, private val out: Appendable) {
    private var i = 0
    private var last = NONE
    private var gap = Gap.NONE
    private var regexAllowed = true
    private var afterRegex = false
    private var afterDot = false

    /** The `{` open in the code of the innermost `${…}`, and those of the templates around it. */
    private var braces = 0
    private val templates = ArrayDeque<Int>()

    private enum class Gap { NONE, SPACE, LINE }

    fun run(checkpoint: () -> Unit) {
        if (js.startsWith("#!")) emit(lineEnd(0), regexNext = true)
        var nextCheck = 0
        while (i < js.length) {
            if (i >= nextCheck) {
                checkpoint()
                nextCheck = i + Minifiers.CANCEL_CHECK_CHARS
            }
            if (!skipped(js[i])) token(js[i])
        }
    }

    /** Past a space, a line break or a comment at [i], if there is one, noted in the gap. */
    private fun skipped(c: Char): Boolean {
        when {
            isLineBreak(c) -> {
                gap = Gap.LINE
                i++
            }
            isSpace(c) -> {
                if (gap == Gap.NONE) gap = Gap.SPACE
                i++
            }
            c == '/' && js.getOrNull(i + 1) == '/' -> {
                i = lineEnd(i)
                if (gap == Gap.NONE) gap = Gap.SPACE
            }
            c == '/' && js.getOrNull(i + 1) == '*' -> blockComment()
            else -> return false
        }
        return true
    }

    private fun token(c: Char) {
        when {
            c == '"' || c == '\'' -> emit(stringEnd(i), regexNext = false)
            c == '`' -> template(i + 1)
            c == '}' && templates.isNotEmpty() && braces == 0 -> template(i + 1)
            c == '/' && regexAllowed -> regexEnd(i)?.let { end ->
                emit(end, regexNext = false)
                afterRegex = true
            } ?: punctuator(c)
            isWordChar(c) -> word()
            else -> punctuator(c)
        }
    }

    private fun blockComment() {
        val end = js.indexOf("*/", i + 2).let { if (it < 0) js.length else it + 2 }
        val breaks = (i until end).any { isLineBreak(js[it]) }
        if (breaks) gap = Gap.LINE else if (gap == Gap.NONE) gap = Gap.SPACE
        i = end
    }

    private fun word() {
        var end = i + 1
        while (end < js.length && isWordChar(js[end])) end++
        val keyword = !afterDot && js.substring(i, end) in REGEX_KEYWORDS
        emit(end, regexNext = keyword)
    }

    private fun punctuator(c: Char) {
        // `a++ / b`: after a postfix `++` or `--` comes a division.
        val postfix = (c == '+' || c == '-') && i > 0 && js[i - 1] == c
        when (c) {
            '{' -> braces++
            '}' -> if (braces > 0) braces--
        }
        emit(i + 1, regexNext = c !in VALUE_ENDS && !postfix)
        afterDot = c == '.'
    }

    /** The part of a template from [start] (after its `` ` `` or the `}` closing a `${`) to its `` ` `` or its next `${`. */
    private fun template(start: Int) {
        val continuing = js[i] == '}'
        var p = start
        while (p < js.length && !isTemplatePartEnd(p)) p += if (js[p] == '\\') 2 else 1
        val closed = p >= js.length || js[p] == '`'
        emit(if (p >= js.length) js.length else if (closed) p + 1 else p + 2, regexNext = !closed)
        if (continuing) braces = templates.removeLast()
        if (!closed) {
            templates.addLast(braces)
            braces = 0
        }
    }

    private fun isTemplatePartEnd(p: Int): Boolean = js[p] == '`' || js[p] == '$' && js.getOrNull(p + 1) == '{'

    /** Writes the token from [i] until [end] after what the gap before it needs; [regexNext]: a `/` after it begins a regex. */
    private fun emit(end: Int, regexNext: Boolean) {
        val first = js[i]
        when (gap) {
            Gap.LINE -> if (last != NONE) out.append('\n')
            Gap.SPACE -> if (last != NONE && joins(last.toChar(), first)) out.append(' ')
            Gap.NONE -> Unit
        }
        gap = Gap.NONE
        out.append(js, i, end)
        last = js[end - 1].code
        i = end
        regexAllowed = regexNext
        afterRegex = false
        afterDot = false
    }

    /** Whether [previous] and [next], written side by side, would make another token. */
    private fun joins(previous: Char, next: Char): Boolean =
        (isWordChar(previous) || afterRegex) && isWordChar(next) ||
            (previous == '+' || previous == '-') && next == previous ||
            previous == '/' && (next == '/' || next == '*') ||
            previous.isDigit() && next == '.' ||
            previous == '<' && next == '!'

    private fun lineEnd(from: Int): Int {
        var p = from
        while (p < js.length && !isLineBreak(js[p])) p++
        return p
    }

    /** After the string at [start]; a line break not escaped ends it unclosed, before the break. */
    private fun stringEnd(start: Int): Int {
        val quote = js[start]
        var p = start + 1
        while (p < js.length && js[p] != quote && js[p] !in NEWLINES) {
            // A line continuation: `\` then CR LF is one escape.
            p += if (js[p] != '\\') 1 else if (js.getOrNull(p + 1) == '\r' && js.getOrNull(p + 2) == '\n') CRLF_ESCAPE else 2
        }
        return if (p < js.length && js[p] == quote) p + 1 else minOf(p, js.length)
    }

    /** After the regular expression at [start] and its flags, or null when it does not close on its line. */
    private fun regexEnd(start: Int): Int? {
        var p = start + 1
        var inClass = false
        while (p < js.length && !endsRegexBody(js[p], inClass)) {
            when (js[p]) {
                // What a backslash escapes is skipped — not a line break, which ends the line, and the regex.
                '\\' -> if (p + 1 < js.length && !isLineBreak(js[p + 1])) p++
                '[' -> inClass = true
                ']' -> inClass = false
            }
            p++
        }
        if (p >= js.length || js[p] != '/') return null
        p++
        while (p < js.length && isWordChar(js[p])) p++
        return p
    }

    /** A line break, or the closing `/` — inside a class `[…]`, a `/` is a character. */
    private fun endsRegexBody(c: Char, inClass: Boolean): Boolean = isLineBreak(c) || !inClass && c == '/'

    private fun isLineBreak(c: Char): Boolean = c == '\n' || c == '\r' || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR

    private fun isSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c in OTHER_SPACES || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** A character of a name, a number or a keyword — `#` of a private name too, so `static #x` keeps its space. */
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$' || c == '\\' || c == '#' || (c.code >= NON_ASCII && !isSpace(c) && !isLineBreak(c))

    private companion object {
        const val NONE = -1
        const val NON_ASCII = 0x80
        const val CRLF_ESCAPE = 3
        val NEWLINES = charArrayOf('\n', '\r')
        val LINE_SEPARATOR = Char(0x2028)
        val PARAGRAPH_SEPARATOR = Char(0x2029)

        /** Vertical tab, form feed, no-break space, BOM: spaces to JavaScript. */
        val OTHER_SPACES = charArrayOf(Char(0x0B), Char(FORM_FEED), Char(0xA0), Char(0xFEFF))

        /** After these a `/` divides. */
        const val VALUE_ENDS = ")]"

        /** After these names a `/` begins a regular expression (`return /x/`), unless they follow a `.`. */
        val REGEX_KEYWORDS = setOf(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await",
        )
    }
}

private const val FORM_FEED = 0x0C
