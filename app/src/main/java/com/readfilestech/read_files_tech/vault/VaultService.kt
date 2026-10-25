package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException
import com.readfilestech.read_files_tech.vault.format.VaultCrypto
import com.readfilestech.read_files_tech.vault.format.VaultFormatException
import com.readfilestech.read_files_tech.vault.format.VaultKdf
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creating, opening, locking and resetting the vault of the Flutter app, in place: its salt, its KDF
 * parameters and its sentinel `_check.enc` are read where the 2.15.x wrote them (VaultKeys, docs D-003).
 * Port of `VaultService.setupWithPassword` / `unlockWithPassword` / `lock` / `reset` (2.15.3).
 *
 * Files, backups and the zero-key repair are in VaultFiles, VaultBackups and ZeroKeyRepair, which open
 * their operations on the same VaultSession.
 */
@Singleton
class VaultService @Inject constructor(
    private val prefs: FlutterPreferences,
    private val dirs: VaultDirs,
    private val session: VaultSession,
    private val lockouts: VaultLockoutStore,
    private val cipher: VaultCipher,
    private val argon2: Argon2Policy,
    private val storedKdf: StoredKdf,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val background = CoroutineScope(SupervisorJob() + dispatcher)

    /** Setup and reset one at a time: interleaved, they leave a vault whose sentinel or flags belong to the other. */
    private val transitions = Mutex()

    val unlocked: StateFlow<Boolean> get() = session.unlocked

    /** @throws VaultCorruptedException the setup flag cannot be read. */
    fun isSetUp(): Boolean = prefs.boolean(VaultKeys.SETUP).orCorrupted(VaultKeys.SETUP) == true

    /**
     * Creates the vault (Argon2id at this device's parameters, docs D-007) and opens it — unless a lock
     * happened during the derivation: the vault is then created but stays locked.
     *
     * Order kept from the 2.15.3: parameters, then the sentinel, then the flags in one commit, the setup
     * flag among them, so a process killed half-way leaves a vault that does not exist yet.
     *
     * @return whether the vault is open.
     * @throws VaultAlreadySetUpException a second setup would replace the salt of the existing vault.
     */
    suspend fun setup(password: String): Boolean = withContext(dispatcher) {
        transitions.withLock { create(password) }
    }

    private fun create(password: String): Boolean {
        if (isSetUp()) throw VaultAlreadySetUpException()
        val startEpoch = session.epoch()
        val params = argon2.newVaultParams()
        val salt = cipher.randomBytes(VaultKdf.SALT_LEN)
        val key = VaultKdf.argon2id(password, salt, params.memoryKB, params.iterations)
        var handedOver = false
        return try {
            prefs.commitOrThrow("the vault parameters") {
                putString(VaultKeys.SALT, Base64.getEncoder().encodeToString(salt))
                putString(VaultKeys.KDF_VERSION, VaultKeys.KDF_ARGON2ID)
                putLong(VaultKeys.ARGON2_MEMORY_KB, params.memoryKB.toLong())
                putLong(VaultKeys.ARGON2_ITERATIONS, params.iterations.toLong())
                // A new vault does not inherit the old one's lockout (the 2.15.3 clears it here too).
                VaultKeys.UNLOCK_LOCKOUT.all.forEach(::remove)
            }
            dirs.vault.mkdirs()
            val sentinel = VaultCrypto.encryptV2(
                VaultNames.SENTINEL_PLAIN.toByteArray(Charsets.UTF_8),
                key,
                cipher.randomBytes(VaultCrypto.NONCE_LEN),
                VaultNames.SENTINEL_FILE,
            )
            AtomicFiles.writeBytes(File(dirs.vault, VaultNames.SENTINEL_FILE), sentinel)
            prefs.commitOrThrow("the vault flags") {
                // A new vault refuses the v1 fallback, and has no file a zeroed key could have written.
                putBoolean(VaultKeys.V2_ONLY, true)
                putBoolean(VaultKeys.ZERO_KEY_REPAIR_DONE, true)
                putBoolean(VaultKeys.SETUP, true)
            }
            handedOver = true
            session.install(key, newV2Only = true, startEpoch)
        } finally {
            if (!handedOver) key.fill(0)
        }
    }

    /**
     * Opens the vault with [password]. False on a wrong password, which counts towards the lockout.
     *
     * @throws VaultLockoutException too many failures: nothing is derived.
     * @throws VaultCorruptedException salt or sentinel missing, sentinel of the wrong content, unknown
     *   KDF, a key that cannot be read — none of them counted as a failure.
     * @throws LockedDuringOperationException the right password, but a lock came during the derivation.
     */
    suspend fun unlock(password: String): Boolean = withContext(dispatcher) {
        transitions.withLock { open(password) }
    }

    /** One attempt at a time: each one counted, none racing a setup or a reset. */
    private fun open(password: String): Boolean {
        val startEpoch = session.epoch()
        val attempt = lockouts.now()
        lockouts.ensureFree(VaultKeys.UNLOCK_LOCKOUT, attempt)
        val key = storedKdf.derive(password)
        var handedOver = false
        return try {
            val v2Only = prefs.boolean(VaultKeys.V2_ONLY).orCorrupted(VaultKeys.V2_ONLY) ?: false
            if (!openSentinel(key, v2Only)) {
                lockouts.recordFailure(VaultKeys.UNLOCK_LOCKOUT, attempt)
                return false
            }
            // The password was right, whatever comes next. A file refusing this write only keeps the
            // old count, which the next success clears.
            lockouts.clear(VaultKeys.UNLOCK_LOCKOUT)
            handedOver = true
            if (!session.install(key, v2Only, startEpoch)) throw LockedDuringOperationException()
            true
        } finally {
            if (!handedOver) key.fill(0)
        }
    }

    /** Locks the vault: the key is wiped, running operations stop before writing, the plaintext cache is purged. */
    fun lock() {
        session.lock()
        background.launch { purgeDecrypted() }
    }

    /**
     * Erases the vault: its keys, every file, the lockouts and the repair state. Locks FIRST, before the
     * first write, so an import running now cannot finish under the old key into the new vault (GPT
     * review of the 2.15.3).
     *
     * @throws VaultStorageException the preferences refused the erasure: the files are left in place.
     */
    suspend fun reset() = withContext(dispatcher) {
        lock() // at once, before waiting for a setup that may be running: it then stays locked
        transitions.withLock {
            lock()
            prefs.commitOrThrow("the vault erasure") { VaultKeys.VAULT_STATE.forEach(::remove) }
            // A write started before the lock may land during the deletion: one more pass then. What
            // still resists would sit, unreadable, in the next vault: said, not hidden.
            val deleted = FileTrees.delete(dirs.vault) || FileTrees.delete(dirs.vault)
            dirs.vault.mkdirs()
            if (!deleted) throw VaultStorageException("files of the erased vault could not be deleted")
        }
    }

    /**
     * Deletes the plaintext copies left in the cache: `vault_decrypt/` and `vault_shared/` (VaultFiles),
     * and what the Flutter app left there — `share_plus/` (the plugin copies every shared file), `share/` (an older
     * name), `exports/` (the old staging of `.rftvault` exports). On lock, on panic and at startup;
     * never on a mere trip to the background, which would delete a file under the app receiving it.
     */
    suspend fun purgeDecrypted() = withContext(dispatcher) {
        PLAINTEXT_CACHE_DIRS.forEach { FileTrees.delete(File(dirs.cache, it)) }
    }

    /**
     * True when [key] opens the sentinel to its known text, false on a refused tag (a wrong password).
     *
     * @throws VaultCorruptedException the sentinel is missing, a v1 blob on a v2-only vault, or opens
     *   to another text: a vault problem, not a wrong password.
     */
    private fun openSentinel(key: ByteArray, v2Only: Boolean): Boolean {
        val plain = try {
            VaultCrypto.decryptAuto(sentinelBytes(), key, VaultNames.SENTINEL_FILE, v2Only)
        } catch (expected: VaultAuthenticationException) {
            return false // the wrong password: the outcome this check exists for
        } catch (e: VaultFormatException) {
            throw VaultCorruptedException("sentinel: ${e.message}", e)
        }
        val known = VaultNames.SENTINEL_PLAIN.toByteArray(Charsets.UTF_8)
        if (!plain.contentEquals(known)) throw VaultCorruptedException("sentinel of unexpected content")
        return true
    }

    private fun sentinelBytes(): ByteArray {
        val file = File(dirs.vault, VaultNames.SENTINEL_FILE)
        if (!file.isFile) throw VaultCorruptedException("sentinel missing")
        return file.readBytes()
    }

    companion object {
        /**
         * The cache folders that may hold vault plaintext: purged here, and never served to another app by
         * PathGuard — `vault_shared/` leaves only through the share the user asked for (docs D-034).
         */
        val PLAINTEXT_CACHE_DIRS = listOf(VaultFiles.DECRYPT_DIR, VaultFiles.SHARE_DIR, "share_plus", "share", "exports")
    }
}
