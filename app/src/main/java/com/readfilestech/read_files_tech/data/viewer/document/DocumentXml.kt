package com.readfilestech.read_files_tech.data.viewer.document

import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FilterReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.Reader

/** The XML parts of a document, read with the platform's pull parser under bounds a hostile file cannot move. */
internal object DocumentXml {
    private const val BOM_BYTES = 3

    /**
     * A pull parser over [input]: namespace-aware, and NEVER processing a DTD. Not `android.util.Xml.newPullParser()`,
     * which turns `FEATURE_PROCESS_DOCDECL` on (AOSP `Xml.java`): the factory leaves every feature off, and on
     * Android makes the platform's KXmlParser. What it reads first goes through [XmlGuard].
     */
    fun parser(input: InputStream, maxStretch: Long): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        check(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) { "a parser that processes DTDs" }
        parser.setInput(XmlGuard(reader(input), maxStretch))
        return parser
    }

    /**
     * The characters of a part: UTF-8 or UTF-16, the only encodings its formats allow (OPC, ODF), told by a
     * byte order mark; UTF-8 without one. A malformed sequence becomes U+FFFD, as the 2.15.3 decoded it. Also
     * how the reader decodes an EPUB's chapters and table of contents, whose formats allow the same two.
     */
    fun reader(input: InputStream): Reader {
        val stream = PushbackInputStream(input, BOM_BYTES)
        val head = ByteArray(BOM_BYTES)
        var size = 0
        while (size < BOM_BYTES) {
            val n = stream.read(head, size, BOM_BYTES - size)
            if (n == -1) break
            size += n
        }
        val encoding = TextEncoding.detect(head, size)
        stream.unread(head, encoding.bomLength, size - encoding.bomLength)
        val charset = if (encoding == TextEncoding.WINDOWS_1252) Charsets.UTF_8 else encoding.charset
        return InputStreamReader(stream, charset)
    }
}

/**
 * What the parser is handed of a part: its characters unchanged, after two checks the parser does not
 * make. A `<!` that opens neither a comment nor a CDATA section — a DOCTYPE, and with it every entity a
 * "billion laughs" declares — is refused before the parser reads it: Word and LibreOffice never write one,
 * and OPC forbids it. And no stretch between two tags (a text, comments and CDATA sections included, or
 * one tag with its attributes) grows past [maxStretch] chars — the most the parser then holds at once,
 * whatever it coalesces.
 *
 * A small lexer that follows the markup only to know where stretches end: it ends a comment, a CDATA
 * section or a processing instruction at its first terminator, as the parser does, so it can only see a
 * `<!` earlier than the parser, never later.
 */
internal class XmlGuard(input: Reader, private val maxStretch: Long) : FilterReader(input) {
    private var state = State.TEXT
    private var stretch = 0L

    /** Dashes, brackets or the question mark just seen, or the chars of `CDATA[` matched so far. */
    private var seen = 0

    private enum class State { TEXT, OPEN, BANG, COMMENT_OPEN, CDATA_OPEN, COMMENT, CDATA, PROCESSING, TAG, DOUBLE_QUOTED, SINGLE_QUOTED }

