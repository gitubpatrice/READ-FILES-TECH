package com.readfilestech.read_files_tech.data.browse

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.CappedInputStream
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** Names, listing, order, filters and the text preview of the explorer. */
class BrowseTest {
    @TempDir
    lateinit var tmp: File

    private fun entry(name: String, dir: Boolean = false, size: Long = 0, modified: Long = 0) =
        FileEntry("/x/$name", name, dir, size, modified, isSymlink = false)

    @Test
    fun `extension follows files_tech_core`() {
        assertThat(FileNames.extension("doc.PDF")).isEqualTo("pdf")
        assertThat(FileNames.extension("archive.tar.gz")).isEqualTo("gz")
        assertThat(FileNames.extension(".bashrc")).isEqualTo("")
        assertThat(FileNames.extension("foo.")).isEqualTo("")
        assertThat(FileNames.extension("README")).isEqualTo("")
        assertThat(entry("photos.d", dir = true).extension).isEqualTo("")
    }

    @Test
    fun `folders first, then names case-insensitive`() {
        val sorted = EntryOrder.sort(
            listOf(entry("b.txt"), entry("Zeta", dir = true), entry("A.txt"), entry("alpha", dir = true)),
            SortMode.NAME
        )
        assertThat(sorted.map { it.name }).containsExactly("alpha", "Zeta", "A.txt", "b.txt").inOrder()
    }

    @Test
    fun `date and size put the most recent and the largest first, folders still first`() {
        val list =
            listOf(entry("old", size = 9, modified = 1), entry("new", size = 1, modified = 3), entry("dir", dir = true, modified = 0))
        assertThat(EntryOrder.sort(list, SortMode.DATE).map { it.name }).containsExactly("dir", "new", "old").inOrder()
        assertThat(EntryOrder.sort(list, SortMode.SIZE).map { it.name }).containsExactly("dir", "old", "new").inOrder()
    }

    @Test
    fun `filter hides dot files unless asked, keeps folders through an extension filter, matches names case-insensitively`() {
        val list = listOf(entry(".hidden"), entry("Report.PDF"), entry("notes.txt"), entry("Docs", dir = true))
        assertThat(EntryOrder.filter(list, showHidden = false, query = "").map { it.name })
            .containsExactly("Report.PDF", "notes.txt", "Docs").inOrder()
        assertThat(EntryOrder.filter(list, showHidden = true, query = "").map { it.name }).contains(".hidden")
        assertThat(EntryOrder.filter(list, showHidden = false, query = "rep").map { it.name }).containsExactly("Report.PDF")
        assertThat(EntryOrder.filter(list, showHidden = false, query = "", extensions = setOf("pdf")).map { it.name })
            .containsExactly("Report.PDF", "Docs").inOrder()
    }

    @Test
    fun `a listing reads each entry once, links seen without being followed`() {
        val dir = File(tmp, "d").apply { mkdirs() }
        File(dir, "a.txt").writeText("12345")
        File(dir, "sub").mkdirs()
        val listing = DirectoryListing.list(dir) as Listing.Listed
        val byName = listing.entries.associateBy { it.name }
        assertThat(byName.getValue("a.txt").size).isEqualTo(5)
        assertThat(byName.getValue("a.txt").isDirectory).isFalse()
        assertThat(byName.getValue("sub").isDirectory).isTrue()
        assertThat(byName.getValue("sub").size).isEqualTo(0)
        assertThat(byName.values.none { it.isSymlink }).isTrue()
    }

    @Test
    fun `a link to a folder is a folder, flagged as a link`() {
        val target = File(tmp, "target").apply { mkdirs() }
        val dir = File(tmp, "d").apply { mkdirs() }
        val link = File(dir, "link")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue(made, "this system refuses symbolic links (Windows without the privilege)")
        val entry = (DirectoryListing.list(dir) as Listing.Listed).entries.single()
        assertThat(entry.isSymlink).isTrue()
        assertThat(entry.isDirectory).isTrue()
    }

    @Test
    fun `a file is not a folder, and a missing folder is not an empty one`() {
        val f = File(tmp, "f.txt").apply { writeText("x") }
        assertThat(DirectoryListing.list(f)).isEqualTo(Listing.NotADirectory)
        assertThat(DirectoryListing.list(File(tmp, "absent"))).isEqualTo(Listing.NotADirectory)
    }

    @Test
    fun `a capped stream ends quietly at its limit, and a shorter source is no error`() {
        val capped = CappedInputStream(ByteArrayInputStream(ByteArray(100) { 7 }), 10)
        assertThat(capped.readBytes()).hasLength(10)
        assertThat(CappedInputStream(ByteArrayInputStream(ByteArray(3)), 10).readBytes()).hasLength(3)
        val single = CappedInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
        assertThat(listOf(single.read(), single.read(), single.read())).containsExactly(1, 2, -1).inOrder()
    }

    @Test
    fun `the preview stops at 40 lines`() {
        val f = File(tmp, "long.txt").apply { writeText((1..1000).joinToString("\n") { "line $it" }) }
        val lines = TextPreview.firstLines(f)
        assertThat(lines).hasSize(TextPreview.MAX_LINES)
        assertThat(lines.last()).isEqualTo("line 40")
    }

    /** V-H3: a file without any line break would be "one line" — read whole. 512 KiB at most. */
    @Test
    fun `the preview reads at most 512 KiB of a file without line breaks`() {
        val f = File(tmp, "blob.bin").apply { writeBytes(ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() }) }
        val lines = TextPreview.firstLines(f)
        assertThat(lines).hasSize(1)
        assertThat(lines.single().length.toLong()).isEqualTo(TextPreview.MAX_BYTES)
    }

    @Test
    fun `the preview of a small file and of malformed UTF-8`() {
        val f = File(tmp, "latin.txt").apply {
            writeBytes(byteArrayOf('c'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte(), 'z'.code.toByte()))
        }
        assertThat(TextPreview.firstLines(f)).containsExactly("c�", "z").inOrder()
        assertThat(TextPreview.firstLines(File(tmp, "empty.txt").apply { writeText("") })).isEmpty()
    }
}
