package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.vault.format.VaultCrypto
import com.readfilestech.read_files_tech.vault.format.VaultKdf
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

class VaultServiceTest {
    @TempDir
    lateinit var root: File

    private val vault by lazy { TestVault(root) }

    // ---------------------------------------------------------------------------------------------
    // Setup — the keys and the order of the 2.15.3
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `setup writes the vault the way the 2_15_3 does, and opens it`() = runBlocking<Unit> {
        assertThat(vault.service.setup(PASSWORD)).isTrue()
        assertThat(vault.session.unlocked.value).isTrue()
        val salt = Base64.getDecoder().decode(vault.stored(VaultKeys.SALT) as String)
        assertThat(salt).hasLength(VaultKdf.SALT_LEN)
        assertThat(vault.stored(VaultKeys.KDF_VERSION)).isEqualTo("argon2id")
        // Integers as the plugin stores them: Long, never Int.
        assertThat(vault.stored(VaultKeys.ARGON2_MEMORY_KB)).isEqualTo(1_024L)
        assertThat(vault.stored(VaultKeys.ARGON2_ITERATIONS)).isEqualTo(1L)
        assertThat(vault.stored(VaultKeys.V2_ONLY)).isEqualTo(true)
        assertThat(vault.stored(VaultKeys.ZERO_KEY_REPAIR_DONE)).isEqualTo(true)
        assertThat(vault.stored(VaultKeys.SETUP)).isEqualTo(true)
        val key = VaultKdf.argon2id(PASSWORD, salt, 1_024, 1)
        val sentinel = File(vault.dirs.vault, VaultNames.SENTINEL_FILE).readBytes()
        assertThat(String(VaultCrypto.decryptAuto(sentinel, key, VaultNames.SENTINEL_FILE, v2Only = true)))
            .isEqualTo(VaultNames.SENTINEL_PLAIN)
    }

