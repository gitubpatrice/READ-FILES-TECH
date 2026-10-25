package com.readfilestech.read_files_tech.data.files

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.random.Random

/**
 * Moves between two volumes on a REAL second file system: the app's internal storage (`/data`) and its
 * external folder (`/storage/emulated/0/Android/data/…`) are two mounts on every device, so a rename
 * between them fails with EXDEV — the path the JVM tests can only reach on one file system (Claude
 * review R5: a folder moved across volumes).
 */
class CrossVolumeMovesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inside = File(context.filesDir, "cross-volume-test")
    private val outside = File(checkNotNull(context.getExternalFilesDir(null)), "cross-volume-test")

    @Before
    fun setUp() {
        tearDown()
        check(inside.mkdirs() && outside.mkdirs())
    }

    @After
    fun tearDown() {
        FileTrees.delete(inside)
        FileTrees.delete(outside)
    }

    /** Tried, not assumed: on one volume, every test below would pass by a plain rename. */
    @Test
    fun theTwoFoldersAreOnTwoVolumes() {
        val probe = File(inside, "probe").apply { writeText("x") }

        assertThrows(AtomicMoveNotSupportedException::class.java) {
            Files.move(probe.toPath(), File(outside, "probe").toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    @Test
    fun aFolderArrivesWholeAndLeavesNothingBehind() {
        val source = File(inside, "folder")
        val files = treeIn(source)
        File(source, "empty").mkdirs()
        val dest = FileMoves.reserveUniquePath(outside, "folder", isDir = true)

        FileMoves.move(source, dest, isDir = true)

        files.forEach { (path, content) -> assertThat(File(dest, path).readBytes()).isEqualTo(content) }
        assertThat(File(dest, "empty").isDirectory).isTrue()
        assertThat(source.exists()).isFalse()
        // No hidden part folder left beside it.
        assertThat(outside.list()!!.toList()).containsExactly("folder")
    }

    @Test
    fun aFileGoesThereAndBack() {
        val content = Random(1).nextBytes(3_000_000)
        val source = File(inside, "file.bin").apply { writeBytes(content) }
        val there = FileMoves.reserveUniquePath(outside, "file.bin", isDir = false)

        FileMoves.move(source, there, isDir = false)
        assertThat(there.readBytes()).isEqualTo(content)
        assertThat(source.exists()).isFalse()
        val back = FileMoves.reserveUniquePath(inside, "file.bin", isDir = false)
        FileMoves.move(there, back, isDir = false)

        assertThat(back.readBytes()).isEqualTo(content)
        assertThat(outside.list()!!.toList()).isEmpty()
        assertThat(inside.list()!!.toList()).containsExactly("file.bin")
    }

    @Test
    fun aCopyKeepsItsSource() {
        val content = Random(2).nextBytes(1_500_000)
        val source = File(inside, "file.bin").apply { writeBytes(content) }
        val copy = FileMoves.reserveUniquePath(outside, "file.bin", isDir = false)

        FileMoves.copyFile(source, copy)

        assertThat(copy.readBytes()).isEqualTo(content)
        assertThat(source.readBytes()).isEqualTo(content)
        assertThat(outside.list()!!.toList()).containsExactly("file.bin")
    }

    /** An entry it cannot copy stops the move: the 2.15.x skipped it, then deleted it with the source. */
    @Test
    fun aFolderHoldingALinkStaysWhereItIs() {
        val source = File(inside, "folder")
        val files = treeIn(source)
        Files.createSymbolicLink(File(source, "link").toPath(), File(source, "a.bin").toPath())
        val dest = FileMoves.reserveUniquePath(outside, "folder", isDir = true)

        assertThrows(IOException::class.java) { FileMoves.move(source, dest, isDir = true) }

        files.forEach { (path, content) -> assertThat(File(source, path).readBytes()).isEqualTo(content) }
        assertThat(Files.isSymbolicLink(File(source, "link").toPath())).isTrue()
        // Only the caller's empty reservation, which it discards: no partial copy.
        assertThat(outside.list()!!.toList()).containsExactly("folder")
        assertThat(dest.list()!!.toList()).isEmpty()
    }

    private fun treeIn(root: File): Map<String, ByteArray> {
        val files = mapOf(
            "a.bin" to Random(3).nextBytes(400_000),
            "sub/b.bin" to Random(4).nextBytes(900_000),
            "sub/deeper/c.txt" to "c".toByteArray(),
        )
        files.forEach { (path, content) -> File(root, path).apply { parentFile!!.mkdirs() }.writeBytes(content) }
        return files
    }
}
