package com.readfilestech.read_files_tech.data.viewer.reader

import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.document.XmlEvents
import com.readfilestech.read_files_tech.data.viewer.reader.HtmlTokenizer.Token
import org.xmlpull.v1.XmlPullParser

/**
 * A book's package document (OPF): its manifest by id, in its order, and its spine — each idref once, in
 * the order it first comes. Read as XML, by the attributes' names alone (`opf:` or none): the 2.15.3 read
 * it with regexes, which took an `<item` in a comment, missed an `idref` after another attribute
 * (`<itemref linear="no" idref=…>`), and kept every item of a hostile OPF.
 */
internal class EpubPackage private constructor(
    val manifest: Map<String, Item>,
    val spine: List<String>,
    private val toc: String?,
) {
    /** A file of the book as the manifest lists it: where it is, relative to the OPF, and what it is. */
    class Item(val href: String, private val mediaType: String, private val properties: String) {
        /** What the reader reads as a chapter: XHTML, HTML or an EPUB 2 text type — by its extension when untyped. */
        val isDocument: Boolean
            get() = mediaType in DOCUMENT_TYPES ||
                (mediaType.isEmpty() && href.substringBefore('#').substringAfterLast('.').lowercase() in DOCUMENT_EXTENSIONS)

        /** The EPUB 3 navigation document. */
        val isNav: Boolean get() = "nav" in properties.split(' ', '\t', '\n', '\r')

        val isNcx: Boolean get() = mediaType == NCX_TYPE
    }

    /** The table of contents to title the chapters by. */
    class Contents(val href: String, val isNcx: Boolean)

    /** The EPUB 3 nav, else the NCX the spine names (EPUB 2), else any NCX the manifest lists. */
    fun contents(): Contents? {
        manifest.values.firstOrNull(Item::isNav)?.let { return Contents(it.href, isNcx = false) }
        val ncx = toc?.let(manifest::get) ?: manifest.values.firstOrNull(Item::isNcx)
        return ncx?.let { Contents(it.href, isNcx = true) }
    }

    companion object {
        /** The OPF's own media type, as `container.xml` names it. */
        private const val PACKAGE_TYPE = "application/oebps-package+xml"
        private const val NCX_TYPE = "application/x-dtbncx+xml"
        private val DOCUMENT_TYPES = setOf("application/xhtml+xml", "text/html", "application/x-dtbook+xml", "text/x-oeb1-document")
        private val DOCUMENT_EXTENSIONS = setOf("xhtml", "html", "htm")

        /** The algorithms of IDPF's and Adobe's font obfuscation: an obfuscated font is no obstacle to the text (OCF 3.3, 4.4). */
        private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

        /**
         * The manifest and the spine of [xml], an OPF: more than [maxItems] items in the manifest, or distinct
         * ones in the spine, are too many.
         */
        suspend fun read(xml: XmlEvents, maxItems: Int): EpubPackage {
            val manifest = LinkedHashMap<String, Item>()
            val spine = LinkedHashSet<String>()
            var toc: String? = null
            startTags(xml) { name ->
                when (name) {
                    "item" -> item(xml)?.let { (id, item) -> manifest.putIfAbsent(id, item) }
                    "itemref" -> xml.attribute(null, "idref")?.let(spine::add)
                    "spine" -> toc = xml.attribute(null, "toc")
                }
                if (manifest.size > maxItems || spine.size > maxItems) throw TooManyItemsException(maxItems)
                true
            }
            return EpubPackage(manifest, spine.toList(), toc)
        }

        private fun item(xml: XmlEvents): Pair<String, Item>? {
            val id = xml.attribute(null, "id")
            val href = xml.attribute(null, "href")
            val mediaType = xml.attribute(null, "media-type").orEmpty().trim().lowercase()
            return if (id == null || href == null) null else id to Item(href, mediaType, xml.attribute(null, "properties").orEmpty())
        }

        /** The package document `container.xml` names: its first rootfile of the OPF's type, else its first. */
        suspend fun packagePath(xml: XmlEvents): String? {
            var first: String? = null
            var typed: String? = null
            startTags(xml) { name ->
                if (name == "rootfile") {
                    val path = xml.attribute(null, "full-path")
                    if (path != null && xml.attribute(null, "media-type") == PACKAGE_TYPE) typed = path
                    if (first == null) first = path
                }
                typed == null
            }
            return typed ?: first
        }

        /**
         * Whether a book's `encryption.xml` encrypts anything but its fonts: a DRM (Adobe ADEPT, Readium LCP…)
         * whose chapters would read as noise — said as a format not opened. The 2.15.3 showed the noise.
         */
        suspend fun protects(xml: XmlEvents): Boolean {
            var protected = false
            startTags(xml) { name ->
                protected = name == "EncryptionMethod" && xml.attribute(null, "Algorithm") !in FONT_OBFUSCATION
                !protected
            }
            return protected
        }

        /** Calls [onStart] with the name of each start tag of [xml], until the document ends or it answers false. */
        private suspend inline fun startTags(xml: XmlEvents, onStart: (name: String) -> Boolean) {
            var more = true
            while (more) {
                more = when (xml.next()) {
                    XmlPullParser.END_DOCUMENT -> false
                    XmlPullParser.START_TAG -> onStart(xml.name)
                    else -> true
                }
            }
        }
    }
}

