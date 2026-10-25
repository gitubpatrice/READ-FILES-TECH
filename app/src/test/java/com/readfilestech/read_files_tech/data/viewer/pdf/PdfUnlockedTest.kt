package com.readfilestech.read_files_tech.data.viewer.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The decrypted copies: one file each, in a folder no FileProvider serves, gone with their viewer. */
class PdfUnlockedTest {
    @TempDir
    lateinit var cache: File

    private val unlocked by lazy { PdfUnlocked.under(cache) }

    @Test
    fun `each decryption has its own empty file, and the last one deleted takes the folder with it`() {
        val first = unlocked.create()
        val second = unlocked.create()

        assertThat(first).isNotEqualTo(second)
        assertThat(first.parentFile).isEqualTo(File(cache, "pdf_unlocked"))
        assertThat(first.length()).isEqualTo(0)

        unlocked.delete(first)
        assertThat(first.exists()).isFalse()
        assertThat(unlocked.folder.isDirectory).isTrue()

        unlocked.delete(second)
        assertThat(unlocked.folder.exists()).isFalse()
    }

    @Test
    fun `the purge at start removes whatever a killed viewer left`() {
        unlocked.create().writeText("%PDF déchiffré")
        File(unlocked.folder, "PDFBox123.tmp").writeText("scratch")

        assertThat(unlocked.purge()).isTrue()
        assertThat(unlocked.folder.exists()).isFalse()
    }
}
