package com.readfilestech.read_files_tech.data.search

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The one walk of the searches and the duplicate finder: what it enters, what it leaves out, where it goes on. */
class FolderWalkTest {
    @TempDir
    lateinit var tmp: File

    private fun file(path: String) = File(tmp, path).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    private fun names(walk: FolderWalk): List<String> = runBlocking {
        val seen = mutableListOf<String>()
        walk.forEachFile { path, _ ->
            seen += tmp.toPath().relativize(path).toString().replace('\\', '/')
            true
        }
        seen.sorted()
    }

    /** The 2.15.3's content search and duplicate finder ended at the first folder they could not read (D9). */
    @Test
    fun `a folder that cannot be listed is counted and the walk goes on`() {
        file("a/one.txt")
        file("gone/two.txt")
        file("z/three.txt")
        // Removed between its parent's listing and its own: listing it fails.
        val walk = FolderWalk(tmp, skipHidden = false) { dir ->
            // The root's folders are asked too (a root left out is not walked): the drive's own has no name.
            if (dir.fileName?.toString() == "gone") dir.toFile().deleteRecursively()
            false
        }
        assertThat(names(walk)).containsExactly("a/one.txt", "z/three.txt")
        assertThat(walk.unreadableFolders).isEqualTo(1)
    }

    @Test
    fun `hidden entries are skipped when asked, walked otherwise`() {
        file("visible.txt")
        file(".hidden.txt")
        file(".folder/inside.txt")
        assertThat(names(FolderWalk(tmp, skipHidden = true))).containsExactly("visible.txt")
        assertThat(names(FolderWalk(tmp, skipHidden = false))).containsExactly("visible.txt", ".hidden.txt", ".folder/inside.txt")
    }

    @Test
    fun `a folder left out is not entered`() {
        file("keep/a.txt")
        file(".RFT_Corbeille/items/1/a.txt")
        file(".RFT_Corbeille.next/items/1/a.txt")
        file("Android/data/com.app/a.txt")
        file("Android/media/a.txt")
        val walk = FolderWalk(tmp, skipHidden = false) { FolderWalk.isTrash(it) || FolderWalk.isAppData(it) }
        assertThat(names(walk)).containsExactly("keep/a.txt", "Android/media/a.txt")
    }

    @Test
    fun `not recursive, the root's own files only`() {
        file("a.txt")
        file("sub/b.txt")
        file("sub/deeper/c.txt")
        assertThat(names(FolderWalk(tmp, skipHidden = false, recursive = false))).containsExactly("a.txt")
        assertThat(names(FolderWalk(File(tmp, "sub"), skipHidden = false, recursive = false))).containsExactly("sub/b.txt")
    }

    /** GPT review R24: the rule held below the root only — the trash chosen as the folder was walked. */
    @Test
    fun `a root left out, or inside a folder left out, is not walked`() {
        file(".RFT_Corbeille/items/1/a.txt")
        file("Android/data/com.app/a.txt")
        file("keep/a.txt")
        val leftOut = { dir: File ->
            FolderWalk(dir, skipHidden = false) { FolderWalk.isTrash(it) || FolderWalk.isAppData(it) }
        }
        assertThat(names(leftOut(File(tmp, ".RFT_Corbeille")))).isEmpty()
        assertThat(names(leftOut(File(tmp, ".RFT_Corbeille/items")))).isEmpty()
        assertThat(names(leftOut(File(tmp, "Android/data")))).isEmpty()
        assertThat(names(leftOut(File(tmp, "keep")))).containsExactly("keep/a.txt")
    }

    @Test
    fun `the trash and the apps' folders are recognised by their names`() {
        assertThat(FolderWalk.isTrash(Paths.get("/storage/emulated/0/.RFT_Corbeille"))).isTrue()
        assertThat(FolderWalk.isTrash(Paths.get("/storage/emulated/0/.RFT_Corbeille.next"))).isTrue()
        assertThat(FolderWalk.isTrash(Paths.get("/storage/emulated/0/RFT_Corbeille"))).isFalse()
        assertThat(FolderWalk.isAppData(Paths.get("/storage/emulated/0/Android/obb"))).isTrue()
        assertThat(FolderWalk.isAppData(Paths.get("/storage/emulated/0/data"))).isFalse()
    }

    @Test
    fun `a link is neither entered nor read`() {
        file("real/a.txt")
        val made = runCatching {
            Files.createSymbolicLink(File(tmp, "link-dir").toPath(), File(tmp, "real").toPath())
            Files.createSymbolicLink(File(tmp, "link.txt").toPath(), File(tmp, "real/a.txt").toPath())
        }.isSuccess
        assumeTrue(made, "this system refuses symbolic links (Windows without the privilege)")
        assertThat(names(FolderWalk(tmp, skipHidden = false))).containsExactly("real/a.txt")
    }

    @Test
    fun `the visit can stop the walk`() {
        (1..5).forEach { file("f$it.txt") }
        val seen = mutableListOf<Path>()
        runBlocking {
            FolderWalk(tmp, skipHidden = false).forEachFile { path, _ ->
                seen.add(path)
                seen.size < 2
            }
        }
        assertThat(seen).hasSize(2)
    }
}
