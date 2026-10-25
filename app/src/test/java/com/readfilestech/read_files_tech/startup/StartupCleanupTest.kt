package com.readfilestech.read_files_tech.startup

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.vault.TestVault
import com.readfilestech.read_files_tech.vault.VaultDirs
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

    private fun cleanup(dirs: VaultDirs = vault.dirs) = StartupCleanup(vault.prefs, vault.service, vault.backups, dirs)

    private fun cacheFile(path: String) = File(vault.dirs.cache, path).apply {
        parentFile.mkdirs()
        writeText("plain")
    }

    @Test
    fun `the first start empties the whole cache the Flutter app left, ZIP entries under any name included`() {
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

        cleanup().runOnce()

        left.forEach { assertThat(it.exists()).isFalse() }
        assertThat(vault.dirs.cache.list()).isEmpty()
        assertThat(vaultFile.exists()).isTrue()
        assertThat(vault.stored(StartupCleanup.FLUTTER_CACHE_PURGED)).isEqualTo(true)
    }

    /** Nothing reads it since the 2.15.3, which checks nothing online: erased, and nothing else with it. */
    @Test
    fun `the 2_15_2's last update check is erased, the other settings kept`() {
        vault.prefs.commit {
            // As the 2.15.2 wrote it (tools/phase2/flutter_seed: `update_last_check_ms_` + its repository, lower case).
            putLong("update_last_check_ms_read-files-tech", 1_791_720_000_000)
            putString("theme_mode", "dark")
        }

        cleanup().runOnce()

        assertThat(vault.prefs.contains("update_last_check_ms_read-files-tech")).isFalse()
        assertThat(vault.prefs.contains("theme_mode")).isTrue()
    }

    @Test
    fun `later starts keep the port's own cache, and still purge the decrypted copies and restore staging`() {
        cleanup().runOnce()
        val own = cacheFile("port-thumbnail.png")
        val decrypted = cacheFile("vault_decrypt/d4e5f6/secret.txt")
        val shared = cacheFile("share_plus/secret.txt")
        val staging = File(vault.dirs.vault, ".restore-0a1b").apply { mkdirs() }

        cleanup().runOnce()

        assertThat(own.exists()).isTrue()
        assertThat(decrypted.exists()).isFalse()
        assertThat(shared.exists()).isFalse()
        assertThat(staging.exists()).isFalse()
    }

    @Test
    fun `a cache that cannot be listed is not taken for an empty one - the next start tries again`() {
        // A FILE where the cache folder should be: listFiles() returns null, as on an I/O error.
        val unlistable = VaultDirs(vault = vault.dirs.vault, cache = File(tmp, "not-a-folder").apply { writeText("x") })

        cleanup(unlistable).runOnce()

        assertThat(vault.stored(StartupCleanup.FLUTTER_CACHE_PURGED)).isNull()
    }

    /** The entries of archives shared by the port (ZipShares): kept until the next start, then purged. */
    @Test
    fun `later starts purge the entries of archives shared before`() {
        cleanup().runOnce()
        val shared = cacheFile("zip_shared/1a2b3c/Relevé été.pdf")
        val own = cacheFile("port-thumbnail.png")

        cleanup().runOnce()

        assertThat(shared.exists()).isFalse()
        assertThat(File(vault.dirs.cache, "zip_shared").exists()).isFalse()
        assertThat(own.exists()).isTrue()
    }

    @Test
    fun `the cleanup runs once per process, however often it is called`() {
        val cleanup = cleanup()
        cleanup.runOnce()
        val later = cacheFile("vault_decrypt/later/secret.txt")
        cleanup.runOnce()
        assertThat(later.exists()).isTrue() // not purged again by the same instance
    }

    /** A fresh start: nothing can claim the photos and pages of a scan left there, they go. */
    @Test
    fun `the scanner's folder is emptied when the first activity starts afresh, once`() {
        cleanup().runOnce()
        val photo = cacheFile("scanner/0123456789abcdef/src-0123456789abcdef.jpg")
        val capture = cacheFile("scanner/capture/fedcba9876543210.jpg")
        val other = cacheFile("zip_viewer_note.txt")
        val cleanup = cleanup()
        cleanup.clearScansOnce(restoring = false)
        assertThat(photo.exists()).isFalse()
        assertThat(capture.exists()).isFalse()
        assertThat(File(vault.dirs.cache, "scanner").exists()).isFalse()
        assertThat(other.exists()).isTrue()
        // A second window of the app, later in the same process, empties nothing.
        val second = cacheFile("scanner/0123456789abcdef/session.json")
        cleanup.clearScansOnce(restoring = false)
        assertThat(second.exists()).isTrue()
    }

    /** The process died in the middle of a scan (the camera app open): the restored screen reads it back. */
    @Test
    fun `a restored activity keeps the scan the process died in`() {
        cleanup().runOnce()
        val photo = cacheFile("scanner/capture/fedcba9876543210.jpg")
        val cleanup = cleanup()
        cleanup.clearScansOnce(restoring = true)
        cleanup.clearScansOnce(restoring = false)
        assertThat(photo.exists()).isTrue()
    }
}
