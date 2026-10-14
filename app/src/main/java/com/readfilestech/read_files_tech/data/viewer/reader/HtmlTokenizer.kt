package com.readfilestech.read_files_tech.data.viewer.reader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Reader
import kotlin.coroutines.CoroutineContext

/**
 * The tokens of an HTML or XHTML text, as far as a reader needs them: start and end tags by name, the few
 * attributes the caller names in [wanted], and text with its character references decoded ([HtmlEntities]).
 *
 * Lenient as a browser, for pages are rarely well formed, yet it never fetches nor resolves anything: a
 * DOCTYPE is passed over whatever it declares — its internal subset included, so a "billion laughs"
 * defines no entity, and an external DTD is never read — as are comments, processing instructions and the
 * content of `script`, `style` and their kind ([RAW_TEXT]). Its memory is constant whatever the input: a
 * name and a wanted value are each bounded, the rest of an endless tag read past; text comes in pieces of
 * [TEXT_PIECE] characters at most. The time is linear in the input, and the coroutine's cancellation is
 * checked at each buffer of [BUFFER] characters, inside the longest comment or tag too.
 *
 * Not the platform's XmlPullParser, even relaxed (measured on Android's KXmlParser): with a DTD named, it
 * maps every named reference to nothing (`caf&eacute;` read `caf`), and it reads a page's `<br>` and `<p>`
 * as never closed, a script's `<` as a tag. Not jsoup: its tree of a 50 MiB chapter held 782 MB, and its
 * stream parser took 13 s over it on a desktop.
 */
// TooManyFunctions: one small function per state of the HTML tokenizer it follows (HTML standard, 13.2.5).
@Suppress("TooManyFunctions")
internal class HtmlTokenizer(private val input: Reader, private val wanted: Set<String> = emptySet()) {
    enum class Token { TEXT, START, END, END_OF_INPUT }

    /** The tag's name, lower case (ASCII), at most [MAX_NAME] characters: after [Token.START] and [Token.END]. */
    var name: String = ""
        private set

    /** `<x/>`: an element without content, as XHTML writes it. After [Token.START]. */
    var selfClosing: Boolean = false
        private set

    /** After [Token.TEXT]: its characters, references decoded; valid until the next call. */
    val text: CharSequence get() = textPiece

    /** A [wanted] attribute of the last start tag, references decoded; null when absent or longer than [MAX_VALUE]. */
    fun attribute(name: String): String? = attributes[name]

    private val buffer = CharArray(BUFFER)
    private var position = 0
    private var limit = 0
    private var pushedBack = NONE
    private val textPiece = StringBuilder()
    private val nameBuilder = StringBuilder()
    private val valueBuilder = StringBuilder()
    private val attributes = HashMap<String, String>()
    private var context: CoroutineContext? = null

    /** A tag read while text was pending: the text is given first, the tag at the next call. */
    private var pending: Token? = null

    /** The raw text element whose content is to be passed over, once its start tag was given. */
    private var rawUntil: String? = null

    /** Inside a CDATA section; and its `]` read but not given yet — two at most, they may end it. */
    private var inCdata = false
    private var cdataBrackets = 0

    /** The next token. */
    suspend fun next(): Token {
        if (context == null) context = currentCoroutineContext()
        val held = pending
        pending = null
        return held ?: rawUntil?.let { raw ->
            rawUntil = null
            skipRawText(raw)
            name = raw
            Token.END
        } ?: text()
    }

    /** Text up to the next tag, or [TEXT_PIECE] characters of it: the tag, when no text comes before it. */
    private fun text(): Token {
        textPiece.setLength(0)
        var tag: Token? = null
        var ended = false
        while (tag == null && !ended && textPiece.length < TEXT_PIECE) {
            if (inCdata) {
                cdata()
            } else {
                when (val c = read()) {
                    NONE -> ended = true
                    '<'.code -> tag = markup()
                    '&'.code -> reference(textPiece)
                    else -> textPiece.append(c.toChar())
                }
            }
        }
        return when {
            textPiece.isNotEmpty() -> Token.TEXT.also { pending = tag }
            else -> tag ?: Token.END_OF_INPUT
        }
    }

    /** After `<`: the token it opens, or null for a comment, a declaration, or a `<` that opens nothing. */
    private fun markup(): Token? {
        val c = read()
        return when {
            c == '!'.code -> null.also { declaration() }
            c == '?'.code -> null.also { skipPast('>') }
            c == '/'.code -> endTag()
            isLetter(c) -> startTag(c)
            else -> null.also {
                textPiece.append('<')
                unread(c)
            }
        }
    }

    private fun endTag(): Token? {
        val c = read()
        if (!isLetter(c)) {
            // `</>` is nothing, `</ x>` a bogus comment (HTML standard).
            if (c != '>'.code) skipPast('>')
            return null
        }
        readName(c, TAG_STOPS)
        skipPast('>')
        name = nameBuilder.toString()
        return Token.END
    }

