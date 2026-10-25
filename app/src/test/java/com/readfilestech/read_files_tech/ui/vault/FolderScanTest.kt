package com.readfilestech.read_files_tech.ui.vault

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** What the vault's folder import offers to encrypt — and, with "Delete the originals", to delete. */
class FolderScanTest {
    @TempDir
    lateinit var tmp: File

    private fun file(path: String, text: String = "x") = File(tmp, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun scan(folder: File = tmp, recursive: Boolean = true) = runBlocking { FolderScan.list(folder, recursive) }

    private fun FolderScan.Listing.relatives() = entries.map { it.relative.replace('\\', '/') }

    @Test
    fun `the files of the folder and its subfolders, sorted, with their sizes`() {
        file("b.txt", "bb")
        file("a/z.txt", "zzz")
        file(".hidden.txt")
        val listing = scan()
        assertThat(listing.relatives()).containsExactly(".hidden.txt", "a/z.txt", "b.txt").inOrder()
        assertThat(listing.entries.map { it.size }).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(listing.entries.map { File(it.path) })
            .containsExactly(File(tmp, ".hidden.txt"), File(tmp, "a/z.txt"), File(tmp, "b.txt"))
        assertThat(listing.entries.all { it.selected }).isTrue()
        assertThat(listing.capReached).isFalse()
    }

    @Test
    fun `without its subfolders, the folder's own files only`() {
        file("top.txt")
        file("sub/below.txt")
        assertThat(scan(recursive = false).relatives()).containsExactly("top.txt")
    }

    /** The app's trash under its `.next` name too: its items are the app's copies, not the user's files. */
    @Test
    fun `the apps' data, the system's folders and the app's trash are left out`() {
        file("keep/a.txt")
        file("Android/data/com.app/a.txt")
        file("Android/obb/com.app/a.obb")
        file("Android/media/a.txt")
        file("DCIM/.thumbnails/1.jpg")
        file(".cache/a.tmp")
        file(".trash/a.txt")
        file(".RFT_Corbeille/items/1/a.txt")
        file(".RFT_Corbeille.next/items/1/a.txt")
        assertThat(scan().relatives()).containsExactly("Android/media/a.txt", "keep/a.txt")
    }

    @Test
    fun `a folder left out, chosen as the folder, offers nothing`() {
        file(".RFT_Corbeille.next/items/1/a.txt")
        file("Android/data/com.app/a.txt")
        assertThat(scan(File(tmp, ".RFT_Corbeille.next")).entries).isEmpty()
        assertThat(scan(File(tmp, "Android/data/com.app")).entries).isEmpty()
    }

    /** A link to a folder outside would offer its files — and "Delete the originals" would delete them. */
    @Test
    fun `a link is not followed`() {
        file("real/a.txt")
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "b.txt").writeText("x")
        val folder = File(tmp, "chosen").apply { mkdirs() }
        val made = runCatching {
            Files.createSymbolicLink(File(folder, "to-outside").toPath(), outside.toPath())
            Files.createSymbolicLink(File(folder, "link.txt").toPath(), File(tmp, "real/a.txt").toPath())
        }.isSuccess
        assumeTrue(made, "this system refuses symbolic links (Windows without the privilege)")
        File(folder, "own.txt").writeText("x")
        assertThat(scan(folder).relatives()).containsExactly("own.txt")
    }

    /** The 2.15.3 said "limit reached" at exactly 5 000 files, when none was left out. */
    @Test
    fun `the limit is said only when a file was left out`() {
        val folder = File(tmp, "many").apply { mkdirs() }
        repeat(FolderScan.MAX_ENTRIES) { File(folder, "f%05d.txt".format(it)).writeText("") }
        val exactly = scan(folder)
        assertThat(exactly.entries).hasSize(FolderScan.MAX_ENTRIES)
        assertThat(exactly.capReached).isFalse()

        File(folder, "one-more.txt").writeText("")
        val beyond = scan(folder)
        assertThat(beyond.entries).hasSize(FolderScan.MAX_ENTRIES)
        assertThat(beyond.capReached).isTrue()
    }

    @Test
    fun `a folder that is not one cannot be read`() {
        assertThrows<IOException> { scan(file("plain.txt")) }
        assertThrows<IOException> { scan(File(tmp, "missing")) }
    }
}