/**
 * The titles a book's table of contents gives its files, handed to [found] as (href, title) in its order —
 * the href as written, relative to the table's own file. Read with [HtmlTokenizer]: an NCX names its DTD,
 * and a nav document may use XHTML's entities. What it keeps is one title, at most [MAX_LABEL] characters.
 */
internal class EpubContents(private val found: (href: String, title: String) -> Unit) {
    private val label = StringBuilder()

    /** In a nav's `<nav epub:type="toc">`, and the href of its link being read. */
    private var inToc = false
    private var href: String? = null

    /** In an NCX point's label, and in its `<text>`. */
    private var inLabel = false
    private var inText = false

    /** An EPUB 3 nav document: the links of its `<nav epub:type="toc">`, or of a nav that says no type. */
    suspend fun readNav(tokens: HtmlTokenizer) = read(tokens, ::navToken)

    /** An EPUB 2 NCX: each point's label, then the src of its content. */
    suspend fun readNcx(tokens: HtmlTokenizer) = read(tokens, ::ncxToken)

    private suspend fun read(tokens: HtmlTokenizer, onToken: (Token, HtmlTokenizer) -> Unit) {
        var token = tokens.next()
        while (token != Token.END_OF_INPUT) {
            onToken(token, tokens)
            token = tokens.next()
        }
    }

    private fun navToken(token: Token, tokens: HtmlTokenizer) {
        when {
            token == Token.START && tokens.name == "nav" -> inToc = tokens.attribute(TYPE)?.let { "toc" in it.split(' ') } ?: true
            token == Token.START && tokens.name == "a" && inToc -> {
                href = tokens.attribute(HREF)
                label.setLength(0)
            }
            token == Token.END && tokens.name == "nav" -> inToc = false
            token == Token.END && tokens.name == "a" -> {
                href?.let(::give)
                href = null
            }
            token == Token.TEXT && href != null -> collect(tokens.text)
        }
    }

    private fun ncxToken(token: Token, tokens: HtmlTokenizer) {
        when {
            token == Token.START && tokens.name == "navlabel" -> {
                inLabel = true
                label.setLength(0)
            }
            token == Token.START && tokens.name == "text" -> inText = inLabel
            token == Token.START && tokens.name == "content" -> tokens.attribute(SRC)?.let(::give)
            token == Token.END && tokens.name == "navlabel" -> inLabel = false
            token == Token.END && tokens.name == "text" -> inText = false
            token == Token.TEXT && inText -> collect(tokens.text)
        }
    }

    private fun collect(text: CharSequence) {
        if (label.length < MAX_LABEL) label.append(text, 0, minOf(text.length, MAX_LABEL - label.length))
    }

    /** Hands [href] and the label collected, white space collapsed, when there is one. */
    private fun give(href: String) {
        val title = label.split(' ', '\t', '\n', '\r', '\u000C').filter(String::isNotEmpty).joinToString(" ")
        if (title.isNotEmpty()) found(href, title)
    }

    companion object {
        private const val HREF = "href"
        private const val SRC = "src"
        private const val TYPE = "epub:type"

        /** The attributes the tokenizer keeps for these readers. */
        val ATTRIBUTES = setOf(HREF, SRC, TYPE)

        /** The characters of one label kept: twice a title's, so that white space collapsed leaves one. */
        const val MAX_LABEL = 2 * EpubBook.TITLE_CHARS
    }
}
