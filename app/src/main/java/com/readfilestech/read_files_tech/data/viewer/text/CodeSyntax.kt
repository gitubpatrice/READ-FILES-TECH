package com.readfilestech.read_files_tech.data.viewer.text

/**
 * What a piece of code is, for its colour: the classes of highlight.js that the 2.15.3's themes (github and
 * atom-one-dark, flutter_highlight 0.7.0) coloured in the languages it coloured.
 */
enum class CodeStyle {
    COMMENT,
    KEYWORD,
    BUILT_IN,
    LITERAL,
    NUMBER,
    STRING,
    REGEXP,
    VARIABLE,
    META,
    TAG,
    ATTRIBUTE,
    PROPERTY,
    SELECTOR,
    SECTION,
    BULLET,
    LINK,
    EMPHASIS,
    STRONG,
}

/** The characters [start] until [end] of a row, in [style]. */
data class CodeSpan(val start: Int, val end: Int, val style: CodeStyle)

/**
 * The languages the 2.15.3 coloured: the text viewer's five (`FileViewerRouter`: css, js, php, xml, dart,
 * each by its own name) and the Markdown viewer's source.
 */
enum class CodeLanguage {
    CSS,
    JAVASCRIPT,
    PHP,
    XML,
    DART,
    MARKDOWN,
    ;

    /** A tokenizer of this language. One per thread: it keeps its place in the row it reads. */
    internal fun tokenizer(): CodeTokenizer = when (this) {
        CSS -> CssTokenizer()
        JAVASCRIPT -> CLikeTokenizer(CLikeSyntax.JAVASCRIPT)
        PHP -> PhpTokenizer()
        XML -> XmlTokenizer()
        DART -> CLikeTokenizer(CLikeSyntax.DART)
        MARKDOWN -> MarkdownTokenizer()
    }

    companion object {
        /** The language a text viewer's file of this (lower-case) extension is coloured in; null for none. */
        fun of(extension: String): CodeLanguage? = when (extension) {
            "css" -> CSS
            "js" -> JAVASCRIPT
            "php" -> PHP
            "xml" -> XML
            "dart" -> DART
            else -> null
        }
    }
}

/**
 * Colours code row by row. A row is entered in the state the row before it left (0 for the first row), which
 * carries what spans rows: a comment, a string, a tag left open. Only ASCII characters decide a state, every
 * other one being a letter: a row read from its bytes undecoded ([RawRows]) then leaves the same state as its
 * decoded text, and [CodeColouring] scans a whole file that way, without decoding it.
 */
internal interface CodeTokenizer {
    /**
     * The state the row after [row] enters in, [row] being entered in [state]; [row]'s spans added to [spans]
     * when it is not null, in order and without overlap. [endsLine]: whether [row] ends its line — false for a
     * piece of a longer line, cut at [TextDocument.ROW_BYTES].
     */
    fun tokenize(row: CharSequence, state: Int, endsLine: Boolean, spans: MutableList<CodeSpan>?): Int
}

/** A letter, a digit, `_`, `$` or any non-ASCII character: a part of a word in every language coloured. */
internal fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '$' || c >= '\u0080'

internal fun isDigit(c: Char): Boolean = c in '0'..'9'

internal fun isBlank(c: Char): Boolean = c == ' ' || c == '\t'

/** Past the end of a row. */
internal const val NONE = '\u0000'

/** The character at [index], or [NONE] past the end of the row. */
internal fun CharSequence.peek(index: Int): Char = if (index < length) this[index] else NONE

/** The end of the word that starts at [from]. */
internal fun CharSequence.wordEnd(from: Int): Int {
    var end = from
    while (end < length && isWordChar(this[end])) end++
    return end
}

/** Whether [text] is written at [at]. */
internal fun CharSequence.hasAt(at: Int, text: String): Boolean {
    var matches = at >= 0 && at + text.length <= length
    var i = 0
    while (matches && i < text.length) {
        matches = this[at + i] == text[i]
        i++
    }
    return matches
}

/** Where [text] is next written from [from] on, starting before [until]; -1 when it is not. */
internal fun CharSequence.find(text: String, from: Int, until: Int = length): Int {
    var at = from
    val last = minOf(until, length - text.length + 1)
    while (at < last && !hasAt(at, text)) at++
    return if (at < last) at else -1
}

/** Whether the characters [start] until [end] are one of [words]. */
internal fun CharSequence.isOneOf(start: Int, end: Int, words: Array<String>): Boolean =
    words.any { it.length == end - start && hasAt(start, it) }

/** Adds a span of [style] over [start] until [end], when there is a list and something to colour. */
internal fun MutableList<CodeSpan>?.mark(start: Int, end: Int, style: CodeStyle) {
    if (this != null && start < end) add(CodeSpan(start, end, style))
}
