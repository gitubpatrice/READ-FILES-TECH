package com.readfilestech.read_files_tech.data.viewer.reader

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.sheet.TextBudget

/**
 * What is not read: the elements whose text is not part of what is read. A book's chapter loses only what
 * is no text (scripts, styles, forms, its `<head>`); a page in reading mode also its clutter — navigation,
 * asides, header and footer — the 2.15.3's list (`reader_service.dart:24-34`), which it also applied to a
 * book's chapters, so an EPUB 3 chapter's title in its `<header>`, and its notes in `<aside>`, were lost.
 */
internal enum class ReadingMode(val skipped: Set<String>, val prefersArticle: Boolean) {
    CHAPTER(NOT_TEXT, prefersArticle = false),
    PAGE(NOT_TEXT + setOf("nav", "aside", "footer", "header"), prefersArticle = true),
}

private val NOT_TEXT = setOf("head", "script", "style", "template", "iframe", "form", "noscript")

/**
 * The blocks of an HTML or XHTML text ([HtmlTokenizer]), read as the 2.15.3 read them (`_walk`,
 * `reader_service.dart:180-221`): a heading, a paragraph, a quote or a list item is one block of its whole
 * text, white space collapsed; and in [ReadingMode.PAGE] the first `<article>`, else the first `<main>`,
 * else the whole body. Fixed: the text outside those elements — a `<div>`'s, a table cell's, an `<h5>`'s —
 * is a paragraph, where the 2.15.3 dropped it; a `<br>` is a line break, and two blocks nested in one are
 * two lines, where it glued their words; a `<pre>` keeps its lines.
 *
 * Without building a tree: a stack of the open elements' names, [maxDepth] deep — the elements nested
 * deeper are read as their parent's text, as a browser flattens them — and the HTML rules that close a
 * paragraph or a list item left open. What it keeps is spent from [budget] ([BlockText]).
 */
internal class BlockReader(
    private val mode: ReadingMode,
    budget: TextBudget,
    private val maxDepth: Int = FileCaps.XML_DEPTH,
) {
    private val stack = arrayOfNulls<String>(maxDepth)
    private var size = 0
    private var skipping = 0
    private var preformatted = 0
    private var headAt = NONE

    /** The depth of the element whose block is open, or [NONE]: then an open block is loose text's. */
    private var capture = NONE
    private val blocks = BlockText(budget)
    private val article = Region()
    private val main = Region()

    /** The first element of a name, once opened: the blocks it holds, [start] to [end]. */
    private class Region(var start: Int = NONE, var end: Int = NONE, var depth: Int = NONE) {
        val holdsBlocks: Boolean get() = start != NONE && end > start

        /** Opened, at [depth], when [count] blocks are made — the first time only. */
        fun opened(depth: Int, count: Int) {
            if (start != NONE) return
            start = count
            this.depth = depth
        }

        /** The element at [closed] depth ends: when it is this region's, its blocks end at [count]. */
        fun closedAt(closed: Int, count: Int) {
            if (depth != closed) return
            end = count
            depth = NONE
        }
    }

    suspend fun read(tokens: HtmlTokenizer): ReaderBlocks {
        while (true) {
            when (tokens.next()) {
                HtmlTokenizer.Token.START -> start(tokens.name, tokens.selfClosing)
                HtmlTokenizer.Token.END -> end(tokens.name)
                HtmlTokenizer.Token.TEXT -> if (skipping == 0) blocks.append(tokens.text, preformatted > 0)
                HtmlTokenizer.Token.END_OF_INPUT -> break
            }
        }
        popTo(0)
        blocks.finish()
        val all = blocks.build()
        return when {
            !mode.prefersArticle -> all
            article.holdsBlocks -> all.slice(article.start, article.end)
            main.holdsBlocks -> all.slice(main.start, main.end)
            else -> all
        }
    }

    private fun start(name: String, selfClosing: Boolean) {
        // Content in a <head> never closed: the body has begun (HTML standard).
        if (headAt != NONE && name !in HEAD_CONTENT) popTo(headAt)
        closeImplied(name)
        when {
            name == "br" -> if (skipping == 0) blocks.lineBreak(hard = true)
            // Past the depth an element is not followed, as a browser flattens what is nested too deep: its
            // text is its parent's, and its end tag closes whatever open element bears its name.
            selfClosing || name in VOID || size == maxDepth -> if (skipping == 0 && name in BLOCK_LEVEL) boundary()
            else -> opened(name)
        }
    }

    private fun opened(name: String) {
        stack[size++] = name
        if (name == "head") headAt = size - 1
        if (name == "pre") preformatted++
        if (name in mode.skipped) skipping++
        if (skipping > 0) return
        val kind = BLOCK_KINDS[name]
        when {
            kind != null && capture == NONE -> {
                blocks.finish()
                blocks.open(kind)
                capture = size
            }
            name in BLOCK_LEVEL -> boundary()
        }
        if (mode.prefersArticle && name == "article") article.opened(size, blocks.count)
        if (mode.prefersArticle && name == "main") main.opened(size, blocks.count)
    }

    private fun end(name: String) {
        if (name == "br") return start(name, selfClosing = true)
        // An end tag with no element open of its name closes nothing.
        val at = (size - 1 downTo 0).firstOrNull { stack[it] == name } ?: return
        popTo(at)
    }

    /** The open paragraph, list item or cell a starting [name] closes, as HTML does. */
    private fun closeImplied(name: String) {
        while (size > 0) {
            val top = stack[size - 1]
            val closed = (top == "p" && name in BLOCK_LEVEL) || IMPLIED_CLOSES[name]?.contains(top) == true
            if (!closed) return
            pop()
        }
    }

    private fun popTo(index: Int) {
        while (size > index) pop()
    }

    private fun pop() {
        val depth = size
        val name = checkNotNull(stack[--size])
        stack[size] = null
        if (depth == capture) {
            blocks.finish()
            capture = NONE
        } else if (skipping == 0 && name in BLOCK_LEVEL) {
            boundary()
        }
        if (name in mode.skipped) skipping--
        if (name == "pre") preformatted--
        if (depth - 1 == headAt) headAt = NONE
        article.closedAt(depth, blocks.count)
        main.closedAt(depth, blocks.count)
    }

    /** A block-level element starts or ends: a line in the block it is in, else the loose text before it ends. */
    private fun boundary() {
        if (capture != NONE) blocks.lineBreak(hard = false) else blocks.finish()
    }

    companion object {
        /**
         * The characters of the budget a block costs beside its text: its end and kind (5 bytes), and the
         * row the screen lays out for it — 300 000 empty paragraphs are no free lunch.
         */
        const val BLOCK_CHARGE = 8

        private const val NONE = -1

        private val BLOCK_KINDS = mapOf(
            "h1" to BlockKind.HEADING_1,
            "h2" to BlockKind.HEADING_2,
            "h3" to BlockKind.HEADING_3,
            "h4" to BlockKind.HEADING_3,
            "h5" to BlockKind.HEADING_3,
            "h6" to BlockKind.HEADING_3,
            "p" to BlockKind.PARAGRAPH,
            "pre" to BlockKind.PARAGRAPH,
            "blockquote" to BlockKind.QUOTE,
            "li" to BlockKind.ITEM,
        )

        /** Elements a line break goes around: they end the loose text before them, and start a line in a block. */
        private val BLOCK_LEVEL = setOf(
            "address", "article", "aside", "blockquote", "body", "caption", "center", "dd", "details", "dialog",
            "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3",
            "h4", "h5", "h6", "header", "hgroup", "hr", "html", "li", "main", "menu", "nav", "ol", "p", "pre",
            "section", "summary", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
        )

        /** Elements that never have content (HTML's void elements): never open. */
        private val VOID = setOf(
            "area", "base", "basefont", "bgsound", "br", "col", "embed", "frame", "hr", "img", "input",
            "keygen", "link", "meta", "param", "source", "track", "wbr",
        )

        /** What a `<head>` holds; any other element starts the body. */
        private val HEAD_CONTENT = setOf("head", "title", "meta", "link", "style", "script", "base", "noscript", "template")

        /** An element left open that a starting one closes when it is the last open (HTML's optional end tags). */
        private val IMPLIED_CLOSES = mapOf(
            "li" to setOf("li"),
            "dt" to setOf("dt", "dd"),
            "dd" to setOf("dt", "dd"),
            "tr" to setOf("tr", "td", "th"),
            "td" to setOf("td", "th"),
            "th" to setOf("td", "th"),
            "option" to setOf("option"),
        )
    }
}

