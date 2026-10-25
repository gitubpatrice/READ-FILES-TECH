package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.prefs.FlutterPrefsFormat
import com.readfilestech.read_files_tech.vault.format.VaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * The one-off repair of D1 (docs D-016): files a 2.15.2 encrypted under 32 zero bytes, found and
 * re-encrypted for whoever skips the 2.15.3.
 */
class ZeroKeyRepairTest {
    @TempDir
    lateinit var root: File

    @ParameterizedTest
    @ValueSource(longs = [TestVault.ONE_BLOCK, TestVault.STREAMED])
    fun `zero-key files are repaired, sound ones left byte for byte, once`(heap: Long) = runBlocking<Unit> {
        val vault = olderVault(heap)
        vault.files.import("sound.txt", 3_000, { ByteArray(3_000) { 5 }.inputStream() })
        val sound = File(vault.dirs.vault, "sound.txt.enc").readBytes()
        plantZeroKey(vault, "lost.pdf", ByteArray(4_000) { 7 })
        plantZeroKey(vault, "empty", ByteArray(0))

        val result = vault.repair.runOnce()!!
        assertThat(result.repaired).containsExactly("empty", "lost.pdf")
        assertThat(result.unexamined).isEmpty()
        assertThat(File(vault.dirs.vault, "sound.txt.enc").readBytes()).isEqualTo(sound)
        val repaired = vault.files.decryptToCache(File(vault.dirs.vault, "lost.pdf.enc"))
        assertThat(repaired.readBytes()).isEqualTo(ByteArray(4_000) { 7 })
        assertThat(vault.stored(VaultKeys.ZERO_KEY_REPAIR_DONE)).isEqualTo(true)
        assertThat(vault.repair.pendingReport()!!.repaired).containsExactly("empty", "lost.pdf")
        assertThat(vault.vaultEntries().filter { it.endsWith(".tmp") }).isEmpty()

        assertThat(vault.repair.runOnce()).isNull() // never twice
        vault.repair.clearReport()
        assertThat(vault.repair.pendingReport()).isNull()
    }

    @Test
    fun `a vault the port created never accepts a zero-key file`() = runBlocking<Unit> {
        val vault = TestVault(root)
        vault.service.setup(PASSWORD)
        val planted = plantZeroKey(vault, "planted.txt", "injected".toByteArray())
        assertThat(vault.repair.runOnce()).isNull()
        assertThat(File(vault.dirs.vault, "planted.txt.enc").readBytes()).isEqualTo(planted)
    }

    @Test
    fun `a flag that cannot be read widens nothing - D-019`() = runBlocking<Unit> {
        val vault = olderVault()
        vault.plant(VaultKeys.ZERO_KEY_REPAIR_DONE, "yes")
        plantZeroKey(vault, "planted.txt", byteArrayOf(1))
        assertThat(vault.repair.runOnce()).isNull()
    }

    @Test
    fun `a report list that cannot be read is not shown as empty - D-019`() = runBlocking<Unit> {
        val vault = olderVault()
        vault.plant(VaultKeys.ZERO_KEY_REPAIR_REPORT, FlutterPrefsFormat.encodeStringList(listOf("a.txt")))
        vault.plant(VaultKeys.ZERO_KEY_REPAIR_UNEXAMINED, 42L)
        assertThrows<VaultCorruptedException> { vault.repair.pendingReport() }
        assertThat(vault.stored(VaultKeys.ZERO_KEY_REPAIR_UNEXAMINED)).isEqualTo(42L)
    }

    @Test
    fun `the file the process died on is set aside, and listed when the repair ends`() = runBlocking<Unit> {
        val vault = olderVault()
        plantZeroKey(vault, "killer.bin", byteArrayOf(1))
        plantZeroKey(vault, "other.txt", byteArrayOf(2))
        vault.plant(VaultKeys.ZERO_KEY_REPAIR_CURRENT, "killer.bin.enc") // the previous pass died on it

        val first = vault.repair.runOnce()!!
        assertThat(first.repaired).containsExactly("other.txt")
        assertThat(first.unexamined).isEmpty() // not finished: retried at the next unlock
        assertThat(vault.stored(VaultKeys.ZERO_KEY_REPAIR_ATTEMPTS)).isEqualTo(1L)
        vault.repair.runOnce()
        val last = vault.repair.runOnce()!!
        assertThat(last.unexamined).containsExactly("killer.bin")
        assertThat(vault.stored(VaultKeys.ZERO_KEY_REPAIR_DONE)).isEqualTo(true)
        assertThat(vault.repair.pendingReport()!!.unexamined).containsExactly("killer.bin")
    }