    /** A start tag from the first letter of its name; null when the input ends inside it, as HTML drops it. */
    private fun startTag(first: Int): Token? {
        readName(first, TAG_STOPS)
        val tag = nameBuilder.toString()
        attributes.clear()
        selfClosing = false
        var c = read()
        while (c != NONE && c != '>'.code) {
            when {
                isSpace(c) -> Unit
                c == '/'.code -> {
                    val after = read()
                    if (after == '>'.code) selfClosing = true else unread(after)
                }
                else -> readAttribute(c)
            }
            if (selfClosing) break
            c = read()
        }
        if (c == NONE) return null
        name = tag
        if (!selfClosing && tag in RAW_TEXT) rawUntil = tag
        return Token.START
    }

    /** One attribute from its first character: its value kept only when its name is [wanted], the first time. */
    private fun readAttribute(first: Int) {
        readName(first, ATTRIBUTE_STOPS)
        val attributeName = nameBuilder.toString()
        var c = read()
        while (isSpace(c)) c = read()
        if (c != '='.code) {
            unread(c)
            return
        }
        c = read()
        while (isSpace(c)) c = read()
        val keep = attributeName in wanted && attributeName !in attributes
        val quote = if (c == '"'.code || c == '\''.code) c else NONE.also { unread(c) }
        readValue(quote, keep)
        if (keep && valueBuilder.length <= MAX_VALUE) attributes[attributeName] = valueBuilder.toString()
    }

    /** An attribute's value, to its closing [quote] or, unquoted ([NONE]), to a space or the tag's end: into [valueBuilder] if [keep]. */
    private fun readValue(quote: Int, keep: Boolean) {
        valueBuilder.setLength(0)
        while (true) {
            val c = read()
            val ends = if (quote == NONE) c == NONE || isSpace(c) || c == '>'.code else c == NONE || c == quote
            if (ends) {
                if (c == '>'.code && quote == NONE) unread(c)
                return
            }
            if (keep && valueBuilder.length <= MAX_VALUE) {
                if (c == '&'.code) reference(valueBuilder) else valueBuilder.append(c.toChar())
            }
        }
    }

    /** A name from [first] to a space or one of [stops], lower case, its first [MAX_NAME] characters kept. */
    private fun readName(first: Int, stops: String) {
        nameBuilder.setLength(0)
        var c = first
        while (c != NONE && !isSpace(c) && c.toChar() !in stops) {
            if (nameBuilder.length < MAX_NAME) nameBuilder.append(lowerAscii(c))
            c = read()
        }
        unread(c)
    }

    /** After `<!`: a comment or a declaration passed over; a CDATA section starts its text. */
    private fun declaration() {
        val c = read()
        when {
            c == '-'.code -> {
                val second = read()
                if (second == '-'.code) skipComment() else skipDeclaration(second)
            }
            c == '['.code && matches(CDATA_AFTER_BRACKET) -> inCdata = true
            else -> skipDeclaration(c)
        }
    }

    /** After `<!--`: to its `-->`; `<!-->` and `<!--->` are empty comments already (HTML standard). */
    private fun skipComment() {
        var dashes = 2
        var c = read()
        while (c != NONE && !(c == '>'.code && dashes >= 2)) {
            dashes = if (c == '-'.code) dashes + 1 else 0
            c = read()
        }
    }

    /**
     * A DOCTYPE or any other `<!…>`, from [first]: ends at its `>`, but not at one in quotes nor in its
     * internal subset (`[…]`), whose declarations are passed over — never defined.
     */
    private fun skipDeclaration(first: Int) {
        var c = first
        var quote = NONE
        var subset = false
        while (c != NONE) {
            if (quote == NONE && !subset && c == '>'.code) return
            when {
                quote != NONE -> if (c == quote) quote = NONE
                c == '"'.code || c == '\''.code -> quote = c
                c == '['.code -> subset = true
                c == ']'.code -> subset = false
            }
            c = read()
        }
    }

    /** A CDATA section's characters as they are, up to its `]]>`, into the current piece. */
    private fun cdata() {
        while (inCdata && textPiece.length < TEXT_PIECE) {
            val c = read()
            when {
                c == NONE || (c == '>'.code && cdataBrackets == 2) -> {
                    if (c == NONE) repeat(cdataBrackets) { textPiece.append(']') }
                    cdataBrackets = 0
                    inCdata = false
                }
                c == ']'.code -> if (cdataBrackets == 2) textPiece.append(']') else cdataBrackets++
                else -> {
                    repeat(cdataBrackets) { textPiece.append(']') }
                    cdataBrackets = 0
                    textPiece.append(c.toChar())
                }
            }
        }
    }

    /** Whether the next characters are [expected]; on the first that differs, that one is given back. */
    private fun matches(expected: String): Boolean {
        for (char in expected) {
            val c = read()
            if (c != char.code) {
                unread(c)
                return false
            }
        }
        return true
    }