    @Test
    fun `a second setup is refused - it would replace the salt of the existing vault`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        val salt = vault.stored(VaultKeys.SALT)
        assertThrows<VaultAlreadySetUpException> { runBlocking { vault.service.setup("other") } }
        assertThat(vault.stored(VaultKeys.SALT)).isEqualTo(salt)
    }

    // ---------------------------------------------------------------------------------------------
    // Unlock and the lockout
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `wrong passwords are counted, the fifth arms one minute, then the right one opens and clears`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        repeat(4) { assertThat(vault.service.unlock("wrong")).isFalse() }
        assertThat(vault.stored(VaultKeys.UNLOCK_FAILS)).isEqualTo(4L)
        assertThat(vault.stored(VaultKeys.LOCKOUT_UNTIL)).isNull()
        assertThat(vault.service.unlock("wrong")).isFalse()
        assertThat(vault.stored(VaultKeys.LOCKOUT_UNTIL)).isEqualTo(vault.clock.wall + MINUTE)
        assertThat(vault.stored(VaultKeys.LOCKOUT_UNTIL_ELAPSED)).isEqualTo(vault.clock.mono + MINUTE)
        assertThat(vault.stored(VaultKeys.LOCKOUT_ARMED_ELAPSED)).isEqualTo(vault.clock.mono)
        // Even the right password waits: nothing is derived during a lockout.
        val refused = assertThrows<VaultLockoutException> { runBlocking { vault.service.unlock(PASSWORD) } }
        assertThat(refused.remainingMs).isEqualTo(MINUTE)
        vault.clock.advance(MINUTE)
        assertThat(vault.service.unlock(PASSWORD)).isTrue()
        VaultKeys.UNLOCK_LOCKOUT.all.forEach { assertThat(vault.stored(it)).isNull() }
    }

    @Test
    fun `D2 - after a reboot the lockout lasts its delay by the wall clock, not the previous uptime`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        vault.clock.mono = 10L * 24 * 3_600_000 // ten days of uptime when the lockout is armed
        repeat(5) { vault.service.unlock("wrong") }
        // Reboot: elapsedRealtime restarts near zero; the wall clock moved on by 10 s.
        vault.clock.mono = 5_000
        vault.clock.wall += 10_000
        val refused = assertThrows<VaultLockoutException> { runBlocking { vault.service.unlock(PASSWORD) } }
        assertThat(refused.remainingMs).isEqualTo(MINUTE - 10_000)
        vault.clock.wall += MINUTE
        assertThat(vault.service.unlock(PASSWORD)).isTrue()
    }

    @Test
    fun `a vault written by the Flutter app opens with its password, v2 and v1 files read`() = runBlocking<Unit> {
        val vectors = JSONObject(checkNotNull(VaultServiceTest::class.java.getResource("/vault-vectors-2.15.2.json")).readText())
        // The keys a 2.7.1+ Flutter vault holds, typed as the plugin wrote them; no v2_only (pre-2.12).
        vault.plant(VaultKeys.SALT, Base64.getEncoder().encodeToString(hex(vectors.getString("salt"))))
        vault.plant(VaultKeys.KDF_VERSION, "argon2id")
        vault.plant(VaultKeys.ARGON2_MEMORY_KB, 8_192L)
        vault.plant(VaultKeys.ARGON2_ITERATIONS, 2L)
        vault.plant(VaultKeys.SETUP, true)
        vault.dirs.vault.mkdirs()
        val plains = mutableMapOf<String, ByteArray>()
        val blobs = vectors.getJSONArray("blobs")
        for (i in 0 until blobs.length()) {
            val blob = blobs.getJSONObject(i)
            val name = blob.getString("filename")
            val wanted = (blob.getString("format") == "v2") == (name != "vide.txt.enc")
            if (!wanted) continue // v2 sentinel and report, v1 empty file
            File(vault.dirs.vault, name).writeBytes(hex(blob.getString("blob")))
            if (!VaultNames.isSentinel(name)) plains[VaultNames.plainName(name)] = hex(blob.getString("plain"))
        }
        assertThat(vault.service.unlock("Mot de passe é€ 🔑 ñ")).isTrue()
        val listed = vault.files.list()
        assertThat(listed.map { it.name }).containsExactly("Rapport été 2026.pdf.enc", "vide.txt.enc")
        for (file in listed) {
            val clear = vault.files.decryptToCache(file)
            assertThat(clear.readBytes()).isEqualTo(plains.getValue(clear.name))
        }
    }

    @Test
    fun `D-019 - a counter that cannot be read is a corrupted vault, not zero, and stays as it is`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        vault.plant(VaultKeys.UNLOCK_FAILS, "4")
        assertThrows<VaultCorruptedException> { runBlocking { vault.service.unlock("wrong") } }
        assertThat(vault.stored(VaultKeys.UNLOCK_FAILS)).isEqualTo("4")
    }

    @Test
    fun `an unknown KDF, a missing salt or sentinel are a corrupted vault, never a counted failure`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        vault.plant(VaultKeys.KDF_VERSION, "scrypt")
        assertThrows<VaultCorruptedException> { runBlocking { vault.service.unlock(PASSWORD) } }
        vault.plant(VaultKeys.KDF_VERSION, "argon2id")
        File(vault.dirs.vault, VaultNames.SENTINEL_FILE).delete()
        assertThrows<VaultCorruptedException> { runBlocking { vault.service.unlock(PASSWORD) } }
        vault.raw.edit().remove("flutter." + VaultKeys.SALT).commit()
        assertThrows<VaultCorruptedException> { runBlocking { vault.service.unlock(PASSWORD) } }
        assertThat(vault.stored(VaultKeys.UNLOCK_FAILS)).isNull()
    }

    @Test
    fun `a sentinel replaced by a user's file - what D25 and D27 left - is a corrupted vault, not counted`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        val salt = Base64.getDecoder().decode(vault.stored(VaultKeys.SALT) as String)
        val key = VaultKdf.argon2id(PASSWORD, salt, 1_024, 1)
        val nonce = ByteArray(12) { 5 }
        val usersFile = VaultCrypto.encryptV2("a file named _check".toByteArray(), key, nonce, VaultNames.SENTINEL_FILE)
        File(vault.dirs.vault, VaultNames.SENTINEL_FILE).writeBytes(usersFile)
        assertThrows<VaultCorruptedException> { runBlocking { vault.service.unlock(PASSWORD) } }
        assertThat(vault.session.unlocked.value).isFalse()
        assertThat(vault.stored(VaultKeys.UNLOCK_FAILS)).isNull()
    }

    @Test
    fun `a lock during the derivation - the right password does not open the vault behind the user`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        // The screen is left while the key is derived: the lock lands right before Argon2 runs, after
        // the lockout was checked — a check of the epoch only before deriving would not see it.
        vault.argon2.onCanAfford = {
            vault.argon2.onCanAfford = null
            vault.service.lock()
        }
        assertThrows<LockedDuringOperationException> { runBlocking { vault.service.unlock(PASSWORD) } }
        assertThat(vault.session.unlocked.value).isFalse()
        assertThat(vault.stored(VaultKeys.UNLOCK_FAILS)).isNull() // the password was right: not counted
        assertThat(vault.service.unlock(PASSWORD)).isTrue()
    }

    @Test
    fun `a lock during the setup's derivation - the vault is created, and stays locked`() = runBlocking<Unit> {
        vault.argon2.onNewVaultParams = { vault.service.lock() }
        assertThat(vault.service.setup(PASSWORD)).isFalse()
        assertThat(vault.session.unlocked.value).isFalse()
        assertThat(vault.service.isSetUp()).isTrue()
        vault.argon2.onNewVaultParams = null
        assertThat(vault.service.unlock(PASSWORD)).isTrue()
    }

    @Test
    fun `a failure the preferences file refuses to record is reported, not swallowed`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        vault.raw.failCommits = true
        assertThrows<VaultStorageException> { runBlocking { vault.service.unlock("wrong") } }
    }

    @Test
    fun `reset erases every key of the vault and its files, locked first`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.files.import("a.txt", 1, { byteArrayOf(1).inputStream() })
        repeat(2) { vault.lockouts.recordFailure(VaultKeys.BACKUP_LOCKOUT, vault.lockouts.now()) }
        vault.plant("theme_mode", "dark") // not the vault's: kept
        // Locked FIRST: at the moment the keys are erased, no operation can open any more.
        var openDuringErasure: Boolean? = null
        vault.raw.onCommit = {
            if (openDuringErasure == null) openDuringErasure = runCatching { vault.session.open().close() }.isSuccess
        }
        vault.service.reset()
        vault.raw.onCommit = null
        assertThat(openDuringErasure).isFalse()
        assertThat(vault.session.unlocked.value).isFalse()
        VaultKeys.VAULT_STATE.forEach { assertThat(vault.stored(it)).isNull() }
        assertThat(vault.stored("theme_mode")).isEqualTo("dark")
        assertThat(vault.vaultEntries()).isEmpty()
        assertThat(vault.service.isSetUp()).isFalse()
        assertThat(vault.service.setup("new")).isTrue()
    }

    @Test
    fun `a reset that cannot delete every file says so, instead of leaving them in the next vault`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        // A file that resists deletion: in a read-only folder (Linux, the CI), and held open (Windows).
        val stubborn = File(vault.dirs.vault, "stuck/file").apply { parentFile.mkdirs() }
        stubborn.writeText("resists")
        val held = stubborn.inputStream()
        stubborn.parentFile.setWritable(false)
        try {
            val probe = !File(stubborn.parentFile, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) }
            assumeTrue(probe || isWindows(), "no way to make a file resist deletion on this machine")
            assertThrows<VaultStorageException> { runBlocking { vault.service.reset() } }
            VaultKeys.VAULT_STATE.forEach { assertThat(vault.stored(it)).isNull() } // the keys went, and it is said
        } finally {
            stubborn.parentFile.setWritable(true)
            held.close()
        }
    }

    private fun isWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun `lock purges the plaintext the Flutter app and the port left in the cache`() = runBlocking<Unit> {
        val leftovers = listOf("vault_decrypt/x/a.pdf", "share_plus/b.jpg", "share/c.txt", "exports/coffre.rftvault")
        leftovers.forEach { File(vault.dirs.cache, it).apply { parentFile.mkdirs() }.writeText("clear") }
        File(vault.dirs.cache, "thumbnails/keep.png").apply { parentFile.mkdirs() }.writeText("not the vault's")
        vault.service.lock()
        assertThat(vault.dirs.cache.list().orEmpty().toList()).containsExactly("thumbnails")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val PASSWORD = "Coffre été 🔐 2026"
        const val MINUTE = 60_000L
    }
}
