package com.readfilestech.read_files_tech.data.viewer.pdf

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessRead
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.IOException
import java.util.IdentityHashMap

/**
 * In the `:pdf` process only (docs D-029): the outline of a PDF, read by PdfBox, its entries bounded by
 * [OutlineWalker]. Each read of an item is guarded on its own: a broken entry ends its branch, or is listed
 * without a page, and the others are listed (a name tree whose kids loop overflows the stack of one lookup).
 */
class PdfBoxOutline private constructor(private val document: PDDocument) : OutlineTree<PDOutlineItem> {
    private val pages = PageIndex(document.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES) as? COSDictionary)

    override fun identity(node: PDOutlineItem): Any = node.cosObject

    override fun firstChild(node: PDOutlineItem): PDOutlineItem? = guarded { node.firstChild }

    override fun next(node: PDOutlineItem): PDOutlineItem? = guarded { node.nextSibling }

    override fun title(node: PDOutlineItem): String? = guarded { node.title }

    override fun page(node: PDOutlineItem): Int? = guarded {
        val destination = node.destination ?: (node.action as? PDActionGoTo)?.destination
        val target = when (destination) {
            is PDNamedDestination -> document.documentCatalog.findNamedDestinationPage(destination)
            is PDPageDestination -> destination
            else -> null
        }
        // A page object, as a destination in the file should be; a page number, as a broken one may be.
        target?.page?.let { pages.indexOf(it.cosObject) } ?: target?.pageNumber?.takeIf { it >= 0 }
    }

    // TooGenericExceptionCaught: whatever one entry of a hostile file throws ends that entry, not the outline.
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guarded(read: () -> T?): T? = try {
        read()
    } catch (expected: Exception) {
        null
    } catch (expected: StackOverflowError) {
        null
    }

    companion object {
        /** What PdfBox may hold in memory while it reads an outline; past it, the outline is not read. */
        private const val SCRATCH_BYTES = 32L * 1024 * 1024

        /**
         * The outline of the PDF in [source] (closed), its pages within [pageCount] — the count PdfRenderer
         * found: an entry leading past it leads nowhere. Empty when the file has none.
         *
         * @throws IOException PdfBox could not open the file.
         */
        fun read(source: RandomAccessRead, pageCount: Int): List<OutlineEntry> {
            val parser = PDFParser(source, "", ScratchFile(MemoryUsageSetting.setupMainMemoryOnly(SCRATCH_BYTES)))
            try {
                parser.parse()
            } catch (e: IOException) {
                source.close()
                throw e
            }
            return parser.pdDocument.use { document ->
                val first = document.documentCatalog.documentOutline?.firstChild
                OutlineWalker.walk(first, PdfBoxOutline(document)).map { entry ->
                    if (entry.page != null && entry.page !in 0 until pageCount) entry.copy(page = null) else entry
                }
            }
        }
    }
}

/**
 * The 0-based index of a page in its document, found by climbing its `/Parent` chain and counting, at each
 * level, the pages of the kids before it — never PdfBox's `indexOf`, which walks the whole tree for each
 * entry, recursively, a loop in `/Kids` included. Bounded: [FileCaps.PDF_PAGE_TREE_DEPTH] levels, and
 * [FileCaps.PDF_OUTLINE_PAGE_LOOKUPS] kids read for the whole outline, each node's kids read once.
 * A `/Count` that lies gives a wrong page, never a loop.
 */
internal class PageIndex(private val root: COSDictionary?) {
    private val offsets = IdentityHashMap<COSDictionary, Map<COSDictionary, Long>>()
    private var lookups = 0

    fun indexOf(page: COSDictionary): Int? {
        var node = page
        var index = 0L
        var levels = 0
        while (levels < FileCaps.PDF_PAGE_TREE_DEPTH) {
            val parent = node.getDictionaryObject(COSName.PARENT) as? COSDictionary ?: break
            index += offsets.getOrPut(parent) { kidsOf(parent) }[node] ?: return null
            node = parent
            levels++
        }
        // The top reached within the depth, and it is the document's tree: never a page found by a loop.
        return index.takeIf { node === root && levels < FileCaps.PDF_PAGE_TREE_DEPTH && it <= Int.MAX_VALUE }?.toInt()
    }

    /** The pages before each kid of [node], in its `/Kids`; as many kids as the lookups left allow. */
    private fun kidsOf(node: COSDictionary): Map<COSDictionary, Long> {
        val kids = node.getDictionaryObject(COSName.KIDS) as? COSArray ?: return emptyMap()
        val read = minOf(kids.size(), (FileCaps.PDF_OUTLINE_PAGE_LOOKUPS - lookups).coerceAtLeast(0))
        lookups += read
        val before = IdentityHashMap<COSDictionary, Long>()
        var pages = 0L
        for (i in 0 until read) {
            // A kid that is not a dictionary holds no page.
            val kid = kids.getObject(i) as? COSDictionary ?: continue
            before.putIfAbsent(kid, pages)
            // PdfBox's own rule for a node of the tree: typed /Pages, or holding /Kids.
            val inner = kid.getCOSName(COSName.TYPE) == COSName.PAGES || kid.containsKey(COSName.KIDS)
            pages += if (inner) kid.getLong(COSName.COUNT, 0L).coerceIn(0L, Int.MAX_VALUE.toLong()) else 1L
        }
        return before
    }
}
