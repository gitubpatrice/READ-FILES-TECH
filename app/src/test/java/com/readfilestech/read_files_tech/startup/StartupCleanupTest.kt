package com.readfilestech.read_files_tech.startup

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.vault.TestVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Port plan, phase 2, finding 5: the plain copies the Flutter app left in its cache. */
class StartupCleanupTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var vault: TestVault

    @BeforeEach
    fun setUp() {
        vault = TestVault(tmp)
    }

    private fun cleanup() = StartupCleanup(vault.prefs, vault.service, vault.backups, vault.dirs, Dispatchers.Unconfined)

    private fun cacheFile(path: String) = File(vault.dirs.cache, path).apply {
        parentFile.mkdirs()
        writeText("plain")
    }

    @Test
    fun `the first start empties the whole cache the Flutter app left, ZIP entries under any name included`() = runBlocking {
        val left = listOf(
            "vault_decrypt/a1b2c3/Relevé été.txt",
            "share_plus/Relevé été.txt",
            "file_picker/1791720000000/photo.jpg",
            "image_picker842424.jpg",
            "photo_no_exif.jpg",
            "contrat_signe.pdf",
            "Facture été.pdf", // a shared ZIP entry: its own name, no pattern would find it
        ).map(::cacheFile)
        val vaultFile = File(vault.dirs.vault, "x.enc").apply {
            parentFile.mkdirs()
            writeText("encrypted")
        }

        cleanup().apply { start() }.await()

        left.forEach { assertThat(it.exists()).isFalse() }
        assertThat(vault.dirs.cache.list()).isEmpty()
        assertThat(vaultFile.exists()).isTrue()
        assertThat(vault.stored(StartupCleanup.FLUTTER_CACHE_PURGED)).isEqualTo(true)
    }

    @Test
    fun `later starts keep the port's own cache, and still purge the decrypted copies and restore staging`() = runBlocking {
        cleanup().apply { start() }.await()
        val own = cacheFile("port-thumbnail.png")
        val decrypted = cacheFile("vault_decrypt/d4e5f6/secret.txt")
        val shared = cacheFile("share_plus/secret.txt")
        val staging = File(vault.dirs.vault, ".restore-0a1b").apply { mkdirs() }

        cleanup().apply { start() }.await()

        assertThat(own.exists()).isTrue()
        assertThat(decrypted.exists()).isFalse()
        assertThat(shared.exists()).isFalse()
        assertThat(staging.exists()).isFalse()
    }

    @Test
    fun `the cleanup runs once per process, however often it is started`() = runBlocking {
        val cleanup = cleanup()
        cleanup.apply { start() }.await()
        val own = cacheFile("vault_decrypt/later/secret.txt")
        cleanup.start()
        cleanup.await()
        assertThat(own.exists()).isTrue() // not purged again by the same instance
    }
}
