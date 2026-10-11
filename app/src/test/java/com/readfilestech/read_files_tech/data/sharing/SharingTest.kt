package com.readfilestech.read_files_tech.data.sharing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What may leave the app (`safeCanonical` of the 2.15.x), and the type it is announced with. */
class SharingTest {
    @TempDir
    lateinit var tmp: File

    private val pkg = "com.readfilestech.read_files_tech"

    private fun guard(): PathGuard {
        val storage = File(tmp, "storage").apply { mkdirs() }
        val cache = File(tmp, "cache").apply { mkdirs() }
        return PathGuard(
            allowedRoots = listOf(storage.canonicalFile),
            blockedDirs = listOf("vault_decrypt", "share_plus").map { File(cache.canonicalFile, it) },
            packageName = pkg,
        )
    }

    private fun under(vararg parts: String) = File(tmp, (listOf("storage") + parts).joinToString(File.separator))

    @Test
    fun `a file of shared storage may leave, resolved`() {
        val f = under("Download", "a.pdf").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertThat(guard().resolve(f)).isEqualTo(f.canonicalFile)
    }

    @Test
    fun `nothing outside the allowed roots`() {
        val outside = File(tmp, "private/vault/x.enc").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertThat(guard().resolve(outside)).isNull()
        // Climbing out with ".." is resolved before the test, not after.
        assertThat(guard().resolve(File(under("Download"), "../../private/vault/x.enc"))).isNull()
    }

    @Test
    fun `the vault plaintext caches never leave`() {
        val decrypted = File(tmp, "cache/vault_decrypt/secret.pdf").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        val guard = PathGuard(listOf(tmp.canonicalFile), listOf(File(File(tmp, "cache").canonicalFile, "vault_decrypt")), pkg)
        assertThat(guard.resolve(decrypted)).isNull()
        assertThat(guard.resolve(File(tmp, "cache/vault_decrypt"))).isNull()
        // A sibling whose name only STARTS like the blocked folder is not blocked.
        val sibling = File(tmp, "cache/vault_decrypted_not/ok.txt").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertThat(guard.resolve(sibling)).isNotNull()
    }

    @Test
    fun `another app's Android data or obb folder never leaves, ours does`() {
        val guard = guard()
        assertThat(guard.resolve(under("Android", "data", "com.other.app", "files", "x"))).isNull()
        assertThat(guard.resolve(under("Android", "obb", "com.other.app", "main.obb"))).isNull()
        assertThat(guard.resolve(under("Android", "data", pkg, "files", "x"))).isNotNull()
    }

    /** The 2.15.x tested `contains("/Android/data/<package>")`: `<package>.evil` passed as ours. */
    @Test
    fun `a package whose name only starts like ours is another app`() {
        assertThat(guard().resolve(under("Android", "data", "$pkg.evil", "files", "x"))).isNull()
        assertThat(guard().resolve(under("Android", "data", "${pkg}x", "files", "x"))).isNull()
    }

    @Test
    fun `the 2_15_x types first, then one type for several files`() {
        assertThat(MimeTypes.of("pdf")).isEqualTo("application/pdf")
        assertThat(MimeTypes.of("md")).isNull()
        assertThat(MimeTypes.common(listOf("image/png", "image/png"))).isEqualTo("image/png")
        assertThat(MimeTypes.common(listOf("image/png", "image/jpeg"))).isEqualTo("image/*")
        assertThat(MimeTypes.common(listOf("image/png", "application/pdf"))).isEqualTo("*/*")
        assertThat(MimeTypes.common(listOf("*/*", "*/*"))).isEqualTo("*/*")
    }
}
