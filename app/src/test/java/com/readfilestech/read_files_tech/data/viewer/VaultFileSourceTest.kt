package com.readfilestech.read_files_tech.data.viewer

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.vault.TestVault
import com.readfilestech.read_files_tech.vault.VaultFiles
import com.readfilestech.read_files_tech.vault.VaultLockedException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A vault file read in the app's viewers (docs D-028): through a private copy, gone when the viewer is done. */
class VaultFileSourceTest {
    @TempDir
    lateinit var root: File

    private val content = "Relevé de compte, été 2026".toByteArray()

    private fun vaultWith(name: String): Pair<TestVault, File> {
        val vault = TestVault(root)
        val stored = runBlocking {
            vault.service.setup("correct horse")
            vault.files.import(name, content.size.toLong(), { content.inputStream() })
        }
        return vault to stored
    }

    private fun decryptFolder(vault: TestVault) = File(vault.dirs.cache, VaultFiles.DECRYPT_DIR)

    private fun copiesLeft(vault: TestVault): List<String> =
        decryptFolder(vault).walkTopDown().filter(File::isFile).map(File::getName).toList()

    @Test
    fun `it reads the plaintext under its vault name, and never offers a file to share`() {
        val (vault, stored) = vaultWith("relevé.pdf")
        val source = VaultFileSource(vault.files, vault.cipher, stored)
        assertThat(source.name).isEqualTo("relevé.pdf")
        assertThat(source.extension).isEqualTo("pdf")
        assertThat(source.shareable).isNull()
        assertThat(source.length()).isEqualTo(content.size.toLong())
        assertThat(source.open(Long.MAX_VALUE).use { it.readBytes() }).isEqualTo(content)
    }

    @Test
    fun `the copy read is deleted when its stream closes, and the handle's when it closes`() {
        val (vault, stored) = vaultWith("notes.txt")
        val source = VaultFileSource(vault.files, vault.cipher, stored)
        val stream = source.open(Long.MAX_VALUE)
        assertThat(copiesLeft(vault)).containsExactly("notes.txt")
        stream.close()
        assertThat(copiesLeft(vault)).isEmpty()
        val handle = source.acquireFile(Long.MAX_VALUE)
        assertThat(handle.file.readBytes()).isEqualTo(content)
        handle.close()
        assertThat(copiesLeft(vault)).isEmpty()
        assertThat(decryptFolder(vault).list().orEmpty()).isEmpty()
    }

    @Test
    fun `beyond the cap nothing is decrypted at all`() {
        val (vault, stored) = vaultWith("big.txt")
        val source = VaultFileSource(vault.files, vault.cipher, stored)
        assertThrows<FileTooLargeException> { source.open(content.size - 1L) }
        assertThrows<FileTooLargeException> { source.acquireFile(content.size - 1L) }
        assertThat(decryptFolder(vault).exists() && decryptFolder(vault).list().orEmpty().isNotEmpty()).isFalse()
    }

    @Test
    fun `a locked vault opens nothing`() {
        val (vault, stored) = vaultWith("secret.txt")
        vault.service.lock()
        assertThrows<VaultLockedException> { VaultFileSource(vault.files, vault.cipher, stored).open(Long.MAX_VALUE) }
        assertThat(copiesLeft(vault)).isEmpty()
    }
}
