package com.readfilestech.read_files_tech.data.sharing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** The one way out for the vault's plaintext (docs D-034): a copy decrypted for that share, nothing else. */
class VaultSharesTest {
    @TempDir
    lateinit var cache: File

    private val root get() = File(cache, "vault_shared")

    private fun copy(path: String) = File(root, path).apply {
        parentFile?.mkdirs()
        writeText("clair")
    }

    @Test
    fun `a copy in its slot is served`() {
        val copy = copy("slot1/relevé.pdf")
        assertThat(VaultShares.resolve(copy, root)).isEqualTo(copy.canonicalFile)
    }

    @Test
    fun `anything else is refused - the viewers' copies, the root itself, a folder, a deeper file, a missing one`() {
        val viewers = File(cache, "vault_decrypt/slot1/relevé.pdf").apply {
            parentFile?.mkdirs()
            writeText("clair")
        }
        val atRoot = copy("relevé.pdf")
        val deeper = copy("slot1/sous/relevé.pdf")
        val folder = File(root, "slot2").apply { mkdirs() }
        val missing = File(root, "slot3/absent.pdf")
        val climbing = File(root, "slot1/../../vault_decrypt/slot1/relevé.pdf")
        for (file in listOf(viewers, atRoot, deeper, folder, missing, climbing)) {
            assertThat(VaultShares.resolve(file, root)).isNull()
        }
    }

    @Test
    fun `a link in a slot is followed, and refused when it leads out`() {
        val target = File(cache, "vault_decrypt/slot1/relevé.pdf").apply {
            parentFile?.mkdirs()
            writeText("clair")
        }
        val link = File(root, "slot1/relevé.pdf")
        link.parentFile?.mkdirs()
        val made = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue(made, "no symbolic link on this file system")
        assertThat(VaultShares.resolve(link, root)).isNull()
    }
}
