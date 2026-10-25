package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.vault.format.Argon2Params
import com.readfilestech.read_files_tech.vault.format.VaultBackupFormat
import com.readfilestech.read_files_tech.vault.format.VaultKdf
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The master key at the salt, KDF and parameters stored with the vault — each Flutter period's:
 * - `vault_kdf_version` absent or `pbkdf2`: PBKDF2-HMAC-SHA256, 600 000 rounds (before 2.6.0);
 * - `argon2id` without parameters: 16 MiB × 4 (2.6.0–2.7.0);
 * - `argon2id` with `vault_argon2_mem_kb` / `_iter`: calibrated at setup (2.7.1 on, and the port).
 */
@Singleton
class StoredKdf @Inject constructor(
    private val prefs: FlutterPreferences,
    private val argon2: Argon2Policy,
) {
    /**
     * @throws VaultCorruptedException salt missing or not Base64, an unknown KDF, parameters out of
     *   bounds or beyond this device, a key that cannot be read.
     */
    fun derive(password: String): ByteArray {
        val salt = readSalt()
        return when (val kdf = prefs.string(VaultKeys.KDF_VERSION).orCorrupted(VaultKeys.KDF_VERSION)) {
            null, VaultKeys.KDF_PBKDF2 -> VaultKdf.pbkdf2(password, salt)
            VaultKeys.KDF_ARGON2ID -> {
                val params = storedArgon2Params()
                VaultKdf.argon2id(password, salt, params.memoryKB, params.iterations)
            }
            // The 2.15.x took anything else for PBKDF2, then counted the right password as wrong.
            else -> throw VaultCorruptedException("unknown KDF \"$kdf\"")
        }
    }

    private fun readSalt(): ByteArray {
        val encoded = prefs.string(VaultKeys.SALT).orCorrupted(VaultKeys.SALT)
            ?: throw VaultCorruptedException("salt missing")
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw VaultCorruptedException("salt is not Base64", e)
        }
    }

    private fun storedArgon2Params(): Argon2Params {
        val memory = prefs.long(VaultKeys.ARGON2_MEMORY_KB).orCorrupted(VaultKeys.ARGON2_MEMORY_KB)
            ?: VaultKdf.ARGON2_LEGACY_MEMORY_KB.toLong()
        val iterations = prefs.long(VaultKeys.ARGON2_ITERATIONS).orCorrupted(VaultKeys.ARGON2_ITERATIONS)
            ?: VaultKdf.ARGON2_LEGACY_ITERATIONS.toLong()
        val memoryRange = VaultBackupFormat.ARGON2_MEMORY_KB_RANGE
        val iterationsRange = VaultBackupFormat.ARGON2_ITERATIONS_RANGE
        val inBounds = memory in memoryRange.first.toLong()..memoryRange.last.toLong() &&
            iterations in iterationsRange.first.toLong()..iterationsRange.last.toLong()
        if (!inBounds) throw VaultCorruptedException("Argon2 parameters out of bounds: $memory KiB × $iterations")
        val params = Argon2Params(memory.toInt(), iterations.toInt())
        if (!argon2.canAfford(params)) throw VaultCorruptedException("Argon2 parameters beyond this device: $memory KiB")
        return params
    }
}
