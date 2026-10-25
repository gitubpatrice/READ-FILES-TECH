package com.readfilestech.read_files_tech.data.files

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException

/** The moves and copies shared by the trash and the explorer. */
class FileMovesTest {
    @TempDir
    lateinit var tmp: File

    private fun parts(dir: File) = dir.list()!!.filter { it.startsWith(".rft-part-") }

    @Test
    fun `a copy lands whole under its name and leaves no temporary file`() {
        val src = File(tmp, "a.bin").apply { writeBytes(ByteArray(70_000) { it.toByte() }) }
        val dest = FileMoves.reserveUniquePath(tmp, "copy.bin", isDir = false)
        FileMoves.copyFile(src, dest)
        assertThat(dest.readBytes()).isEqualTo(src.readBytes())
        assertThat(parts(tmp)).isEmpty()
    }

    /** Claude review R4: a copy cut short must never sit under the real name looking whole. */
    @Test
    fun `a failed copy leaves the reservation untouched and no temporary file`() {
        val folder = File(tmp, "not-a-file").apply { mkdirs() }
        val dest = FileMoves.reserveUniquePath(tmp, "copy.bin", isDir = false)
        assertThrows<IOException> { FileMoves.copyFile(folder, dest) }
        assertThat(dest.length()).isEqualTo(0)
        assertThat(parts(tmp)).isEmpty()
    }

    @Test
    fun `free names keep the extension, and a hidden file has none`() {
        File(tmp, "photo.jpg").writeText("x")
        File(tmp, ".bashrc").writeText("x")
        assertThat(FileMoves.reserveUniquePath(tmp, "photo.jpg", isDir = false).name).isEqualTo("photo (1).jpg")
        assertThat(FileMoves.reserveUniquePath(tmp, ".bashrc", isDir = false).name).isEqualTo(".bashrc (1)")
        assertThat(FileMoves.reserveUniquePath(tmp, "photo.jpg", isDir = true).name).isEqualTo("photo (2).jpg")
    }

    /** Claude review R4: any error used to read as "taken", 999 times, then "no free name". */
    @Test
    fun `an error other than a taken name is thrown at once`() {
        assertThrows<NoSuchFileException> { FileMoves.reserveUniquePath(File(tmp, "missing"), "a.txt", isDir = false) }
    }

    @Test
    fun `a move renames into the reservation and the source is gone`() {
        val src = File(tmp, "a.txt").apply { writeText("A") }
        val dest = FileMoves.reserveUniquePath(File(tmp, "out").apply { mkdirs() }, "a.txt", isDir = false)
        FileMoves.move(src, dest, isDir = false)
        assertThat(dest.readText()).isEqualTo("A")
        assertThat(src.exists()).isFalse()
    }
}
