package com.readfilestech.read_files_tech.data.viewer.text

/**
 * What sets the C-like languages the 2.15.3 coloured apart. The words are those of its grammars (highlight
 * 0.7.0, `languages/javascript.dart`, `dart.dart`, `php.dart`), the ones still in use.
 */
@Suppress("LongParameterList") // one flag per difference between three languages, each named where it is set
internal class CLikeSyntax(
    val keywords: Set<String>,
    val literals: Set<String>,
    val builtIns: Set<String>,
    /** The quotes that open a string. */
    val quotes: String,
    /** Those whose strings go on past the end of a line. */
    val multiLineQuotes: String,
    /** Dart: `'''` and `"""` strings, and the raw ones (`r'…'`), where a backslash escapes nothing. */
    val tripleQuotes: Boolean = false,
    /** Dart: a comment opened inside a comment needs an end of its own. */
    val nestedComments: Boolean = false,
    /** JavaScript: `/…/` where a value is expected. */
    val regexLiterals: Boolean = false,
    /** Dart: `@name`. */
    val annotations: Boolean = false,
    /** PHP: `$name`, `#` comments (not `#[`, an attribute), words in any case, and `?>` ends the code. */
    val php: Boolean = false,
) {
    companion object {
        val JAVASCRIPT = CLikeSyntax(
            keywords = words(
                "as async await break case catch class const continue debugger default delete do else export extends finally for " +
                    "from function get if import in instanceof let new of return set static super switch this throw try typeof " +
                    "var void while with yield",
            ),
            literals = words("true false null undefined NaN Infinity"),
            builtIns = words(
                "Array ArrayBuffer Boolean DataView Date Error EvalError Float32Array Float64Array Function Int16Array Int32Array " +
                    "Int8Array Intl JSON Map Math Number Object Promise Proxy RangeError ReferenceError Reflect RegExp Set String " +
                    "Symbol SyntaxError TypeError URIError Uint16Array Uint32Array Uint8Array Uint8ClampedArray WeakMap WeakSet " +
                    "arguments console decodeURI decodeURIComponent document encodeURI encodeURIComponent eval isFinite isNaN " +
                    "module parseFloat parseInt require window",
            ),
            quotes = "'\"`",
            multiLineQuotes = "`",
            regexLiterals = true,
        )

        val DART = CLikeSyntax(
            keywords = words(
                "abstract as assert async await base break case catch class const continue covariant default deferred do " +
                    "dynamic else enum export extends extension external factory final finally for get hide if implements " +
                    "import in interface is late library mixin new on operator part required rethrow return sealed set show " +
                    "static super switch sync this throw try typedef var void when while with yield",
            ),
            literals = words("true false null"),
            builtIns = words(
                "Comparable DateTime Duration Function Future Iterable Iterator List Map Match Never Null Object Pattern " +
                    "Record RegExp Set Stopwatch Stream String StringBuffer StringSink Symbol Type Uri bool double int num print",
            ),
            quotes = "'\"",
            multiLineQuotes = "",
            tripleQuotes = true,
            nestedComments = true,
            annotations = true,
        )

        val PHP = CLikeSyntax(
            keywords = words(
                "abstract and array as break callable case catch class clone const continue declare default die do echo " +
                    "else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum eval exit extends final " +
                    "finally fn for foreach function global goto if implements include include_once instanceof insteadof " +
                    "interface isset list match namespace new or parent print private protected public readonly require " +
                    "require_once return self static switch throw trait try unset use var while xor yield",
            ),
            literals = words("true false null"),
            builtIns = words("__class__ __dir__ __file__ __function__ __line__ __method__ __namespace__ __trait__"),
            quotes = "'\"`",
            multiLineQuotes = "'\"`",
            php = true,
        )

        private fun words(list: String): Set<String> = list.split(' ').toSet()
    }
}

/**
 * Colours JavaScript, Dart, and the code of PHP (inside `<?php … ?>`, [PhpTokenizer]): comments, strings,
 * numbers, keywords. A state carries a block comment (and its depth in Dart, where comments nest), a string
 * (its quote; Dart's triple-quoted and raw ones), and a line comment or a one-line string to the next piece
 * of a cut line — never to the next line. A JavaScript regular expression ends on its row.
 */
