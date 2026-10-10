package com.readfilestech.read_files_tech.vault.format

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A vault blob or backup whose STRUCTURE is wrong: not a wrong password. */
class VaultFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** AES-GCM refused the tag: wrong key, wrong file name in the AAD, or altered bytes. */
class VaultAuthenticationException(cause: AEADBadTagException) : Exception("authentication failed", cause)

/**
 * The vault file format of Read Files Tech, byte for byte the one of the Flutter app
 * (`lib/services/vault/vault_blob.dart`, unchanged from 2.12 to 2.15.3), checked against vectors
 * produced by that Dart code (VaultFormatVectorsTest).
 *
 * - v2: `"RFT2" ‖ nonce(12) ‖ ciphertext ‖ tag(16)`, AAD = UTF-8 of `"rft-vault-v2|" + file name`:
 *   a file renamed on disk no longer decrypts.
 * - v1 (≤ 2.5.4): `nonce(12) ‖ ciphertext ‖ tag(16)`, empty AAD. Refused on a vault marked
 *   `vault_v2_only` (created since 2.12.0), where a v1 blob can only be a substitution.
 */
object VaultCrypto {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16

    private val MAGIC_V2 = "RFT2".toByteArray(Charsets.US_ASCII)
    private const val AAD_PREFIX = "rft-vault-v2|"
    private const val TAG_BITS = TAG_LEN * 8

    fun aad(fileName: String): ByteArray = (AAD_PREFIX + fileName).toByteArray(Charsets.UTF_8)

    /** AES-256-GCM, the 128-bit tag appended — the layout of `gcmProcess` in Dart. */
    fun gcmEncrypt(plain: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    /** @throws VaultAuthenticationException when the tag does not match. */
    fun gcmDecrypt(sealed: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
    } catch (e: AEADBadTagException) {
        throw VaultAuthenticationException(e)
    }

    fun encryptV2(plain: ByteArray, key: ByteArray, nonce: ByteArray, fileName: String): ByteArray {
        require(nonce.size == NONCE_LEN) { "a GCM nonce is $NONCE_LEN bytes" }
        return MAGIC_V2 + nonce + gcmEncrypt(plain, key, nonce, aad(fileName))
    }

    fun isV2(blob: ByteArray): Boolean =
        blob.size >= MAGIC_V2.size + NONCE_LEN + TAG_LEN && blob.copyOfRange(0, MAGIC_V2.size).contentEquals(MAGIC_V2)

    /**
     * `decryptAuto` of `vault_blob.dart`: v2 when the magic is there, v1 otherwise unless [v2Only].
     *
     * @throws VaultFormatException a v1 blob on a v2-only vault, or a blob too short to be either.
     * @throws VaultAuthenticationException the tag does not match.
     */
    fun decryptAuto(blob: ByteArray, key: ByteArray, fileName: String, v2Only: Boolean): ByteArray {
        if (isV2(blob)) {
            val start = MAGIC_V2.size
            return gcmDecrypt(
                blob.copyOfRange(start + NONCE_LEN, blob.size),
                key,
                blob.copyOfRange(start, start + NONCE_LEN),
                aad(fileName),
            )
        }
        if (v2Only) throw VaultFormatException("not a v2 blob, on a v2-only vault")
        if (blob.size < NONCE_LEN + TAG_LEN) throw VaultFormatException("blob too short")
        return gcmDecrypt(blob.copyOfRange(NONCE_LEN, blob.size), key, blob.copyOfRange(0, NONCE_LEN), ByteArray(0))
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == KEY_LEN) { "a vault key is $KEY_LEN bytes" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
    }
}
