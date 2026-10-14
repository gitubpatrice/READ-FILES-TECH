package com.readfilestech.read_files_tech.data.viewer.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random

/** PdfBox's reads of the `:pdf` process's descriptor: what a file holds, at every position, by any read. */
class ChannelRandomAccessTest {
    @TempDir
    lateinit var tmp: File

    /** Three pages of reads and a bit: every boundary is crossed. */
    private val bytes = Random(7).nextBytes(3 * 4096 + 123)

    private fun open() = ChannelRandomAccess(RandomAccessFile(File(tmp, "f.bin").apply { writeBytes(bytes) }, "r").channel)

    @Test
    fun `byte by byte, then in pieces across the pages, the file's own bytes`() {
        open().use { input ->
            val first = ByteArray(5000) { input.read().toByte() }
            val rest = ByteArray(bytes.size - 5000)
            assertThat(input.read(rest)).isEqualTo(rest.size)

            assertThat(first + rest).isEqualTo(bytes)
            assertThat(input.read()).isEqualTo(-1)
            assertThat(input.read(ByteArray(4))).isEqualTo(-1)
            assertThat(input.isEOF).isTrue()
        }
    }

    @Test
    fun `seek, peek and rewind move as PdfBox expects`() {
        open().use { input ->
            input.seek(8190)
            assertThat(input.peek()).isEqualTo(bytes[8190].toInt() and 0xFF)
            assertThat(input.position).isEqualTo(8190)
            assertThat(input.readFully(10)).isEqualTo(bytes.copyOfRange(8190, 8200))
            input.rewind(4)
            assertThat(input.position).isEqualTo(8196)
            assertThat(input.read()).isEqualTo(bytes[8196].toInt() and 0xFF)
            assertThat(input.available()).isEqualTo(bytes.size - 8197)
            assertThat(input.length()).isEqualTo(bytes.size.toLong())
        }
    }

    @Test
    fun `reading past the end is an end, readFully past it an EOFException, a closed file an IOException`() {
        val input = open()
        input.seek(bytes.size - 3L)
        assertThrows<EOFException> { input.readFully(4) }
        input.seek(bytes.size + 100L)
        assertThat(input.read()).isEqualTo(-1)
        assertThrows<IOException> { input.seek(-1) }

        input.close()
        assertThat(input.isClosed).isTrue()
        assertThrows<IOException> { input.read() }
    }
}