// TooManyFunctions: a hand-written lexer is one small function per kind of token.
@Suppress("TooManyFunctions")
internal class CLikeTokenizer(private val syntax: CLikeSyntax) : CodeTokenizer {
    private var row: CharSequence = ""
    private var spans: MutableList<CodeSpan>? = null
    private var at = 0

    /** The state where the last [scan] stopped. */
    var state = CODE
        private set

    /** JavaScript: whether a `/` here opens a regular expression rather than divides — a value is expected. */
    private var valueExpected = true

    override fun tokenize(row: CharSequence, state: Int, endsLine: Boolean, spans: MutableList<CodeSpan>?): Int {
        scan(row, 0, state, spans)
        return endRow(this.state, endsLine)
    }

    /**
     * Reads [row] from [from], entered in [state], to its end — or, in PHP, to a `?>` outside a string and a
     * block comment, which ends the code: returns where it stopped, [state] being then the state there.
     */
    fun scan(row: CharSequence, from: Int, state: Int, spans: MutableList<CodeSpan>?): Int {
        this.row = row
        this.spans = spans
        this.state = state
        at = from
        valueExpected = true
        while (at < row.length) {
            if (syntax.php && this.state == CODE && row.hasAt(at, "?>")) break
            when (this.state and MODE) {
                COMMENT -> blockComment(at)
                LINE_COMMENT -> lineComment()
                STRING -> string(at)
                else -> code()
            }
        }
        return at
    }

    /** The state the next row enters in, this one having left [state]: what ends with a line ends here. */
    fun endRow(state: Int, endsLine: Boolean): Int = when {
        !endsLine -> state
        state and MODE == LINE_COMMENT -> CODE
        state and MODE == STRING && state and TRIPLE == 0 && quoteOf(state) !in syntax.multiLineQuotes -> CODE
        else -> state
    }

    // CyclomaticComplexMethod: one branch per kind of token, in the order they must be told apart.
    @Suppress("CyclomaticComplexMethod")
    private fun code() {
        val c = row[at]
        val next = row.peek(at + 1)
        when {
            c == '/' && next == '*' -> {
                val start = at
                at += 2
                state = COMMENT or (1 shl DEPTH_SHIFT)
                blockComment(start)
            }
            opensLineComment(c, next) -> {
                state = LINE_COMMENT
                lineComment()
            }
            c in syntax.quotes -> openString(at, raw = false)
            c == '/' && syntax.regexLiterals && valueExpected -> regex()
            isDigit(c) -> number()
            c == '$' && syntax.php && isWordChar(next) -> marked(at, row.wordEnd(at + 1), CodeStyle.VARIABLE)
            c == '@' && syntax.annotations && isWordChar(next) -> marked(at, row.wordEnd(at + 1), CodeStyle.META)
            isWordChar(c) -> word()
            isBlank(c) -> at++
            else -> {
                valueExpected = c != ')' && c != ']' && c != '}'
                at++
            }
        }
    }

    /** `//`; and in PHP `#`, but not `#[`, an attribute. */
    private fun opensLineComment(c: Char, next: Char): Boolean = if (c == '/') next == '/' else syntax.php && c == '#' && next != '['

    private fun blockComment(start: Int) {
        var depth = (state shr DEPTH_SHIFT) and DEPTH_MASK
        while (at < row.length && depth > 0) {
            when {
                row.hasAt(at, "*/") -> {
                    depth--
                    at += 2
                }
                syntax.nestedComments && row.hasAt(at, "/*") -> {
                    // Bounded: a file of nothing but openers stays one comment, closed by as many ends as the bound.
                    depth = minOf(depth + 1, DEPTH_MASK)
                    at += 2
                }
                else -> at++
            }
        }
        spans.mark(start, at, CodeStyle.COMMENT)
        state = if (depth == 0) CODE else COMMENT or (depth shl DEPTH_SHIFT)
    }

    private fun lineComment() {
        val start = at
        val end = if (syntax.php) row.find("?>", at).takeIf { it >= 0 } ?: row.length else row.length
        spans.mark(start, end, CodeStyle.COMMENT)
        at = end
        // PHP: `?>` ends a line comment with the code.
        if (end < row.length) state = CODE
    }

