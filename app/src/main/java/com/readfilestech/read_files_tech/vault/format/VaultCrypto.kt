package com.readfilestech.read_files_tech.vault.format

import com.readfilestech.read_files_tech.data.DartText
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** A vault blob or backup whose STRUCTURE is wrong: not a wrong password. */
class VaultFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES-GCM refused the tag: wrong key, wrong file name in the AAD, or altered bytes. [cause] is the
 * engine's own signal (Gcm: Conscrypt's `AEADBadTagException`, BouncyCastle's `InvalidCipherTextException`).
 */
class VaultAuthenticationException(cause: Exception) : Exception("authentication failed", cause)

/**
 * The vault file format of Read Files Tech, byte for byte the one of the Flutter app
 * (`lib/services/vault/vault_blob.dart`, unchanged from 2.12 to 2.15.3), checked against vectors
 * produced by that Dart code (VaultFormatVectorsTest).
 *
 * - v2: `"RFT2" ‖ nonce(12) ‖ ciphertext ‖ tag(16)`, AAD = UTF-8 of `"rft-vault-v2|" + file name`:
 *   a file renamed on disk no longer decrypts.
 * - v1 (≤ 2.5.4): `nonce(12) ‖ ciphertext ‖ tag(16)`, empty AAD. Refused on a vault marked
 *   `vault_v2_only` (created since 2.12.0), where a v1 blob can only be a substitution.
 *
 * Each operation exists in one block (fast, whole data in memory) and as a stream (constant memory):
 * same bytes, the caller picks by size (docs D-023, Gcm).
 */
object VaultCrypto {
    const val KEY_LEN = Gcm.KEY_LEN
    const val NONCE_LEN = Gcm.NONCE_LEN
    const val TAG_LEN = Gcm.TAG_LEN

    private val MAGIC_V2 = "RFT2".toByteArray(Charsets.US_ASCII)
    private const val AAD_PREFIX = "rft-vault-v2|"

    /** What a v2 blob adds to its plaintext; a v1 one adds [V1_OVERHEAD]. */
    const val V2_OVERHEAD = 4 + NONCE_LEN + TAG_LEN
    const val V1_OVERHEAD = NONCE_LEN + TAG_LEN

    /** Dart's `utf8.encode` of the prefix and the name: a lone surrogate is U+FFFD there too (DartText). */
    fun aad(fileName: String): ByteArray = DartText.utf8(AAD_PREFIX + fileName)

    fun encryptV2(plain: ByteArray, key: ByteArray, nonce: ByteArray, fileName: String): ByteArray =
        MAGIC_V2 + nonce + Gcm.seal(plain, key, nonce, aad(fileName))

    /** The same file as [encryptV2], from [input] to [out], in constant memory. [out] is not closed. */
    fun encryptV2(input: InputStream, out: OutputStream, key: ByteArray, nonce: ByteArray, fileName: String) {
        val sealing = encryptingV2(out, key, nonce, fileName)
        pump(input, sealing)
        sealing.finish()
    }

    /**
     * Writes the v2 header to [out] and returns the stream the plaintext goes into; its `finish()`
     * appends the tag. For a producer that writes rather than one that can be read.
     */
    fun encryptingV2(out: OutputStream, key: ByteArray, nonce: ByteArray, fileName: String): Gcm.SealingStream {
        val sealing = Gcm.SealingStream(out, key, nonce, aad(fileName))
        out.write(MAGIC_V2)
        out.write(nonce)
        return sealing
    }

    /**
     * `isV2Format` of the 2.15.x, for a blob of [length] bytes whose first bytes are [head]: long enough
     * for magic, nonce and tag, and starting with the magic.
     */
    fun isV2(length: Long, head: ByteArray): Boolean =
        length >= V2_OVERHEAD && head.size >= MAGIC_V2.size && head.copyOfRange(0, MAGIC_V2.size).contentEquals(MAGIC_V2)

    /**
     * `decryptAuto` of `vault_blob.dart`: v2 when the magic is there, v1 otherwise unless [v2Only].
     *
     * @throws VaultFormatException a v1 blob on a v2-only vault, or a blob too short to be either.
     * @throws VaultAuthenticationException the tag does not match.
     */
    fun decryptAuto(blob: ByteArray, key: ByteArray, fileName: String, v2Only: Boolean): ByteArray {
        val start = MAGIC_V2.size
        if (isV2(blob.size.toLong(), blob.copyOfRange(0, minOf(blob.size, start)))) {
            return Gcm.open(blob, start + NONCE_LEN, key, blob.copyOfRange(start, start + NONCE_LEN), aad(fileName))
        }
        checkV1(blob.size.toLong(), v2Only)
        return Gcm.open(blob, NONCE_LEN, key, blob.copyOfRange(0, NONCE_LEN), ByteArray(0))
    }

    /**
     * [decryptAuto] from [input], whose [length] is the blob's, into [out], in constant memory.
     *
     * ⚠️ [out] receives UNVERIFIED bytes until this returns: on [VaultAuthenticationException] what it
     * received must be discarded (written to a temporary file, never published).
     */
    @Suppress("LongParameterList") // the blob, where its plaintext goes, and the three things that open it
    fun decryptAuto(input: InputStream, length: Long, out: OutputStream, key: ByteArray, fileName: String, v2Only: Boolean) {
        val data = DataInputStream(input)
        val head = ByteArray(minOf(length, MAGIC_V2.size.toLong()).toInt())
        readFully(data, head)
        val nonce = ByteArray(NONCE_LEN)
        val aad = if (isV2(length, head)) {
            readFully(data, nonce)
            aad(fileName)
        } else {
            checkV1(length, v2Only)
            head.copyInto(nonce)
            readFully(data, nonce, head.size)
            ByteArray(0)
        }
        Gcm.OpeningStream(data, key, nonce, aad).use { pump(it, out) }
    }

    /** The plaintext size of a well-formed blob of [blobLength] bytes, starting with [head] (its first 4 bytes). */
    fun plainLength(blobLength: Long, head: ByteArray): Long =
        blobLength - if (isV2(blobLength, head)) V2_OVERHEAD else V1_OVERHEAD

    private fun checkV1(length: Long, v2Only: Boolean) {
        if (v2Only) throw VaultFormatException("not a v2 blob, on a v2-only vault")
        if (length < V1_OVERHEAD) throw VaultFormatException("blob too short")
    }
}

/** `copyTo` with a buffer wiped at the end: it carries plaintext, and `copyTo`'s stays out of reach. */
private fun pump(input: InputStream, out: OutputStream) {
    val buffer = ByteArray(COPY_CHUNK)
    try {
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
        }
    } finally {
        buffer.fill(0)
    }
}

private const val COPY_CHUNK = 64 * 1024

private fun readFully(data: DataInputStream, into: ByteArray, offset: Int = 0) {
    try {
        data.readFully(into, offset, into.size - offset)
    } catch (e: EOFException) {
        throw VaultFormatException("blob shorter than its length", e)
    }
}
