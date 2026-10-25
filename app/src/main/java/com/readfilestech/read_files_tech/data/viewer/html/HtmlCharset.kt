package com.readfilestech.read_files_tech.data.viewer.html

import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException

/**
 * The encoding a page declares in a `<meta>` of its start — `<meta charset="…">`, or the Word style
 * `<meta http-equiv=Content-Type content="text/html; charset=windows-1252">` — found as a browser's prescan
 * finds it (HTML standard, "prescan a byte stream"): comments and the attributes of other tags skipped,
 * so a `charset=` in a comment or in an attribute's value is not taken.
 *
 * The 2.15.3 read every page as strict UTF-8 (`readAsString`): a Windows-1252 page — Word's "Save as Web
 * Page" in French — was refused whole. The loader now tells the WebView the encoding (HtmlPage.charset).
 */
internal object HtmlCharset {
    /** How far the prescan reads: Word's `<head>` carries its styles before the end of it. */
    const val PRESCAN_BYTES = 64 * 1024

    /** The label declared in the first [PRESCAN_BYTES] of [bytes], when it is one the platform knows. */
    fun declared(bytes: ByteArray, size: Int): String? {
        // One byte, one character: the markup the prescan looks for is ASCII in every encoding it applies to.
        val head = String(bytes, 0, minOf(size, PRESCAN_BYTES), Charsets.ISO_8859_1)
        var i = 0
        var found: String? = null
        while (found == null && i < head.length) {
            val meta = head.startsWith(META, i, ignoreCase = true) && isSeparator(head.getOrNull(i + META.length))
            val tag = if (meta || isTagStart(head, i)) Tag.read(head, i + if (meta) META.length else 1) else null
            found = tag?.takeIf { meta }?.charset()
            i = tag?.end ?: skipOther(head, i)
        }
        return found
    }

    /** Past a comment, a `<!…>` or a `<?…>`, else one character. The dashes of `<!--` may close it: `<!-->`. */
    private fun skipOther(head: String, i: Int): Int = when {
        head.startsWith(COMMENT, i) -> after(head, COMMENT_END, i + 2)
        head.startsWith("<!", i) || head.startsWith("<?", i) -> after(head, ">", i)
        else -> i + 1
    }

    private fun after(head: String, end: String, from: Int): Int {
        val at = head.indexOf(end, from)
        return if (at < 0) head.length else at + end.length
    }

    /** `<` and a letter, or `</`: a start or end tag, whose attributes are skipped whole. */
    private fun isTagStart(head: String, i: Int): Boolean =
        head[i] == '<' && head.getOrNull(i + 1).let { it != null && (it.isLetter() || it == '/') }

    private fun isSeparator(c: Char?): Boolean = c != null && (c in WHITESPACE || c == '/')

    /**
     * The canonical name of [label] when the platform knows it — a name the WebView knows too — or null. An
     * encoding in which `<meta` is not written as in ASCII is read as UTF-8, as the standard says of UTF-16
     * (Word writes `charset=unicode`): the prescan just read this `<meta>` in ASCII, so the page is not in it.
     */
    private fun usable(label: String): String? {
        val name = label.trim { it in WHITESPACE }
        // An illegal name (a space, a `;`, a quote) is refused by the platform itself.
        val charset = try {
            name.takeIf { Charset.isSupported(it) }?.let { Charset.forName(it) }
        } catch (expected: IllegalCharsetNameException) {
            null
        } ?: return null
        return if (charset.canEncode() && META.toByteArray(charset).contentEquals(META_ASCII)) charset.name() else Charsets.UTF_8.name()
    }

    /** "Extract a character encoding from a meta element": `charset`, `=`, then a quoted or bare value. */
    private fun fromContent(content: String): String? =
        CONTENT_CHARSET.find(content)?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)

    /** The attributes of one tag, from just after its name to just after its `>`. */
    private class Tag(val attributes: Map<String, String>, val end: Int) {
        /** `charset`, else the `content` of an `http-equiv="content-type"` — never both, as the standard reads it. */
        fun charset(): String? {
            val label = attributes["charset"] ?: attributes["content"]
                ?.takeIf { attributes["http-equiv"].equals("content-type", ignoreCase = true) }
                ?.let(::fromContent)
            return label?.let(::usable)
        }

        companion object {
            /** "Get an attribute", repeated until the tag's `>`; the first of two same names wins. */
            fun read(head: String, start: Int): Tag {
                val attributes = mutableMapOf<String, String>()
                var i = skip(head, start, SEPARATORS)
                while (i < head.length && head[i] != '>') {
                    val nameEnd = until(head, i, NAME_STOPS)
                    val name = head.substring(i, nameEnd).lowercase()
                    i = skip(head, nameEnd, WHITESPACE)
                    val (value, next) = if (head.getOrNull(i) == '=') value(head, skip(head, i + 1, WHITESPACE)) else "" to i
                    attributes.putIfAbsent(name, value)
                    i = skip(head, next, SEPARATORS)
                }
                return Tag(attributes, minOf(i + 1, head.length))
            }

            /** A value from [i]: quoted, to its closing quote (or the end), else bare, to a blank or the `>`. */
            private fun value(head: String, i: Int): Pair<String, Int> {
                val quote = head.getOrNull(i)
                return if (quote == '"' || quote == '\'') {
                    val close = head.indexOf(quote, i + 1).let { if (it < 0) head.length else it }
                    head.substring(i + 1, close) to close + 1
                } else {
                    val end = until(head, i, VALUE_STOPS)
                    head.substring(i, end) to end
                }
            }

            private fun skip(head: String, from: Int, these: String): Int {
                var i = from
                while (i < head.length && head[i] in these) i++
                return i
            }

            private fun until(head: String, from: Int, stops: String): Int {
                var i = from
                while (i < head.length && head[i] !in stops) i++
                return i
            }
        }
    }

    private const val COMMENT = "<!--"
    private const val COMMENT_END = "-->"
    private const val META = "<meta"
    private val META_ASCII = META.toByteArray(Charsets.US_ASCII)

    /** What the HTML standard calls whitespace: tab, line feed, form feed, carriage return, space. */
    private const val WHITESPACE = "\t\n\u000C\r "
    private const val SEPARATORS = "$WHITESPACE/"
    private const val NAME_STOPS = "=>/$WHITESPACE"
    private const val VALUE_STOPS = ">$WHITESPACE"
    private val CONTENT_CHARSET = Regex(
        "charset[\\t\\n\\f\\r ]*=[\\t\\n\\f\\r ]*(?:\"([^\"]*)\"|'([^']*)'|([^\\t\\n\\f\\r ;\"']+))",
        RegexOption.IGNORE_CASE,
    )
}
