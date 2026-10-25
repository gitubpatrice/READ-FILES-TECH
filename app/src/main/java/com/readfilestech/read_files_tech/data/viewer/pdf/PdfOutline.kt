package com.readfilestech.read_files_tech.data.viewer.pdf

import com.readfilestech.read_files_tech.data.files.FileCaps
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One line of a PDF's "Contents" panel: its [title], its level ([depth], 0 at the top) and the 0-based
 * [page] it leads to — null when the entry leads nowhere this viewer can go (a link to another file, a
 * destination that does not resolve), and is then listed but not followed.
 */
data class OutlineEntry(val title: String, val depth: Int, val page: Int?)

/**
 * An outline as [OutlineWalker] reads it: PdfBox's items in the `:pdf` process, plain objects in the tests.
 * Each read may return null for what the file does not hold, or holds broken: a branch then ends there.
 */
interface OutlineTree<N : Any> {
    /** What makes two nodes the same: the dictionary in the file, never the wrapper PdfBox makes per read. */
    fun identity(node: N): Any

    fun firstChild(node: N): N?

    fun next(node: N): N?

    fun title(node: N): String?

    fun page(node: N): Int?
}

/**
 * The entries of an outline, in reading order, BOUNDED whatever the file says (inventory, K1: the 2.15.3
 * had no outline of its own, Syncfusion's panel read whatever it was given). An outline is a tree of
 * linked lists (`/First`, `/Next`) that a hostile file can bend:
 * - into a cycle, between siblings or back to an ancestor: a node is listed once, then its branch ends —
 *   PdfBox's own iterator only stops on a cycle back to the first sibling;
 * - into millions of entries: the first [FileCaps.PDF_OUTLINE_ENTRIES] are listed;
 * - into a nesting thousands deep: levels past [FileCaps.PDF_OUTLINE_DEPTH] are not read, and the walk
 *   keeps its own stack — no recursion to overflow.
 */
object OutlineWalker {
    fun <N : Any> walk(first: N?, tree: OutlineTree<N>): List<OutlineEntry> {
        val entries = ArrayList<OutlineEntry>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        // The next node to read at each level: at most one per level, so the stack stays within the depth cap.
        val pending = ArrayDeque<Pair<N, Int>>()
        first?.let { pending.addLast(it to 0) }
        while (pending.isNotEmpty() && entries.size < FileCaps.PDF_OUTLINE_ENTRIES) {
            val (node, depth) = pending.removeLast()
            if (!seen.add(tree.identity(node))) continue
            entries += OutlineEntry(titleOf(tree.title(node)), depth, tree.page(node))
            tree.next(node)?.let { pending.addLast(it to depth) }
            if (depth + 1 < FileCaps.PDF_OUTLINE_DEPTH) tree.firstChild(node)?.let { pending.addLast(it to depth + 1) }
        }
        return entries
    }

    /** One line of at most [FileCaps.PDF_OUTLINE_TITLE_CHARS] chars: a title's line breaks and controls are spaces. */
    private fun titleOf(raw: String?): String {
        if (raw == null) return ""
        val title = StringBuilder(minOf(raw.length, FileCaps.PDF_OUTLINE_TITLE_CHARS))
        for (char in raw) {
            if (title.length == FileCaps.PDF_OUTLINE_TITLE_CHARS) break
            title.append(if (char.isISOControl()) ' ' else char)
        }
        // Never half a character: a cut between the two halves of an emoji leaves the first one alone.
        if (title.isNotEmpty() && title.last().isHighSurrogate()) title.setLength(title.length - 1)
        return title.trim().toString()
    }
}