    @Test
    fun `a lock during the pass stops it before any write, uncounted, and it resumes at the next unlock`() = runBlocking<Unit> {
        val vault = olderVault()
        val planted = plantZeroKey(vault, "a.txt", byteArrayOf(1))
        // The lock lands while the file is being worked on (its name just recorded as current).
        vault.raw.onCommit = { keys ->
            if ("flutter." + VaultKeys.ZERO_KEY_REPAIR_CURRENT in keys) {
                vault.raw.onCommit = null
                vault.service.lock()
            }
        }
        assertThrows<LockedDuringOperationException> { runBlocking { vault.repair.runOnce() } }
        assertThat(File(vault.dirs.vault, "a.txt.enc").readBytes()).isEqualTo(planted)
        assertThat(vault.stored(VaultKeys.ZERO_KEY_REPAIR_ATTEMPTS)).isNull()
        assertThat(vault.stored(VaultKeys.ZERO_KEY_REPAIR_DONE)).isNull()
        assertThat(vault.stored(VaultKeys.ZERO_KEY_REPAIR_CURRENT)).isNull() // not taken for a crash
        assertThat(vault.vaultEntries().filter { it.endsWith(".tmp") }).isEmpty()

        assertThrows<VaultLockedException> { runBlocking { vault.repair.runOnce() } }
        assertThat(vault.service.unlock(PASSWORD)).isTrue()
        assertThat(vault.repair.runOnce()!!.repaired).containsExactly("a.txt")
    }

    @Test
    fun `a file broken for another reason is examined, counted, and left byte for byte`() = runBlocking<Unit> {
        val vault = olderVault()
        vault.files.import("sound.txt", 4, { "sain".byteInputStream() })
        plantZeroKey(vault, "damaged.bin", ByteArray(3_000) { (it * 7).toByte() })
        // A v2 magic over anything: no key opens it, the zero key included.
        val broken = File(vault.dirs.vault, "broken.bin.enc").apply { writeBytes("RFT2".toByteArray() + ByteArray(64) { 7 }) }
        val before = broken.readBytes()
        val result = vault.repair.runOnce()!!
        assertThat(result.scanned).isEqualTo(3)
        assertThat(result.repaired).containsExactly("damaged.bin")
        assertThat(result.unexamined).isEmpty()
        assertThat(broken.readBytes()).isEqualTo(before)
    }

    @Test
    fun `a vault older than the 2_12, which still reads v1, is repaired too`() = runBlocking<Unit> {
        val vault = olderVault()
        vault.plant(VaultKeys.V2_ONLY, false)
        vault.service.lock()
        assertThat(vault.service.unlock(PASSWORD)).isTrue()
        plantZeroKey(vault, "l.bin", "legacy".toByteArray())
        assertThat(vault.repair.runOnce()!!.repaired).containsExactly("l.bin")
        assertThat(vault.files.decryptToCache(File(vault.dirs.vault, "l.bin.enc")).readText()).isEqualTo("legacy")
    }

    @Test
    fun `two calls during the same unlock share one pass`() = runBlocking<Unit> {
        // On threads: on the caller's own, a pass would end before a second call could come.
        val vault = TestVault(root, dispatcher = Dispatchers.Default)
        vault.service.setup(PASSWORD)
        vault.raw.edit().remove("flutter." + VaultKeys.ZERO_KEY_REPAIR_DONE).commit()
        plantZeroKey(vault, "p.bin", byteArrayOf(1))
        // The second call comes while the first pass works on the file (its name just recorded).
        var second: Deferred<ZeroKeyRepair.Result?>? = null
        vault.raw.onCommit = { keys ->
            if ("flutter." + VaultKeys.ZERO_KEY_REPAIR_CURRENT in keys) {
                vault.raw.onCommit = null
                second = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) { vault.repair.runOnce() }
            }
        }
        val first = vault.repair.runOnce()
        assertThat(first!!.repaired).containsExactly("p.bin")
        assertThat(second!!.await()).isSameInstanceAs(first)
    }

    /** A vault from before the 2.15.3: never repaired. */
    private suspend fun olderVault(heap: Long = TestVault.ONE_BLOCK): TestVault {
        val vault = TestVault(root, heap)
        vault.service.setup(PASSWORD)
        vault.raw.edit().remove("flutter." + VaultKeys.ZERO_KEY_REPAIR_DONE).commit()
        return vault
    }

    /** What D1 wrote: a v2 file, right name in its AAD, under 32 zero bytes. */
    private fun plantZeroKey(vault: TestVault, name: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(VaultCrypto.NONCE_LEN) { 1 }
        val blob = VaultCrypto.encryptV2(plain, ByteArray(VaultCrypto.KEY_LEN), nonce, "$name.enc")
        File(vault.dirs.vault, "$name.enc").writeBytes(blob)
        return blob
    }

    private companion object {
        const val PASSWORD = "mot de passe"
    }
}
