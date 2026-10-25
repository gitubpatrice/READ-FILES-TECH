package com.readfilestech.read_files_tech.data.viewer.markdown

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Block
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * A Markdown text as the items of the rendered view (`MdViewerScreen` of the 2.15.3, gpt_markdown): CommonMark
 * and GFM's tables and strikethrough, parsed by commonmark-java with its nesting bounded
 * ([FileCaps.MARKDOWN_NESTING]), then walked WITHOUT recursion. Nothing is fetched: an image is shown only
 * from the document's own folder ([MarkdownImages]), any other is its place and its description; a link is
 * a destination the screen opens only when tapped, if its scheme allows.
 * Raw HTML is shown as written, never interpreted.
 */
// TooManyFunctions: a visitor without recursion — what entering and leaving each kind of node does.
@Suppress("TooManyFunctions")
internal class MarkdownRenderer private constructor(private val images: MarkdownImages) {
    private val blocks = ArrayList<MarkdownBlock>()
    private val shownImages = ArrayList<MarkdownImage>()
    private var quotes = 0
    private val lists = ArrayList<ListState>()
    private var marker: String? = null

    private var text: StringBuilder? = null
    private val runs = ArrayList<MarkdownRun>()
    private var heading = 0
    private var continued = false
    private var row: ArrayList<MarkdownCell>? = null
    private var cellAlignment: MarkdownAlignment? = null
    private var table = 0
    private var header = false

    private var bold = 0
    private var italic = 0
    private var strike = 0
    private var image = 0
    private val links = ArrayList<String>()

    /** A list open around the walk: the number of its next item, or [BULLET]. */
    private class ListState(var number: Int, val delimiter: String)

    /** What entering [node] does; false when its children are not to be walked, nor its exit. */
    private fun enter(node: Node): Boolean {
        when (node) {
            is Block -> enterBlock(node)
            is TableRow -> {
                row = ArrayList()
                header = node.parent is TableHead
            }
            is TableCell -> startCell(node.alignment)
            is Image -> return enterImage(node)
            else -> enterInline(node)
        }
        return true
    }

    /**
     * An image of the document's folder is shown after the text holding it, its description its own; any
     * other keeps its place in the text, an icon and its description. In a table cell, always the icon.
     */
    private fun enterImage(node: Image): Boolean {
        val file = if (row == null && text != null) images.resolve(node.destination) else null
        if (file != null) {
            shownImages += MarkdownImage(file, altText(node), links.lastOrNull(), quotes, lists.size)
            return false
        }
        append(MarkdownStyle.IMAGE_MARK.toString(), MarkdownStyle.IMAGE)
        image++
        return true
    }

    /** The text of [image]'s description, without recursion. */
    private fun altText(image: Image): String {
        val alt = StringBuilder()
        var node: Node? = image.firstChild
        while (node != null && alt.length < PIECE) {
            when (node) {
                is Text -> alt.append(node.literal)
                is Code -> alt.append(node.literal)
            }
            node = node.firstChild ?: after(node, image)
        }
        return alt.take(PIECE).toString()
    }

    /** The node after [node] and its children, inside [root]; null past it. */
    private fun after(node: Node, root: Node): Node? {
        var current = node
        while (current.next == null) {
            current = current.parent
            if (current === root) return null
        }
        return current.next
    }

    private fun enterBlock(node: Block) {
        when (node) {
            is Paragraph -> startText(0)
            is Heading -> startText(node.level)
            is BlockQuote -> quotes++
            is BulletList -> lists += ListState(BULLET, "")
            is OrderedList -> lists += ListState(node.markerStartNumber ?: 1, node.markerDelimiter ?: ".")
            is ListItem -> marker = nextMarker()
            is FencedCodeBlock -> code(node.literal)
            is IndentedCodeBlock -> code(node.literal)
            is HtmlBlock -> code(node.literal)
            is ThematicBreak -> add(MarkdownRule(quotes, lists.size))
            is TableBlock -> table++
        }
    }

    private fun enterInline(node: Node) {
        when (node) {
            is Text -> append(node.literal, 0)
            is Code -> append(node.literal, MarkdownStyle.CODE)
            is HtmlInline -> append(node.literal, MarkdownStyle.CODE)
            is SoftLineBreak -> append(" ", 0)
            is HardLineBreak -> append("\n", 0)
            is Emphasis -> italic++
            is StrongEmphasis -> bold++
            is Strikethrough -> strike++
            is Link -> links += node.destination
        }
    }

    private fun exit(node: Node) {
        when (node) {
            is Paragraph, is Heading -> endText()
            is BlockQuote -> quotes--
            is BulletList, is OrderedList -> lists.removeAt(lists.lastIndex)
            // An item with nothing in it still shows its marker.
            is ListItem -> flushMarker()
            is TableCell -> endCell()
            is TableRow -> row?.let { add(MarkdownTableRow(table, it, header, quotes, lists.size)) }.also { row = null }
            is Emphasis -> italic--
            is StrongEmphasis -> bold--
            is Strikethrough -> strike--
            is Link -> links.removeAt(links.lastIndex)
            is Image -> image--
        }
    }

    private fun nextMarker(): String {
        val list = lists.lastOrNull()
        if (list == null || list.number == BULLET) return BULLET_MARK
        val marker = "${list.number}${list.delimiter}"
        list.number++
        return marker
    }

