package com.readfilestech.read_files_tech.data.files

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileBytesTest {
    @TempDir
    lateinit var dir: File

    private fun file(bytes: ByteArray) = File(dir, "f").apply { writeBytes(bytes) }

    @Test
    fun `a file holds its own bytes, and no others`() {
        val bytes = "abc".toByteArray()
        assertThat(FileBytes.holds(file(bytes), bytes)).isTrue()
        assertThat(FileBytes.holds(file(bytes), "abd".toByteArray())).isFalse()
        assertThat(FileBytes.holds(file(bytes), "ab".toByteArray())).isFalse()
        assertThat(FileBytes.holds(file(bytes), "abcd".toByteArray())).isFalse()
        assertThat(FileBytes.holds(file(ByteArray(0)), ByteArray(0))).isTrue()
    }

    @Test
    fun `a difference past the first chunk is seen`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertThat(FileBytes.holds(file(bytes), bytes.copyOf())).isTrue()
        val other = bytes.copyOf().also { it[it.size - 1] = 7 }
        assertThat(FileBytes.holds(file(bytes), other)).isFalse()
    }

    @Test
    fun `a missing file or a folder holds nothing`() {
        assertThat(FileBytes.holds(File(dir, "missing"), ByteArray(0))).isFalse()
        assertThat(FileBytes.holds(dir, ByteArray(0))).isFalse()
    }
}
