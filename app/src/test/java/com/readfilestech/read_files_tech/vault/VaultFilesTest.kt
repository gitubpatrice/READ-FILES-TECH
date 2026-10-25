package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException
import com.readfilestech.read_files_tech.vault.format.VaultFormatException
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.file.Files

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
    fun `streamed, an altered vault file is refused by its tag and leaves nothing - export or cache`() = runBlocking<Unit> {
        val vault = openVault(TestVault.STREAMED)
        val stored = vault.files.import("big.bin", 60_000, { ByteArray(60_000) { 3 }.inputStream() })
        val bytes = stored.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte() // in the tag
        stored.writeBytes(bytes)
        val out = File(root, "out").apply { mkdirs() }
        assertThrows<VaultAuthenticationException> { runBlocking { vault.files.export(stored, out) } }
        assertThat(out.list()!!.toList()).isEmpty()
        assertThrows<VaultAuthenticationException> { runBlocking { vault.files.decryptToCache(stored) } }
        assertThat(File(vault.dirs.cache, VaultFiles.DECRYPT_DIR).list().orEmpty().toList()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(longs = [TestVault.ONE_BLOCK, TestVault.STREAMED])
    fun `a byte changed in the nonce or the ciphertext is refused and leaves nothing, on both engines`(heap: Long) = runBlocking<Unit> {
        val vault = openVault(heap)
        val data = ByteArray(20_000) { 0x41 }
        val out = File(root, "out").apply { mkdirs() }
        // 4: the first byte of the nonce, right after the magic. 10 000: the middle of the ciphertext,
        // so that it is the authentication of the content that refuses, not that of the tag.
        for (offset in listOf(4, 10_000)) {
            val stored = vault.files.import("doc-$offset.bin", data.size.toLong(), { data.inputStream() })
            val bytes = stored.readBytes()
            bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
            stored.writeBytes(bytes)
            assertThrows<VaultAuthenticationException>("byte $offset") { runBlocking { vault.files.export(stored, out) } }
            assertThrows<VaultAuthenticationException>("byte $offset") { runBlocking { vault.files.decryptToCache(stored) } }
        }
        assertThat(out.list()!!.toList()).isEmpty()
        assertThat(File(vault.dirs.cache, VaultFiles.DECRYPT_DIR).list().orEmpty().toList()).isEmpty()
    }

    @Test
    fun `a vault file renamed on disk no longer decrypts - the AAD binds its name`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("facture.pdf", 1_024, { ByteArray(1_024) { 0x50 }.inputStream() })
        // Someone who can write the vault folder renames it, to pass it off as another document.
        val renamed = File(vault.dirs.vault, "releve.pdf.enc")
        assertThat(stored.renameTo(renamed)).isTrue()
        val out = File(root, "out").apply { mkdirs() }
        assertThrows<VaultAuthenticationException> { runBlocking { vault.files.export(renamed, out) } }
        assertThrows<VaultAuthenticationException> { runBlocking { vault.files.decryptToCache(renamed) } }
        assertThat(out.list()!!.toList()).isEmpty()
    }

    @Test
    fun `a vault the port created refuses a v1 blob - a file stripped of its magic`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("note.txt", 7, { "contenu".byteInputStream() })
        // Without "RFT2" the blob reads as the v1 format, which has no AAD: on this vault, a substitution.
        stored.writeBytes(stored.readBytes().copyOfRange(4, stored.length().toInt()))
        val out = File(root, "out").apply { mkdirs() }
        assertThrows<VaultFormatException> { runBlocking { vault.files.export(stored, out) } }
        assertThrows<VaultFormatException> { runBlocking { vault.files.decryptToCache(stored) } }
        assertThat(out.list()!!.toList()).isEmpty()
    }

    @Test
    fun `D1 - a lock during a decryption to the cache stops it and leaves no plaintext`() = runBlocking<Unit> {
        val vault = openVault()
        val text = "clair à ne pas laisser".toByteArray()
        val stored = vault.files.import("d1-cache.txt", text.size.toLong(), { text.inputStream() })
        // The vault locks once the decryption is under way: its output is open in the cache.
        val locking = object : File(stored.path) {
            private var fired = false

            override fun length(): Long {
                if (!fired) {
                    fired = true
                    vault.service.lock()
                }
                return super.length()
            }
        }
        assertThrows<LockedDuringOperationException> { runBlocking { vault.files.decryptToCache(locking) } }
        assertThat(File(vault.dirs.cache, VaultFiles.DECRYPT_DIR).walk().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `a locked vault decrypts nothing - export, cache and backup refused`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("g.txt", 9, { "contenu G".byteInputStream() })
        vault.service.lock()
        val out = File(root, "out").apply { mkdirs() }
        assertThrows<VaultLockedException> { runBlocking { vault.files.export(stored, out) } }
        assertThrows<VaultLockedException> { runBlocking { vault.files.decryptToCache(stored) } }
        assertThrows<VaultLockedException> {
            runBlocking { vault.backups.export("x", VaultBackups.Destination(out) { File(out, "coffre.rftvault") }) }
        }
        assertThat(out.list()!!.toList()).isEmpty()
    }

    @Test
    fun `an import running across a reset and a new vault never lands in the new vault`() = runBlocking<Unit> {
        val vault = openVault()
        val data = ByteArray(50_000) { it.toByte() }
        // The vault is open again when the import checks — but under another key: only the epoch tells.
        // Triggered as the source opens, once the import holds its copy of the old key.
        val source = {
            runBlocking {
                vault.service.reset()
                vault.service.setup("a new vault")
            }
            data.inputStream()
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
    fun `a symbolic link planted in the vault is neither listed nor read, exported or deleted through`() = runBlocking<Unit> {
        val vault = openVault()
        val outside = File(root, "outside.enc").apply { writeText("not the vault's") }
        val link = File(vault.dirs.vault, "link.enc").toPath()
        val created = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        // Windows needs a privilege to create a link; the Linux CI always runs this.
        assumeTrue(created, "symbolic links cannot be created on this machine")
        assertThat(vault.files.list()).isEmpty()
        val asFile = link.toFile()
        assertThrows<IllegalArgumentException> { runBlocking { vault.files.decryptToCache(asFile) } }
        assertThrows<IllegalArgumentException> { runBlocking { vault.files.export(asFile, File(root, "out")) } }
        assertThrows<IllegalArgumentException> { runBlocking { vault.files.delete(asFile) } }
        assertThat(outside.readText()).isEqualTo("not the vault's")
    }

    @Test
    fun `a locked vault opens no operation, deletion included`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("kept.txt", 1, { byteArrayOf(1).inputStream() })
        vault.service.lock()
        assertThrows<VaultLockedException> { runBlocking { vault.files.import("a", 1, { byteArrayOf(1).inputStream() }) } }
        assertThrows<VaultLockedException> { runBlocking { vault.files.delete(stored) } }
        assertThat(stored.exists()).isTrue()
    }

    @Test
    fun `two imports of the same name - the second one does not replace the first without consent`() = runBlocking<Unit> {
        val vault = openVault()
        // The second import starts once the first has checked for a namesake and is reading its source.
        val second = {
            LockingInput("first".byteInputStream()) {
                runBlocking { vault.files.import("a.txt", 6, { "second".byteInputStream() }) }
            }
        }
        assertThrows<NameTakenException> { runBlocking { vault.files.import("a.txt", 5, second) } }
        val stored = File(vault.dirs.vault, "a.txt.enc")
        assertThat(vault.files.decryptToCache(stored).readText()).isEqualTo("second")
        assertThat(vault.vaultEntries().filter { it.endsWith(".tmp") }).isEmpty()
    }

    @Test
    fun `a viewer's route finds a file of the vault by its name, and nothing else`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("photo.jpg", 3, { byteArrayOf(1, 2, 3).inputStream() })
        File(vault.dirs.vault, "dossier.enc").mkdirs()
        assertThat(vault.dirs.fileNamed("photo.jpg.enc")).isEqualTo(stored)
        for (name in listOf(VaultNames.SENTINEL_FILE, "absent.enc", "dossier.enc", "../vault/photo.jpg.enc", "a/photo.jpg.enc", "..", "")) {
            assertThat(vault.dirs.fileNamed(name)).isNull()
        }
    }

    @Test
    fun `the size shown is the plaintext's, for a v2 file and for a v1 one, without the key`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("notes.txt", 1_000, { ByteArray(1_000).inputStream() })
        // A 2.5.4's v1 file: no magic, a 12-byte nonce and a 16-byte tag around its ciphertext.
        val old = File(vault.dirs.vault, "ancien.txt.enc").apply { writeBytes(ByteArray(100) { 7 }) }
        vault.service.lock()
        assertThat(vault.cipher.plainLength(stored)).isEqualTo(1_000)
        assertThat(vault.cipher.plainLength(old)).isEqualTo(72)
    }

    @Test
    fun `a share's copy goes apart from the viewers', and a lock purges both (docs D-034)`() = runBlocking<Unit> {
        val vault = openVault()
        val stored = vault.files.import("relevé.pdf", 4, { byteArrayOf(9, 8, 7, 6).inputStream() })
        val shared = vault.files.copy(stored, VaultFiles.Copy.SHARE)
        val viewed = vault.files.decryptToCache(stored)
        assertThat(shared.parentFile?.parentFile).isEqualTo(File(vault.dirs.cache, VaultFiles.SHARE_DIR))
        assertThat(viewed.parentFile?.parentFile).isEqualTo(File(vault.dirs.cache, VaultFiles.DECRYPT_DIR))
        assertThat(shared.readBytes()).isEqualTo(byteArrayOf(9, 8, 7, 6))
        vault.service.lock()
        assertThat(File(vault.dirs.cache, VaultFiles.SHARE_DIR).exists()).isFalse()
        assertThat(File(vault.dirs.cache, VaultFiles.DECRYPT_DIR).exists()).isFalse()
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
