package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.EOFException
import java.io.File
import java.io.InputStream

class VaultFilesTest {
    @TempDir
    lateinit var root: File

    @ParameterizedTest
    @ValueSource(longs = [TestVault.ONE_BLOCK, TestVault.STREAMED])
    fun `imported files list, open from the cache and export identical, on both engines`(heap: Long) = runBlocking<Unit> {
        val vault = openVault(heap)
        val contents = mapOf(
            "vide.txt" to ByteArray(0),
            "petit.txt" to "été ✓".toByteArray(),
            "gros.bin" to ByteArray(200_003) { (it * 7).toByte() },
        )
        contents.forEach { (name, data) -> vault.files.import(name, data.size.toLong(), { data.inputStream() }) }
        val listed = vault.files.list()
        assertThat(listed.map { it.name }).containsExactly("gros.bin.enc", "petit.txt.enc", "vide.txt.enc").inOrder()
        for (file in listed) {
            val clear = vault.files.decryptToCache(file)
            assertThat(clear.readBytes()).isEqualTo(contents.getValue(clear.name))
            val out = File(root, "out").apply { mkdirs() }
            assertThat(vault.files.export(file, out).readBytes()).isEqualTo(contents.getValue(clear.name))
        }
        assertThat(vault.vaultEntries().filter { it.endsWith(".tmp") }).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(longs = [TestVault.ONE_BLOCK, TestVault.STREAMED])
    fun `D1 - a lock during an import stops it before writing - no file, no residue`(heap: Long) = runBlocking<Unit> {
        val vault = openVault(heap)
        val data = ByteArray(50_000) { it.toByte() }
        // The source is read while the vault locks: leaving the screen, 30 s in the background, panic.
        val source = { LockingInput(data.inputStream()) { vault.service.lock() } }
        assertThrows<LockedDuringOperationException> {
            runBlocking { vault.files.import("photo.jpg", data.size.toLong(), source) }
        }
        assertThat(vault.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE)
    }

    @Test
    fun `an import running across a reset and a new vault never lands in the new vault`() = runBlocking<Unit> {
        val vault = openVault()
        val data = ByteArray(50_000) { it.toByte() }
        // The vault is open again when the import checks — but under another key: only the epoch tells.
        val source = {
            LockingInput(data.inputStream()) {
                runBlocking {
                    vault.service.reset()
                    vault.service.setup("a new vault")
                }
            }
        }
        assertThrows<LockedDuringOperationException> {
            runBlocking { vault.files.import("photo.jpg", data.size.toLong(), source) }
        }
        assertThat(vault.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE)
    }

    @Test
    fun `D27 - a file named _check cannot replace the sentinel, even with overwrite`() = runBlocking<Unit> {
        val vault = openVault()
        val refused = assertThrows<NameRefusedException> {
            runBlocking { vault.files.import("_check", 1, { byteArrayOf(1).inputStream() }, overwrite = true) }
        }
        assertThat(refused.name).isEqualTo("_check")
        vault.service.lock()
        assertThat(vault.service.unlock(PASSWORD)).isTrue()
    }

    @Test
    fun `a namesake is reported, then replaced only when asked`() = runBlocking<Unit> {
        val vault = openVault()
        vault.files.import("a.txt", 1, { byteArrayOf(1).inputStream() })
        assertThrows<NameTakenException> { runBlocking { vault.files.import("a.txt", 1, { byteArrayOf(2).inputStream() }) } }
        val replaced = vault.files.import("a.txt", 1, { byteArrayOf(2).inputStream() }, overwrite = true)
        assertThat(vault.files.decryptToCache(replaced).readBytes()).isEqualTo(byteArrayOf(2))
    }

    @Test
    fun `above 100 MiB an import is refused before reading, and a source that lies about its size fails`() = runBlocking<Unit> {
        val vault = openVault()
        var opened = false
        val source = {
            opened = true
            InputStream.nullInputStream()
        }
        assertThrows<TooLargeException> {
            runBlocking { vault.files.import("big.iso", VaultFiles.IMPORT_MAX_BYTES + 1, source) }
        }
        assertThat(opened).isFalse()
        assertThrows<EOFException> {
            runBlocking { vault.files.import("short.txt", 10, { ByteArray(5).inputStream() }) }
        }
        assertThrows<EOFException> {
            runBlocking { vault.files.import("long.txt", 5, { ByteArray(10).inputStream() }) }
        }
        assertThat(vault.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE)
    }

    @Test
    fun `export never replaces a file of the destination unless asked`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("doc.txt", 3, { "new".byteInputStream() })
        val out = File(root, "out").apply { mkdirs() }
        File(out, "doc.txt").writeText("mine")
        assertThrows<NameTakenException> { runBlocking { vault.files.export(stored, out) } }
        assertThat(File(out, "doc.txt").readText()).isEqualTo("mine")
        assertThat(vault.files.export(stored, out, overwrite = true).readText()).isEqualTo("new")
    }

    @Test
    fun `D24 and D26 - the sentinel and write residues are not listed, any other name is`() = runBlocking<Unit> {
        val vault = openVault()
        vault.files.import("rapport_check", 1, { byteArrayOf(1).inputStream() })
        File(vault.dirs.vault, "photo.jpg.enc.3-a1b2c3d4.tmp").writeText("residue")
        File(vault.dirs.vault, "notes").writeText("an old file without .enc")
        File(vault.dirs.vault, ".restore-0011").mkdirs()
        assertThat(vault.files.list().map { it.name }).containsExactly("notes", "rapport_check.enc").inOrder()
    }

    @Test
    fun `a decrypted copy is its own folder of the cache, and a lock removes it`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("a.pdf", 2, { byteArrayOf(1, 2).inputStream() })
        val first = vault.files.decryptToCache(stored)
        val second = vault.files.decryptToCache(stored)
        assertThat(first.parentFile).isNotEqualTo(second.parentFile)
        assertThat(first.name).isEqualTo("a.pdf")
        vault.service.lock()
        assertThat(first.exists() || second.exists()).isFalse()
    }

    @Test
    fun `nothing outside the vault directory is deleted or decrypted through it`() = runBlocking<Unit> {
        val vault = openVault()
        val outside = File(root, "elsewhere.enc").apply { writeText("x") }
        assertThrows<IllegalArgumentException> { runBlocking { vault.files.delete(outside) } }
        assertThrows<IllegalArgumentException> { runBlocking { vault.files.decryptToCache(outside) } }
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `a locked vault opens no operation`() = runBlocking<Unit> {
        val vault = openVault()
        vault.service.lock()
        assertThrows<VaultLockedException> { runBlocking { vault.files.import("a", 1, { byteArrayOf(1).inputStream() }) } }
    }

    private suspend fun openVault(heap: Long = TestVault.ONE_BLOCK): TestVault {
        val vault = TestVault(root, heap)
        vault.service.setup(PASSWORD)
        return vault
    }

    /** Runs [onFirstRead] once, at the first read: the moment the operation is under way. */
    private class LockingInput(private val input: InputStream, private var onFirstRead: (() -> Unit)?) : InputStream() {
        override fun read(): Int {
            fire()
            return input.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            fire()
            return input.read(b, off, len)
        }

        private fun fire() {
            onFirstRead?.invoke()
            onFirstRead = null
        }
    }

    private companion object {
        const val PASSWORD = "mot de passe"
    }
}