    /** A string whose quote is at [at], written from [start] (Dart's `r` before it, for a raw one). */
    private fun openString(start: Int, raw: Boolean) {
        val quote = row[at]
        val triple = syntax.tripleQuotes && isTriple(quote)
        at += if (triple) TRIPLE_LENGTH else 1
        state = STRING or (quote.code shl QUOTE_SHIFT) or (if (raw) RAW else 0) or (if (triple) TRIPLE else 0)
        string(start)
    }

    private fun string(start: Int) {
        val quote = quoteOf(state)
        val raw = state and RAW != 0
        val triple = state and TRIPLE != 0
        var closed = false
        while (at < row.length && !closed) {
            val c = row[at]
            when {
                c == '\\' && !raw -> at += 2
                c == quote && (!triple || isTriple(quote)) -> {
                    at += if (triple) TRIPLE_LENGTH else 1
                    closed = true
                }
                else -> at++
            }
        }
        // A backslash ending the row escaped nothing left in it.
        at = minOf(at, row.length)
        spans.mark(start, at, CodeStyle.STRING)
        if (closed) {
            state = CODE
            valueExpected = false
        }
    }

    /** Whether the [quote] at [at] is the first of three. */
    private fun isTriple(quote: Char): Boolean = row.peek(at + 1) == quote && row.peek(at + 2) == quote

    /** `/…/flags` on one row, a `[…]` in it holding slashes; a slash it does not close is a division. */
    private fun regex() {
        val start = at
        var end = at + 1
        var inClass = false
        var closed = false
        while (end < row.length && !closed) {
            when (row[end]) {
                '\\' -> end++
                '[' -> inClass = true
                ']' -> inClass = false
                '/' -> closed = !inClass
            }
            end++
        }
        if (closed) {
            at = row.wordEnd(minOf(end, row.length))
            spans.mark(start, at, CodeStyle.REGEXP)
            valueExpected = false
        } else {
            at++
        }
    }

    private fun number() {
        val start = at
        at = row.wordEnd(at)
        while (row.peek(at) == '.' && isDigit(row.peek(at + 1))) at = row.wordEnd(at + 1)
        marked(start, at, CodeStyle.NUMBER)
    }

    private fun word() {
        val start = at
        at = row.wordEnd(at)
        if (syntax.tripleQuotes && opensRawString(start)) {
            openString(start, raw = true)
            return
        }
        valueExpected = syntax.regexLiterals && row.isOneOf(start, at, VALUE_BEFORE)
        val spans = spans ?: return
        val word = row.subSequence(start, at).toString().let { if (syntax.php) it.lowercase() else it }
        when (word) {
            in syntax.keywords -> spans.mark(start, at, CodeStyle.KEYWORD)
            in syntax.literals -> spans.mark(start, at, CodeStyle.LITERAL)
            in syntax.builtIns -> spans.mark(start, at, CodeStyle.BUILT_IN)
        }
    }

    /** Dart: the word from [start] is an `r` before a quote, a raw string. */
    private fun opensRawString(start: Int): Boolean = at == start + 1 && row[start] == 'r' && row.peek(at) in syntax.quotes

    /** A token of [style] from [start] to [end], after which a `/` divides. */
    private fun marked(start: Int, end: Int, style: CodeStyle) {
        spans.mark(start, end, style)
        at = end
        valueExpected = false
    }

    companion object {
        // A state: its mode in bits 0-2; a comment's depth in bits 3-7; a string's quote in bits 8-15, and
        // whether it is raw (16) or triple-quoted (17). The first row of a file enters in CODE, 0.
        const val CODE = 0
        private const val COMMENT = 1
        private const val LINE_COMMENT = 2
        private const val STRING = 3
        private const val MODE = 0x7
        private const val DEPTH_SHIFT = 3
        private const val DEPTH_MASK = 0x1F
        private const val QUOTE_SHIFT = 8
        private const val QUOTE_MASK = 0xFF
        private const val RAW = 1 shl 16
        private const val TRIPLE = 1 shl 17
        private const val TRIPLE_LENGTH = 3

        /** JavaScript: after these, a `/` opens a regular expression (after any other word, it divides). */
        private val VALUE_BEFORE = arrayOf(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await",
        )

        private fun quoteOf(state: Int): Char = ((state shr QUOTE_SHIFT) and QUOTE_MASK).toChar()
    }
}
