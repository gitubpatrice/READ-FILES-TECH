package com.readfilestech.read_files_tech.data.files

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileCapsTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `a file at its cap is read, one byte more is refused`() {
        val file = File(tmp, "f").apply { writeBytes(ByteArray(10)) }
        FileCaps.check(file, cap = 10)
        val refused = assertThrows<FileTooLargeException> { FileCaps.check(file, cap = 9) }
        assertThat(refused.capBytes).isEqualTo(9)
    }

    @Test
    fun `the caps are the 2_15_3's`() {
        assertThat(FileCaps.TEXT_VIEWER).isEqualTo(50L shl 20)
        assertThat(FileCaps.ZIP_VIEWER).isEqualTo(500L shl 20)
        assertThat(FileCaps.ZIP_EXTRACT_TOTAL).isEqualTo(1L shl 30)
        assertThat(FileCaps.HTML_VIEWER).isEqualTo(20L shl 20)
        assertThat(FileCaps.IMAGE_PIXELS).isEqualTo(64_000_000L)
    }
}
