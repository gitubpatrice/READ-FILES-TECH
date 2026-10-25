package com.readfilestech.read_files_tech.vault.format

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The key derivations of the Flutter vault (`lib/services/vault/vault_kdf.dart`), byte-compatible:
 * same password encoding (UTF-8), same parameters, Argon2 version 0x13, one lane. Checked against
 * 14 keys derived by that Dart code (VaultFormatVectorsTest).
 */
object VaultKdf {
    const val SALT_LEN = 16

    /** PBKDF2-HMAC-SHA256, vaults created before 2.6.0 (`vault_kdf_version` absent or `pbkdf2`). */
    const val PBKDF2_ITERATIONS = 600_000

    /** What a 2.6.0–2.7.0 vault used when `vault_argon2_mem_kb` / `_iter` are absent. */
    const val ARGON2_LEGACY_MEMORY_KB = 16_384
    const val ARGON2_LEGACY_ITERATIONS = 4

    /** The fixed parameters of every `.rftvault` the Flutter app wrote (v1 implies them, v2 states them). */
    const val ARGON2_EXPORT_MEMORY_KB = 16_384
    const val ARGON2_EXPORT_ITERATIONS = 4

    private const val ARGON2_LANES = 1
    private const val BITS_PER_BYTE = 8

    fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        try {
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(passwordBytes, salt, PBKDF2_ITERATIONS)
            return (generator.generateDerivedParameters(VaultCrypto.KEY_LEN * BITS_PER_BYTE) as KeyParameter).key
        } finally {
            passwordBytes.fill(0)
        }
    }

    fun argon2id(password: String, salt: ByteArray, memoryKB: Int, iterations: Int): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memoryKB)
                .withIterations(iterations)
                .withParallelism(ARGON2_LANES)
                .build()
            val out = ByteArray(VaultCrypto.KEY_LEN)
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passwordBytes, out)
            return out
        } finally {
            passwordBytes.fill(0)
        }
    }
}