    /** The content of a raw text element, passed over to its end tag: `</script` then a space, `/` or `>`. */
    private fun skipRawText(tag: String) {
        var c = read()
        while (c != NONE && !closes(c, tag)) c = read()
    }

    /** Whether [c] opens [tag]'s end tag — `</`, its name, then a space, `/` or `>` — passed over when it does. */
    private fun closes(c: Int, tag: String): Boolean {
        if (c != '<'.code || !matches("/")) return false
        var matched = 0
        var next = read()
        while (matched < tag.length && lowerAscii(next) == tag[matched]) {
            matched++
            next = read()
        }
        val closes = matched == tag.length && endsName(next)
        if (!closes) unread(next) else if (next != '>'.code) skipPast('>')
        return closes
    }

    /**
     * After `&`: the character a reference names, appended to [out] — or what was read, as it was, when it
     * names none. A numeric reference may lack its `;` (HTML standard); a named one needs it.
     */
    private fun reference(out: StringBuilder) {
        val start = out.length
        out.append('&')
        var c = read()
        if (c == '#'.code) {
            out.append('#')
            numericReference(out, start)
            return
        }
        while (isLetterOrDigit(c) && out.length - start <= HtmlEntities.LONGEST_NAME) {
            out.append(c.toChar())
            c = read()
        }
        val resolved = if (c == ';'.code) HtmlEntities.named(out.substring(start + 1)) else null
        if (resolved != null) {
            out.setLength(start)
            out.append(resolved)
        } else {
            unread(c)
        }
    }

    private fun numericReference(out: StringBuilder, start: Int) {
        var c = read()
        val hex = c == 'x'.code || c == 'X'.code
        val radix = if (hex) HEX else DECIMAL
        if (hex) {
            out.append(c.toChar())
            c = read()
        }
        var code = 0L
        var digits = 0
        while (c != NONE && Character.digit(c, radix) >= 0) {
            out.append(c.toChar())
            // Past Unicode it is U+FFFD whatever follows: the value stops growing there.
            code = minOf(code * radix + Character.digit(c, radix), Character.MAX_CODE_POINT + 1L)
            digits++
            c = read()
        }
        if (digits == 0 || c != ';'.code) unread(c)
        if (digits > 0) {
            out.setLength(start)
            out.append(HtmlEntities.numeric(code.toInt()))
        }
    }

    private fun skipPast(end: Char) {
        var c = read()
        while (c != NONE && c != end.code) c = read()
    }

    private fun read(): Int = when {
        pushedBack != NONE -> pushedBack.also { pushedBack = NONE }
        position == limit && !fill() -> NONE
        else -> buffer[position++].code
    }

    /** Gives [c] back to the next [read]: one character at most, all the grammar above needs. */
    private fun unread(c: Int) {
        check(pushedBack == NONE) { "two characters given back" }
        pushedBack = c
    }

    private fun fill(): Boolean {
        context?.ensureActive()
        val n = input.read(buffer, 0, BUFFER)
        if (n > 0) {
            position = 0
            limit = n
        }
        return n > 0
    }

    companion object {
        /** Characters read from the input at once; the cancellation is checked at each. */
        const val BUFFER = 64 * 1024

        /** The most characters one [Token.TEXT] holds (a reference may pass it by a few): a longer text comes in several. */
        const val TEXT_PIECE = 8 * 1024

        /** The characters of a name kept: no element nor attribute a reader knows is that long. */
        const val MAX_NAME = 64

        /** The characters of a wanted attribute's value kept: past it, the attribute is as absent. */
        const val MAX_VALUE = 4 * 1024

        /** Elements whose content is text up to their end tag — never markup, and never shown. */
        val RAW_TEXT = setOf("script", "style", "textarea", "title", "iframe", "noembed", "noframes", "xmp")

        private const val NONE = -1
        private const val HEX = 16
        private const val DECIMAL = 10
        private const val TAG_STOPS = "/>"
        private const val ATTRIBUTE_STOPS = "=/>"
        private const val CDATA_AFTER_BRACKET = "CDATA["

        /** HTML's white space: tab, line feed, form feed, carriage return, space. */
        private const val SPACES = "\t\n\u000C\r "

        private fun isSpace(c: Int): Boolean = c >= 0 && c.toChar() in SPACES

        /** What ends a tag's name in an end tag being matched: a space, `/` or `>`. */
        private fun endsName(c: Int): Boolean = isSpace(c) || c == '/'.code || c == '>'.code

        private fun isLetter(c: Int): Boolean = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

        private fun isLetterOrDigit(c: Int): Boolean = isLetter(c) || c in '0'.code..'9'.code

        private fun lowerAscii(c: Int): Char = if (c in 'A'.code..'Z'.code) (c + ('a' - 'A')).toChar() else c.toChar()
    }
}
