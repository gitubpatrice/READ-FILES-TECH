package com.readfilestech.read_files_tech.data.viewer.pdf

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

/** The outline's walk, bounded whatever a hostile file links its entries into (K1, FileCaps). */
class OutlineWalkerTest {
    /** A node of a test outline; [next] and [first] are read lazily, as PdfBox reads them from the file. */
    private class Node(val title: String, val page: Int? = null) {
        var next: Node? = null
        var first: Node? = null
    }

    private object Tree : OutlineTree<Node> {
        override fun identity(node: Node): Any = node

        override fun firstChild(node: Node) = node.first

        override fun next(node: Node) = node.next

        override fun title(node: Node) = node.title

        override fun page(node: Node) = node.page
    }

    /** An endless outline, built as it is read: a sibling after each entry, forever. */
    private object Endless : OutlineTree<Int> {
        override fun identity(node: Int): Any = node

        override fun firstChild(node: Int): Int? = null

        override fun next(node: Int) = node + 1

        override fun title(node: Int) = "entry $node"

        override fun page(node: Int) = null
    }

    /** An outline nested forever: a child under each entry. */
    private object Deep : OutlineTree<Int> {
        override fun identity(node: Int): Any = node

        override fun firstChild(node: Int) = node + 1

        override fun next(node: Int): Int? = null

        override fun title(node: Int) = "level $node"

        override fun page(node: Int) = node
    }

    @TempDir
    lateinit var tmp: File

    @Test
    fun `entries are listed in reading order, children before the next sibling`() {
        val a = Node("A", 0)
        val a1 = Node("A.1", 1)
        val a2 = Node("A.2", 2)
        val b = Node("B")
        a.first = a1
        a1.next = a2
        a.next = b

        assertThat(OutlineWalker.walk(a, Tree)).containsExactly(
            OutlineEntry("A", 0, 0),
            OutlineEntry("A.1", 1, 1),
            OutlineEntry("A.2", 1, 2),
            OutlineEntry("B", 0, null),
        ).inOrder()
    }

    @Test
    fun `a sibling loop and a child pointing back to its ancestor are each listed once`() {
        val a = Node("A")
        val b = Node("B")
        val c = Node("C")
        a.next = b
        b.next = c
        // PdfBox's iterator stops on a loop back to the FIRST sibling only: this one goes back to B.
        c.next = b
        a.first = Node("A.1").also { it.first = a }

        assertThat(OutlineWalker.walk(a, Tree).map { it.title }).containsExactly("A", "A.1", "B", "C").inOrder()
    }

    @Test
    fun `an endless outline lists its first entries only`() {
        val entries = OutlineWalker.walk(0, Endless)

        assertThat(entries).hasSize(FileCaps.PDF_OUTLINE_ENTRIES)
        assertThat(entries.last().title).isEqualTo("entry ${FileCaps.PDF_OUTLINE_ENTRIES - 1}")
    }

    @Test
    fun `an outline nested forever stops at the depth cap, without a recursion to overflow`() {
        val entries = OutlineWalker.walk(0, Deep)

        assertThat(entries).hasSize(FileCaps.PDF_OUTLINE_DEPTH)
        assertThat(
            entries.last()
        ).isEqualTo(OutlineEntry("level ${FileCaps.PDF_OUTLINE_DEPTH - 1}", FileCaps.PDF_OUTLINE_DEPTH - 1, FileCaps.PDF_OUTLINE_DEPTH - 1))
    }

    @Test
    fun `a title is one line, its controls spaces, cut at the cap without half a character`() {
        val long = "x".repeat(FileCaps.PDF_OUTLINE_TITLE_CHARS - 1) + "😀 et la suite"
        val entries = OutlineWalker.walk(Node("  Chapitre\n1\tfin  ").also { it.next = Node(long) }, Tree)

        assertThat(entries[0].title).isEqualTo("Chapitre 1 fin")
        assertThat(entries[1].title).isEqualTo("x".repeat(FileCaps.PDF_OUTLINE_TITLE_CHARS - 1))
    }

    /** In a thread of its own: a climb that never ends fails the test instead of hanging the suite. */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `PdfBox reads a hostile outline - loops of entries, a page outside the tree, a page tree in a loop`() {
        val file = File(tmp, "hostile.pdf").apply { writeBytes(TestPdfs.hostileOutline()) }

        val entries = PdfBoxOutline.read(ChannelRandomAccess(RandomAccessFile(file, "r").channel), pageCount = 2)

        assertThat(entries).containsExactly(
            OutlineEntry("A", 0, 0),
            OutlineEntry("A.1", 1, 1),
            // Its page climbs to a top that is not the document's: no page.
            OutlineEntry("B", 0, null),
            // Its page's /Parent chain loops: no page, never a hang.
            OutlineEntry("C", 0, null),
        ).inOrder()
    }
}
