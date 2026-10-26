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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
    fun `a wrong password leaves the vault locked - no file of it can be read`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        val stored = vault.files.import("carte.txt", 19, { "4111 1111 1111 1111".byteInputStream() })
        vault.service.lock()
        assertThat(vault.service.unlock("wrong")).isFalse()
        assertThat(vault.session.unlocked.value).isFalse()
        // No key held: every operation refuses, none hands out a partial plaintext.
        assertThrows<VaultLockedException> { runBlocking { vault.files.decryptToCache(stored) } }
        assertThrows<VaultLockedException> { runBlocking { vault.files.export(stored, File(root, "out")) } }
        assertThat(File(root, "out").exists()).isFalse()
    }

    @Test
    fun `a wall-clock deadline moved into the past does not lift the lockout - the monotonic one holds`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        repeat(5) { vault.service.unlock("wrong") }
        // "Settings, Date & time": the wall deadline is long past; the monotonic one did not move.
        vault.plant(VaultKeys.LOCKOUT_UNTIL, 0L)
        assertThrows<VaultLockoutException> { runBlocking { vault.service.unlock(PASSWORD) } }
        assertThat(vault.session.unlocked.value).isFalse()
    }

    @Test
    fun `a reset leaves no unlock lockout behind - the next vault opens at once`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        vault.service.lock()
        repeat(5) { vault.service.unlock("wrong") } // the fifth arms it
        assertThat(vault.stored(VaultKeys.LOCKOUT_UNTIL_ELAPSED)).isNotNull()
        vault.service.reset()
        // V-L5 of the 2.15.x: checked after the reset itself, not only once a new setup cleared it.
        VaultKeys.UNLOCK_LOCKOUT.all.forEach { assertThat(vault.stored(it)).isNull() }
        vault.service.setup("after the reset")
        vault.service.lock()
        assertThat(vault.service.unlock("after the reset")).isTrue()
    }

    @Test
    fun `a reset erases the restore lockout too`() = runBlocking<Unit> {
        vault.service.setup(PASSWORD)
        repeat(5) { vault.lockouts.recordFailure(VaultKeys.BACKUP_LOCKOUT, vault.lockouts.now()) }
        assertThat(vault.stored("vault_backup_fails")).isEqualTo(5L)
        vault.service.reset()
        // The Dart keys spelt out: checked against VaultKeys' own list, a key that list forgot would
        // pass unseen (the reset test above iterates VAULT_STATE).
        val backupLockout = listOf(
            "vault_backup_fails",
            "vault_backup_lockout_until_ms",
            "vault_backup_lockout_until_elapsed",
            "vault_backup_lockout_armed_elapsed",
        )
        backupLockout.forEach { assertThat(vault.stored(it)).isNull() }
    }

    // ---------------------------------------------------------------------------------------------
    // Vaults of the Flutter app's past versions
    // ---------------------------------------------------------------------------------------------

    /** Null: no `vault_kdf_version` at all, as before 2.6.0 (the key came with Argon2id); "pbkdf2": the Dart test's. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["pbkdf2"])
    fun `a PBKDF2 vault of the 2_5_4, with a v1 sentinel, still opens with its password`(kdfVersion: String?) = runBlocking<Unit> {
        plantPbkdf2Vault(LEGACY_PASSWORD, kdfVersion)
        assertThat(vault.service.isSetUp()).isTrue()
        assertThat(vault.service.unlock(LEGACY_PASSWORD)).isTrue()
        assertThat(vault.session.unlocked.value).isTrue()
    }

    @Test
    fun `a PBKDF2 vault of the 2_5_4 still refuses a wrong password, and counts it`() = runBlocking<Unit> {
        plantPbkdf2Vault(LEGACY_PASSWORD)
        assertThat(vault.service.unlock("other")).isFalse()
        assertThat(vault.session.unlocked.value).isFalse()
        assertThat(vault.stored(VaultKeys.UNLOCK_FAILS)).isEqualTo(1L)
    }

    @Test
    fun `a file imported into a vault of the 2_5_4 is written as v2`() = runBlocking<Unit> {
        plantPbkdf2Vault(LEGACY_PASSWORD)
        vault.service.unlock(LEGACY_PASSWORD)
        val text = "écrit aujourd'hui".toByteArray()
        val stored = vault.files.import("nouveau.txt", text.size.toLong(), { text.inputStream() })
        // New writes are v2 even where v1 is still read: bound to their name by the AAD.
        assertThat(String(stored.readBytes().copyOf(4), Charsets.US_ASCII)).isEqualTo("RFT2")
        assertThat(vault.files.decryptToCache(stored).readBytes()).isEqualTo(text)
    }

    /**
     * A vault as the 2.5.4 left it, stated INDEPENDENTLY of the port's code (the JCA, not VaultKdf nor
     * VaultCrypto; the Dart keys spelt out): PBKDF2-HMAC-SHA256 × 600 000, a v1 sentinel (`nonce ‖
     * ciphertext ‖ tag`, empty AAD, no magic), no `vault_v2_only`. Built by the port's own code, it would
     * stay green the day that code changed format — the day it should turn red (`writeLegacyPbkdf2Vault`
     * of the Dart test).
     */
    private fun plantPbkdf2Vault(password: String, kdfVersion: String? = null) {
        val salt = ByteArray(16) { (it * 17 + 3).toByte() }
        val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val nonce = ByteArray(12) { (it * 31 + 7).toByte() }
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        vault.dirs.vault.mkdirs()
        File(vault.dirs.vault, "_check.enc").writeBytes(nonce + gcm.doFinal("read_files_tech_vault_v1".toByteArray()))
        vault.plant("vault_salt_v1", Base64.getEncoder().encodeToString(salt))
        kdfVersion?.let { vault.plant("vault_kdf_version", it) }
        vault.plant("vault_setup_v1", true)
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
        val leftovers =
            listOf("vault_decrypt/x/a.pdf", "pdf_scratch/PDFBox1.tmp", "share_plus/b.jpg", "share/c.txt", "exports/coffre.rftvault")
        leftovers.forEach { File(vault.dirs.cache, it).apply { parentFile.mkdirs() }.writeText("clear") }
        File(vault.dirs.cache, "thumbnails/keep.png").apply { parentFile.mkdirs() }.writeText("not the vault's")
        vault.service.lock()
        assertThat(vault.dirs.cache.list().orEmpty().toList()).containsExactly("thumbnails")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val PASSWORD = "Coffre été 🔐 2026"
        const val LEGACY_PASSWORD = "mot de passe historique"
        const val MINUTE = 60_000L
    }
}