    private fun startText(level: Int) {
        text = StringBuilder()
        runs.clear()
        heading = level
        continued = false
    }

    private fun endText() {
        flushText()
        text = null
    }

    private fun flushText() {
        val built = text ?: return
        // A paragraph that held only images shown is these images.
        if (built.isNotBlank() || shownImages.isEmpty() || marker != null) {
            blocks += MarkdownText(built.toString(), runs.toList(), heading, marker, continued, quotes, lists.size)
        }
        blocks += shownImages
        shownImages.clear()
        marker = null
        built.setLength(0)
        runs.clear()
        continued = true
    }

    private fun startCell(alignment: TableCell.Alignment?) {
        text = StringBuilder()
        runs.clear()
        cellAlignment = alignment?.let { MarkdownAlignment.valueOf(it.name) }
    }

    private fun endCell() {
        val built = text ?: return
        row?.add(MarkdownCell(built.toString(), runs.toList(), cellAlignment))
        text = null
        runs.clear()
    }

    /** [literal] in the current style, cut into pieces of a paragraph — or past the end of a cell. */
    private fun append(literal: String, extra: Int) {
        val target = text ?: return
        // An image's place is drawn as an icon whatever is around it: its style is IMAGE alone.
        val style = if (extra == MarkdownStyle.IMAGE) extra else extra or currentStyle()
        val link = links.lastOrNull()
        var from = 0
        while (from < literal.length) {
            var to = minOf(literal.length, from + PIECE - target.length)
            // Never between the two halves of a surrogate pair: the pair goes to the next piece.
            if (to > from && to < literal.length && Character.isHighSurrogate(literal[to - 1])) to--
            if (to > from) run(target, literal.substring(from, to), style, link)
            if (to < literal.length) {
                // A cell keeps its first piece; a paragraph goes on in the next item.
                if (row != null) return
                flushText()
            }
            from = to
        }
    }

    /** The styles the nodes around the walk give: an image's description is in italics. */
    private fun currentStyle(): Int {
        var style = 0
        if (bold > 0) style = style or MarkdownStyle.BOLD
        if (italic > 0 || image > 0) style = style or MarkdownStyle.ITALIC
        if (strike > 0) style = style or MarkdownStyle.STRIKE
        return style
    }

    private fun run(target: StringBuilder, piece: String, style: Int, link: String?) {
        val start = target.length
        target.append(piece)
        val last = runs.lastOrNull()
        // An image's place stays a run of its own: the screen draws one icon per run.
        val same = last != null && last.end == start && last.style == style && last.link == link
        if (same && style != MarkdownStyle.IMAGE) {
            runs[runs.lastIndex] = checkNotNull(last).copy(end = target.length)
        } else if (style != 0 || link != null) {
            runs += MarkdownRun(start, target.length, style, link)
        }
    }

    /** A code block's lines, in pieces of at most [PIECE] characters; a longer line is cut. */
    private fun code(literal: String) {
        flushMarker()
        val lines = literal.removeSuffix("\n")
        var from = 0
        var first = true
        while (from < lines.length || first) {
            var to = minOf(lines.length, from + PIECE)
            val lastBreak = lines.lastIndexOf('\n', to - 1)
            if (to < lines.length && lastBreak >= from) to = lastBreak + 1
            if (to < lines.length && to > from && Character.isHighSurrogate(lines[to - 1])) to--
            blocks += MarkdownCode(lines.substring(from, to).removeSuffix("\n"), !first, quotes, lists.size)
            first = false
            if (to == from) break
            from = to
        }
    }

    private fun add(block: MarkdownBlock) {
        flushMarker()
        blocks += block
    }

    private fun flushMarker() {
        marker?.let { blocks += MarkdownText("", emptyList(), marker = it, quotes = quotes, lists = lists.size) }
        marker = null
    }

    /** Walks [root] in document order without recursion: a node, its children, then its exit. */
    private suspend fun walk(root: Node) {
        var node = root.firstChild ?: return
        var visited = 0
        while (true) {
            if (++visited % CANCEL_CHECK_NODES == 0) currentCoroutineContext().ensureActive()
            val entered = enter(node)
            val child = node.firstChild
            if (entered && child != null) {
                node = child
                continue
            }
            if (entered) exit(node)
            while (node.next == null) {
                node = node.parent
                if (node === root) return
                exit(node)
            }
            node = node.next
        }
    }

    companion object {
        /** The longest text one item lays out: the text viewer's row. */
        const val PIECE = TextDocument.ROW_BYTES

        private const val BULLET = -1
        private const val BULLET_MARK = "•"
        private const val CANCEL_CHECK_NODES = 4096

        private val parser: Parser = Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .maxOpenBlockParsers(FileCaps.MARKDOWN_NESTING)
            .maxInlineNesting(FileCaps.MARKDOWN_NESTING)
            .build()

        /** The items of [markdown]; its images of the document's folder as [images] resolves them. */
        suspend fun render(markdown: String, images: MarkdownImages = MarkdownImages(null)): List<MarkdownBlock> {
            val document = parser.parse(markdown)
            return MarkdownRenderer(images).apply { walk(document) }.blocks
        }
    }
}