    override fun read(): Int {
        val c = super.read()
        if (c >= 0) scan(c.toChar())
        return c
    }

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val n = super.read(cbuf, off, len)
        for (i in off until off + n) scan(cbuf[i])
        return n
    }

    /** Through [read]: a skip of the reader below would pass characters unseen. */
    override fun skip(n: Long): Long {
        var skipped = 0L
        while (skipped < n && read() != -1) skipped++
        return skipped
    }

    override fun markSupported(): Boolean = false

    private fun scan(c: Char) {
        stretch++
        when (state) {
            State.TEXT -> if (c == '<') state = State.OPEN
            State.OPEN -> opened(c)
            State.BANG, State.COMMENT_OPEN, State.CDATA_OPEN -> declaration(c)
            State.COMMENT -> closing(c, '-', 2)
            State.CDATA -> closing(c, ']', 2)
            State.PROCESSING -> closing(c, '?', 1)
            State.TAG, State.DOUBLE_QUOTED, State.SINGLE_QUOTED -> tag(c)
        }
        if (stretch > maxStretch) throw FileTooLargeException(maxStretch)
    }

    /** The char after a `<`: a tag starts there, and the stretch before it ends. */
    private fun opened(c: Char) {
        state = when (c) {
            '!' -> State.BANG
            '?' -> State.PROCESSING.also { seen = 0 }
            else -> State.TAG.also { stretch = 2 }
        }
    }

    /** After `<!`: `--` opens a comment, `[CDATA[` a CDATA section; anything else is a declaration, refused. */
    private fun declaration(c: Char) {
        state = when {
            state == State.BANG && c == '-' -> State.COMMENT_OPEN
            state == State.BANG && c == '[' -> State.CDATA_OPEN.also { seen = 0 }
            state == State.COMMENT_OPEN && c == '-' -> State.COMMENT.also { seen = 0 }
            state == State.CDATA_OPEN && c == CDATA[seen] -> if (++seen == CDATA.length) State.CDATA.also { seen = 0 } else state
            else -> refuse()
        }
    }

    /** Inside a comment, a CDATA section or a processing instruction: ends at [count] [mark]s and a `>`. */
    private fun closing(c: Char, mark: Char, count: Int) {
        seen = when {
            c == mark -> seen + 1
            c == '>' && seen >= count -> 0.also { state = State.TEXT }
            else -> 0
        }
    }

    /** Inside a tag: a `>` in a quoted value does not end it. */
    private fun tag(c: Char) {
        state = when {
            state == State.DOUBLE_QUOTED -> if (c == '"') State.TAG else state
            state == State.SINGLE_QUOTED -> if (c == '\'') State.TAG else state
            c == '"' -> State.DOUBLE_QUOTED
            c == '\'' -> State.SINGLE_QUOTED
            c == '>' -> State.TEXT.also { stretch = 0 }
            else -> state
        }
    }

    private fun refuse(): Nothing = throw UnreadableFileException("an XML declaration (<!DOCTYPE, <!ENTITY…): never in a document part")

    private companion object {
        const val CDATA = "CDATA["
    }
}

/**
 * The events of a part, each START_TAG checked against [maxDepth] and counted against [maxElements], the
 * coroutine's cancellation checked every [CANCEL_CHECK_EVERY] events: a parser's one call is bounded by
 * [XmlGuard]'s stretch.
 */
internal class XmlEvents(private val parser: XmlPullParser, private val maxDepth: Int, private val maxElements: Int) {
    private var count = 0
    private var elements = 0

    val depth: Int get() = parser.depth
    val name: String get() = parser.name
    val namespace: String get() = parser.namespace
    val text: String get() = parser.text

    fun attribute(namespace: String?, name: String): String? = parser.getAttributeValue(namespace, name)

    suspend fun next(): Int {
        if (++count % CANCEL_CHECK_EVERY == 0) currentCoroutineContext().ensureActive()
        val type = parser.next()
        if (type == XmlPullParser.START_TAG) {
            if (parser.depth > maxDepth) throw UnreadableFileException("XML nested deeper than $maxDepth")
            if (++elements > maxElements) throw TooManyItemsException(maxElements)
        }
        return type
    }

    /** From a START_TAG: passes it, everything inside it and its END_TAG. */
    suspend fun skip() {
        val start = parser.depth
        while (true) {
            when (next()) {
                XmlPullParser.END_TAG -> if (parser.depth == start) return
                XmlPullParser.END_DOCUMENT -> throw UnreadableFileException("an element never closed")
            }
        }
    }

    /** The root element is [name] in one of [namespaces]: a part of the format the file was taken for. */
    suspend fun requireRoot(namespaces: Set<String>, name: String) {
        var type = next()
        while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) type = next()
        if (type != XmlPullParser.START_TAG || parser.namespace !in namespaces || parser.name != name) {
            throw UnreadableFileException("not the root element of a document part")
        }
    }

    private companion object {
        const val CANCEL_CHECK_EVERY = 4096
    }
}
