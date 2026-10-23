package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.vault.format.Argon2Params
import com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException
import com.readfilestech.read_files_tech.vault.format.VaultBackupFormat
import com.readfilestech.read_files_tech.vault.format.VaultKdf
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

class VaultBackupsTest {
    @TempDir
    lateinit var root: File

    @ParameterizedTest
    @ValueSource(longs = [TestVault.ONE_BLOCK, TestVault.STREAMED])
    fun `an export restores identical into another vault, on both engines`(heap: Long) = runBlocking<Unit> {
        val source = vault("source", heap)
        val contents = mapOf(
            "notes été.txt" to "Bonjour ✓".toByteArray(),
            "vide" to ByteArray(0),
            "gros.bin" to ByteArray(150_000) { it.toByte() },
        )
        contents.forEach { (name, data) -> source.files.import(name, data.size.toLong(), { data.inputStream() }) }
        val backup = source.backups.export(EXPORT_PASSWORD, destination("out"))
        assertThat(backup.name).isEqualTo("coffre.rftvault")
        assertThat(File(root, "out").list()!!.toList()).containsExactly("coffre.rftvault") // no residue
        val target = vault("target", heap)
        val result = target.backups.restore(backup, EXPORT_PASSWORD)
        assertThat(result).isEqualTo(VaultBackups.RestoreResult(3, 3, 0, emptyList(), emptyList()))
        assertThat(clearContents(target)).isEqualTo(contents.mapValues { sha256(it.value) })
        assertThat(target.vaultEntries().filter { it.startsWith(".restore-") }).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(longs = [TestVault.ONE_BLOCK, TestVault.STREAMED])
    fun `the backup the 2_15_2 wrote on a device restores into the port, on both engines`(heap: Long) = runBlocking<Unit> {
        val target = vault("target", heap)
        val device = checkNotNull(VaultBackupsTest::class.java.getResource("/device/emu34.rftvault"))
        val backup = File(root, "emu34.rftvault").apply { writeBytes(device.readBytes()) }
        val result = target.backups.restore(backup, "Sauvegarde ✓ export")
        assertThat(result.restored).isEqualTo(5)
        assertThat(clearContents(target)).isEqualTo(
            mapOf(
                "Relevé été 2026 ✓.txt" to "646385b512e5eb78e7153ad409dde88297fbaa4df1296b43e66f821ce5029428",
                "vide.txt" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "photo-200k.jpg" to "27c643827cee16fbff7f10f3243dceb9ad78d15214b8b406b63c8f95bbb0c489",
                "scan-2M.pdf" to "39bdab5cef6cc135ae3419453ca59031a3e34deee73ac44b0c88c5d580d32401",
                "gros-6M.bin" to "11707b8478c614f584ba01761ff13a254a034aa903df0838c2c96f7c678f3c71",
            ),
        )
    }

    @ParameterizedTest
    @ValueSource(longs = [TestVault.ONE_BLOCK, TestVault.STREAMED])
    fun `a wrong export password is counted and publishes nothing, on both engines`(heap: Long) = runBlocking<Unit> {
        val backup = exportOf(mapOf("a.txt" to ByteArray(5_000) { 1 }, "b.txt" to ByteArray(5_000) { 2 }))
        val target = vault("target", heap)
        assertThrows<VaultAuthenticationException> { runBlocking { target.backups.restore(backup, "wrong") } }
        assertThat(target.stored(VaultKeys.BACKUP_FAILS)).isEqualTo(1L)
        assertThat(target.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE)
    }

    @ParameterizedTest
    @ValueSource(longs = [TestVault.ONE_BLOCK, TestVault.STREAMED])
    fun `a backup altered after its first entry publishes nothing - even the entries before it`(heap: Long) = runBlocking<Unit> {
        val backup = exportOf(linkedMapOf("1-first.txt" to ByteArray(20_000) { 1 }, "2-second.txt" to ByteArray(20_000) { 2 }))
        val bytes = backup.readBytes()
        bytes[bytes.size - 100] = (bytes[bytes.size - 100].toInt() xor 1).toByte() // inside the second entry
        backup.writeBytes(bytes)
        val target = vault("target", heap)
        assertThrows<VaultAuthenticationException> { runBlocking { target.backups.restore(backup, EXPORT_PASSWORD) } }
        assertThat(target.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE)
    }

    @Test
    fun `streamed, bytes after the last entry do not skip the tag - an altered entry publishes nothing`() = runBlocking<Unit> {
        // The 2.15.x ignores what follows the last entry. The parser then stops before the end of the
        // envelope, where the tag is: only reading on to the end checks it.
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeInt(1)
                writeShort(5)
                write("a.txt".toByteArray())
                writeInt(5_000)
                write(ByteArray(5_000) { 1 })
                write(ByteArray(3_000)) // trailing bytes
            }
        }.toByteArray()
        val salt = ByteArray(VaultKdf.SALT_LEN) { 3 }
        val params = Argon2Params(1_024, 1)
        val key = VaultKdf.argon2id(EXPORT_PASSWORD, salt, params.memoryKB, params.iterations)
        val sealed = VaultBackupFormat.writeV2(payload, key, salt, ByteArray(12) { 4 }, params)
        val headerLength = VaultBackupFormat.readHeader(sealed).length
        sealed[headerLength + 4 + 2 + 5 + 4 + 100] = (sealed[headerLength + 4 + 2 + 5 + 4 + 100].toInt() xor 1).toByte()
        val backup = File(root, "trailing.rftvault").apply { writeBytes(sealed) }
        val target = vault("target", TestVault.STREAMED)
        assertThrows<VaultAuthenticationException> { runBlocking { target.backups.restore(backup, EXPORT_PASSWORD) } }
        assertThat(target.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE)
    }

    @Test
    fun `five wrong export passwords arm the restore lockout, apart from the unlock one`() = runBlocking<Unit> {
        val backup = exportOf(mapOf("a.txt" to byteArrayOf(1)))
        val target = vault("target")
        repeat(5) { assertThrows<VaultAuthenticationException> { runBlocking { target.backups.restore(backup, "wrong") } } }
        assertThrows<VaultLockoutException> { runBlocking { target.backups.restore(backup, EXPORT_PASSWORD) } }
        assertThat(target.stored(VaultKeys.UNLOCK_FAILS)).isNull()
        target.clock.advance(60_000)
        assertThat(target.backups.restore(backup, EXPORT_PASSWORD).restored).isEqualTo(1)
        VaultKeys.BACKUP_LOCKOUT.all.forEach { assertThat(target.stored(it)).isNull() }
    }

    @Test
    fun `D2 - after a reboot the restore lockout lasts its delay by the wall clock, as the unlock one`() = runBlocking<Unit> {
        val backup = exportOf(mapOf("x.txt" to byteArrayOf(1)))
        val target = vault("target")
        target.clock.mono = 10L * 24 * 3_600_000 // ten days of uptime when the lockout is armed
        repeat(5) { assertThrows<VaultAuthenticationException> { runBlocking { target.backups.restore(backup, "wrong") } } }
        // Armed: the right password is refused before any derivation.
        assertThrows<VaultLockoutException> { runBlocking { target.backups.restore(backup, EXPORT_PASSWORD) } }
        // Reboot: elapsedRealtime restarts near zero; the wall clock moved on by 10 s.
        target.clock.mono = 5_000
        target.clock.wall += 10_000
        val refused = assertThrows<VaultLockoutException> { runBlocking { target.backups.restore(backup, EXPORT_PASSWORD) } }
        assertThat(refused.remainingMs).isEqualTo(50_000)
        target.clock.wall += 60_000
        assertThat(target.backups.restore(backup, EXPORT_PASSWORD).restored).isEqualTo(1)
    }

    @Test
    fun `D1 - a restore a lock interrupts publishes nothing, at its start or once the tag is checked`() = runBlocking<Unit> {
        val backup = exportOf(mapOf("r1.txt" to "un".toByteArray(), "r2.txt" to "deux".toByteArray()))
        val target = vault("target")
        val moments = mapOf<String, () -> Unit>(
            // As the restore starts, before its first entry: the 2.15.3's case.
            "at the start" to {
                target.argon2.onCanAfford = {
                    target.argon2.onCanAfford = null
                    target.service.lock()
                }
            },
            // At the commit that clears the restore lockout, after the tag: only publication is left.
            "before publication" to {
                target.raw.onCommit = {
                    target.raw.onCommit = null
                    target.service.lock()
                }
            },
        )
        for ((moment, arm) in moments) {
            arm()
            assertThrows<LockedDuringOperationException>(moment) { runBlocking { target.backups.restore(backup, EXPORT_PASSWORD) } }
            assertThat(target.service.unlock(PASSWORD)).isTrue()
            assertThat(target.files.list()).isEmpty()
            assertThat(target.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE)
        }
    }

    @Test
    fun `files already there are kept unless overwrite is asked`() = runBlocking<Unit> {
        val backup = exportOf(mapOf("a.txt" to "from backup".toByteArray(), "b.txt" to "b".toByteArray()))
        val target = vault("target")
        target.files.import("a.txt", 4, { "mine".byteInputStream() })
        val kept = target.backups.restore(backup, EXPORT_PASSWORD)
        assertThat(kept).isEqualTo(VaultBackups.RestoreResult(2, 1, 1, emptyList(), emptyList()))
        assertThat(clearContents(target)["a.txt"]).isEqualTo(sha256("mine".toByteArray()))
        target.backups.restore(backup, EXPORT_PASSWORD, overwriteExisting = true)
        assertThat(clearContents(target)["a.txt"]).isEqualTo(sha256("from backup".toByteArray()))
    }

    @Test
    fun `two entries of the same name - the first one stays, as in the 2_15_x`() = runBlocking<Unit> {
        val forged = forgedBackup(listOf("a.txt" to "first", "a.txt" to "second"))
        val target = vault("target")
        val result = target.backups.restore(forged, EXPORT_PASSWORD)
        assertThat(result).isEqualTo(VaultBackups.RestoreResult(2, 1, 1, emptyList(), emptyList()))
        assertThat(clearContents(target)["a.txt"]).isEqualTo(sha256("first".toByteArray()))
    }

    @Test
    fun `entries no name can hold are counted, the sentinel stays the sentinel - D25`() = runBlocking<Unit> {
        val forged = forgedBackup(listOf("ok.txt" to "ok", "_check" to "not a sentinel", "../evil" to "x"))
        val target = vault("target")
        val result = target.backups.restore(forged, EXPORT_PASSWORD, overwriteExisting = true)
        assertThat(result.restored).isEqualTo(1)
        assertThat(result.refused.map { it.name }).containsExactly("_check", "../evil").inOrder()
        target.service.lock()
        assertThat(target.service.unlock(PASSWORD)).isTrue()
        // Where "../evil" would have landed: next to the vault directory, as "evil.enc".
        assertThat(File(target.dirs.vault.parentFile, "evil.enc").exists()).isFalse()
        assertThat(target.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE, "ok.txt.enc")
    }

    @Test
    @Timeout(30) // a derivation at 1 GiB × 64 would run out of memory, or for minutes
    fun `a header asking 1 GiB of Argon2 is refused before any derivation`() = runBlocking<Unit> {
        val huge = Argon2Params(memoryKB = 1_048_576, iterations = 64) // the bounds of the 2.15.x, both maxed
        val salt = ByteArray(VaultKdf.SALT_LEN) { 1 }
        val file = VaultBackupFormat.writeV2(ByteArray(100), ByteArray(32) { 2 }, salt, ByteArray(12) { 3 }, huge)
        val backup = File(root, "huge.rftvault").apply { writeBytes(file) }
        val target = vault("target")
        target.argon2.affordable = false
        val refused = assertThrows<BackupTooCostlyException> { runBlocking { target.backups.restore(backup, "any") } }
        assertThat(refused.params).isEqualTo(huge)
        assertThat(target.stored(VaultKeys.BACKUP_FAILS)).isNull() // nothing tried, nothing counted
    }

    @Test
    fun `an export a lock interrupts stops before the seconds of Argon2 and leaves nothing behind`() = runBlocking<Unit> {
        val source = vault("source")
        source.files.import("a.txt", 1, { byteArrayOf(1).inputStream() })
        source.argon2.onExportParams = { source.service.lock() }
        val out = File(root, "out")
        assertThrows<LockedDuringOperationException> {
            runBlocking { source.backups.export(EXPORT_PASSWORD, destination("out")) }
        }
        // Not even created: the check comes before the derivation, the destination after it.
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun `a staging folder a killed restore left is discarded`() = runBlocking<Unit> {
        val target = vault("target")
        File(target.dirs.vault, ".restore-0011/0").apply { parentFile.mkdirs() }.writeText("left behind")
        target.backups.discardLeftovers()
        assertThat(target.vaultEntries()).containsExactly(VaultNames.SENTINEL_FILE)
    }

    private suspend fun vault(name: String, heap: Long = TestVault.ONE_BLOCK): TestVault {
        val vault = TestVault(File(root, name), heap)
        vault.service.setup(PASSWORD)
        return vault
    }

    private suspend fun exportOf(contents: Map<String, ByteArray>): File {
        val source = vault("source-" + contents.keys.joinToString("-").hashCode())
        contents.forEach { (name, data) -> source.files.import(name, data.size.toLong(), { data.inputStream() }) }
        return source.backups.export(EXPORT_PASSWORD, destination("exports"))
    }

    /** A backup built by hand, with what the port's writer refuses to write. */
    private fun forgedBackup(entries: List<Pair<String, String>>): File {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeInt(entries.size)
                for ((name, data) in entries) {
                    val n = name.toByteArray()
                    writeShort(n.size)
                    write(n)
                    writeInt(data.length)
                    write(data.toByteArray())
                }
            }
        }.toByteArray()
        val salt = ByteArray(VaultKdf.SALT_LEN) { 3 }
        val params = Argon2Params(1_024, 1)
        val key = VaultKdf.argon2id(EXPORT_PASSWORD, salt, params.memoryKB, params.iterations)
        return File(root, "forged-${entries.hashCode()}.rftvault").apply {
            writeBytes(VaultBackupFormat.writeV2(payload, key, salt, ByteArray(12) { 4 }, params))
        }
    }

    private fun destination(dir: String): VaultBackups.Destination {
        val out = File(root, dir)
        return VaultBackups.Destination(out) {
            generateSequence(0) { it + 1 }
                .map { File(out, if (it == 0) "coffre.rftvault" else "coffre_$it.rftvault") }
                .first { it.createNewFile() }
        }
    }

    private suspend fun clearContents(vault: TestVault): Map<String, String> =
        vault.files.list().associate { VaultNames.plainName(it.name) to sha256(vault.files.decryptToCache(it).readBytes()) }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PASSWORD = "maître"
        const val EXPORT_PASSWORD = "export 🔒"
    }
}