/**
 * The text of the blocks as they are made: one block open at a time, white space collapsed into it, then
 * kept when it holds more than white space. Every character kept is spent from [budget], and
 * [BlockReader.BLOCK_CHARGE] per block.
 */
private class BlockText(private val budget: TextBudget) {
    private val text = StringBuilder()
    private var ends = IntArray(INITIAL_BLOCKS)
    private var kinds = ByteArray(INITIAL_BLOCKS)
    private var kind: BlockKind? = null
    private var start = 0
    private var pendingSpace = false

    /** The blocks made so far. */
    var count = 0
        private set

    fun open(kind: BlockKind) {
        this.kind = kind
        start = text.length
        pendingSpace = false
    }

    /** [chars] into the open block — a paragraph of loose text when none is — as they are when [preformatted]. */
    fun append(chars: CharSequence, preformatted: Boolean) {
        if (kind == null) open(BlockKind.PARAGRAPH)
        val before = text.length
        for (c in chars) {
            when {
                // A preformatted block drops its first line break, as HTML does, and carriage returns.
                preformatted -> if (c != '\r' && (c != '\n' || text.length > start)) text.append(c)
                c in SPACES -> if (text.length > start && text[text.length - 1] != '\n') pendingSpace = true
                else -> {
                    if (pendingSpace) text.append(' ')
                    pendingSpace = false
                    text.append(c)
                }
            }
        }
        budget.spend(text.length - before)
    }

    /** A line break in the open block — [hard] for a `<br>`, which may follow another; none at its start. */
    fun lineBreak(hard: Boolean) {
        if (kind == null || text.length == start) return
        pendingSpace = false
        if (hard || text[text.length - 1] != '\n') {
            text.append('\n')
            budget.spend(1)
        }
    }

    /** Ends the open block: kept when it holds more than white space, its trailing lines dropped. */
    fun finish() {
        val finished = kind ?: return
        kind = null
        pendingSpace = false
        var end = text.length
        while (end > start && text[end - 1] in SPACES) end--
        text.setLength(end)
        if (end == start) return
        budget.spend(BlockReader.BLOCK_CHARGE)
        if (count == ends.size) {
            ends = ends.copyOf(count * 2)
            kinds = kinds.copyOf(count * 2)
        }
        ends[count] = end
        kinds[count] = finished.ordinal.toByte()
        count++
    }

    fun build(): ReaderBlocks = ReaderBlocks(text.toString(), ends.copyOf(count), kinds.copyOf(count), 0, count)

    private companion object {
        const val INITIAL_BLOCKS = 64

        /** HTML's white space: what collapses to one space between words. A no-break space is a character. */
        const val SPACES = " \t\n\r\u000C"
    }
}
