package com.readfilestech.read_files_tech.data.browse

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileAlreadyExistsException

/** The explorer's own changes: nothing is ever replaced, a failed move keeps its source. */
class FileActionsTest {
    @TempDir
    lateinit var tmp: File

    private val actions = FileActions()
    private lateinit var src: File
    private lateinit var dest: File

    @BeforeEach
    fun setUp() {
        src = File(tmp, "src").apply { mkdirs() }
        dest = File(tmp, "dest").apply { mkdirs() }
    }

    private fun file(dir: File, name: String, content: String) = File(dir, name).apply { writeText(content) }

    @Test
    fun `a new folder is created, and an existing name is SAID, not silently accepted`() {
        val made = actions.createFolder(src, "Photos")
        assertThat(made.isDirectory).isTrue()
        // Dart's Directory.create() succeeded on an existing folder: "New folder" did nothing, said nothing.
        assertThrows<FileAlreadyExistsException> { actions.createFolder(src, "Photos") }
    }

    @Test
    fun `invalid names are refused before touching the disk`() {
        listOf("", ".", "..", "a/b", "a\\b", "tab\tname", "nul\u0000").forEach { name ->
            assertThrows<InvalidFileNameException> { actions.createFolder(src, name) }
            assertThrows<InvalidFileNameException> { actions.rename(file(src, "x.txt", "x"), name) }
        }
        assertThat(src.list()!!.toList()).containsExactly("x.txt")
    }

    @Test
    fun `rename never replaces another item`() {
        val a = file(src, "a.txt", "A")
        file(src, "b.txt", "B")
        assertThrows<FileAlreadyExistsException> { actions.rename(a, "b.txt") }
        assertThat(File(src, "a.txt").readText()).isEqualTo("A")
        assertThat(File(src, "b.txt").readText()).isEqualTo("B")
    }

    @Test
    fun `a folder is renamed with what it holds, over its reservation`() {
        val album = File(src, "album").apply { mkdirs() }
        File(album, "a.jpg").writeText("A")
        val renamed = actions.rename(album, "photos")
        assertThat(File(renamed, "a.jpg").readText()).isEqualTo("A")
        assertThat(src.list()!!.toList()).containsExactly("photos")
    }

    @Test
    fun `renaming onto an existing folder fails and leaves both as they were`() {
        val a = File(src, "a").apply { mkdirs() }
        File(src, "b").mkdirs()
        File(a, "x.txt").writeText("x")
        assertThrows<FileAlreadyExistsException> { actions.rename(a, "b") }
        assertThat(File(a, "x.txt").readText()).isEqualTo("x")
        assertThat(File(src, "b").list()!!.toList()).isEmpty()
    }

    @Test
    fun `rename moves the item and keeps its content`() {
        val renamed = actions.rename(file(src, "a.txt", "A"), "c.txt")
        assertThat(renamed).isEqualTo(File(src, "c.txt"))
        assertThat(renamed.readText()).isEqualTo("A")
        assertThat(File(src, "a.txt").exists()).isFalse()
    }

    /**
     * Shared storage ignores case: renaming `photo.JPG` to `photo.jpg` found "another" item, the item
     * itself, and the 2.15.x refused it. Runs where the volume ignores case (Windows, macOS); the CI's
     * Linux disk does not, and the test says so instead of passing for nothing.
     */
    @Test
    fun `a change of case only is a rename, not a collision`() {
        val photo = file(src, "photo.JPG", "pixels")
        assumeTrue(File(src, "photo.jpg").exists(), "this volume tells case apart")
        val renamed = actions.rename(photo, "photo.jpg")
        assertThat(src.list()!!.toList()).containsExactly("photo.jpg")
        assertThat(renamed.readText()).isEqualTo("pixels")
    }

    @Test
    fun `a copy onto a namesake gets a free name, and the namesake is untouched`() {
        file(src, "report.pdf", "new")
        file(dest, "report.pdf", "old")
        val result = actions.transfer(listOf(File(src, "report.pdf")), dest, move = false)
        assertThat(result).isEqualTo(BatchResult(done = 1, failed = 0, written = listOf(File(dest, "report (1).pdf"))))
        assertThat(File(dest, "report.pdf").readText()).isEqualTo("old")
        assertThat(File(dest, "report (1).pdf").readText()).isEqualTo("new")
        assertThat(File(src, "report.pdf").readText()).isEqualTo("new")
    }

    /** V-M1 of the 2.15.x: moving onto a namesake used to replace it, then delete the source — both lost. */
    @Test
    fun `a move onto a namesake keeps both files`() {
        file(src, "report.pdf", "new")
        file(dest, "report.pdf", "old")
        actions.transfer(listOf(File(src, "report.pdf")), dest, move = true)
        assertThat(File(dest, "report.pdf").readText()).isEqualTo("old")
        assertThat(File(dest, "report (1).pdf").readText()).isEqualTo("new")
        assertThat(File(src, "report.pdf").exists()).isFalse()
    }

    @Test
    fun `moving a file into its own folder changes nothing`() {
        val f = file(src, "note.txt", "N")
        val result = actions.transfer(listOf(f), src, move = true)
        assertThat(result.done).isEqualTo(1)
        assertThat(src.list()!!.toList()).containsExactly("note.txt")
    }

    @Test
    fun `copying a file into its own folder makes a numbered copy`() {
        val f = file(src, "note.txt", "N")
        actions.transfer(listOf(f), src, move = false)
        assertThat(src.list()!!.toList()).containsExactly("note.txt", "note (1).txt")
        assertThat(File(src, "note (1).txt").readText()).isEqualTo("N")
    }

    @Test
    fun `folders are left out of a copy and counted apart, not as errors`() {
        val folder = File(src, "album").apply { mkdirs() }
        val photo = file(src, "a.jpg", "A")
        val result = actions.transfer(listOf(folder, photo), dest, move = false)
        assertThat(result).isEqualTo(BatchResult(done = 1, failed = 0, skippedFolders = 1, written = listOf(File(dest, "a.jpg"))))
        assertThat(dest.list()!!.toList()).containsExactly("a.jpg")
    }

    @Test
    fun `a source that vanished is a failure, and leaves no reservation behind`() {
        val result = actions.transfer(listOf(File(src, "gone.txt")), dest, move = true)
        assertThat(result).isEqualTo(BatchResult(done = 0, failed = 1))
        assertThat(dest.list()!!.toList()).isEmpty()
    }

    @Test
    fun `a destination that cannot be written to fails without touching the source`() {
        val f = file(src, "a.txt", "A")
        val result = actions.transfer(listOf(f), File(tmp, "no-such-folder"), move = true)
        assertThat(result.failed).isEqualTo(1)
        assertThat(f.readText()).isEqualTo("A")
    }

    @Test
    fun `delete forever removes a folder with its content`() {
        val folder = File(src, "album").apply { mkdirs() }
        file(folder, "a.jpg", "A")
        val result = actions.deleteAll(listOf(folder, File(src, "absent")))
        assertThat(folder.exists()).isFalse()
        // An absent item is gone already: deleted, not failed.
        assertThat(result).isEqualTo(BatchResult(done = 2, failed = 0))
    }
}
