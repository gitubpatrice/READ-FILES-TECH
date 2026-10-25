package com.readfilestech.read_files_tech.vault.format

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.GCMModeCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with the 128-bit tag appended — the one primitive of the vault formats — by two engines
 * that write the same bytes (docs D-023, measured on the S9):
 * - [seal] / [open]: the platform provider (Conscrypt on Android) in ONE block, 400-600 MB/s, but it
 *   keeps the whole input in memory until the end, in both directions;
 * - [SealingStream] / [OpeningStream]: BouncyCastle as a stream, about 23 MB/s, in constant memory.
 *
 * The caller picks by size ([fitsInOneBlock]). Both are checked against the Dart vectors and against
 * each other (GcmTest).
 */
object Gcm {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    private const val TAG_BITS = TAG_LEN * 8
    private const val CHUNK = 64 * 1024

    /** Three copies of the data (input, the provider's buffer, output) within a quarter of the heap. */
    private const val ONE_BLOCK_HEAP_SHARE = 12

    fun fitsInOneBlock(dataBytes: Long, maxHeapBytes: Long): Boolean = dataBytes <= maxHeapBytes / ONE_BLOCK_HEAP_SHARE

    fun seal(plain: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
        platform(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    /**
     * Opens the ciphertext‖tag that runs from [from] to the end of [sealed] — read in place, not copied.
     *
     * @throws VaultAuthenticationException when the tag does not match.
     */
    fun open(sealed: ByteArray, from: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray = try {
        platform(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed, from, sealed.size - from)
    } catch (e: AEADBadTagException) {
        throw VaultAuthenticationException(e)
    }

    /**
     * Encrypts what is written to it into [out]; [finish] appends the tag. Does NOT close [out]: the
     * caller syncs and publishes the file it belongs to.
     */
    class SealingStream(private val out: OutputStream, key: ByteArray, nonce: ByteArray, aad: ByteArray) : OutputStream() {
        private val gcm = streaming(true, key, nonce, aad)
        private val buffer = ByteArray(gcm.getUpdateOutputSize(CHUNK) + TAG_LEN)
        private var finished = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished) { "already finished" }
            var offset = off
            val end = off + len
            while (offset < end) {
                val n = minOf(CHUNK, end - offset)
                out.write(buffer, 0, gcm.processBytes(b, offset, n, buffer, 0))
                offset += n
            }
        }

        fun finish() {
            check(!finished) { "already finished" }
            finished = true
            out.write(buffer, 0, gcm.doFinal(buffer, 0))
            buffer.fill(0)
        }
    }

    /**
     * The plaintext of the ciphertext‖tag read from [input] up to its end.
     *
     * ⚠️ UNVERIFIED until the end: what [read] returns before the end-of-stream may come from a wrong
     * key or altered bytes. Reaching the end checks the tag and throws [VaultAuthenticationException]
     * on a mismatch. Nothing read from it may be published before that.
     */
    class OpeningStream(private val input: InputStream, key: ByteArray, nonce: ByteArray, aad: ByteArray) : InputStream() {
        private val gcm = streaming(false, key, nonce, aad)
        private val inBuffer = ByteArray(CHUNK)
        private val outBuffer = ByteArray(gcm.getOutputSize(CHUNK) + TAG_LEN)
        private var outStart = 0
        private var outEnd = 0
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            // Refill until there is output or the end: the tag is checked by the refill that meets the end.
            while (len > 0 && outStart == outEnd && !done) fill()
            val n = minOf(len, outEnd - outStart)
            System.arraycopy(outBuffer, outStart, b, off, n)
            outStart += n
            return if (n == 0 && len > 0) -1 else n
        }

        override fun available(): Int = outEnd - outStart

        override fun close() {
            outBuffer.fill(0)
            input.close()
        }

        private fun fill() {
            outStart = 0
            val read = input.read(inBuffer)
            outEnd = if (read == -1) {
                done = true
                try {
                    gcm.doFinal(outBuffer, 0)
                } catch (e: InvalidCipherTextException) {
                    throw VaultAuthenticationException(e)
                }
            } else {
                gcm.processBytes(inBuffer, 0, read, outBuffer, 0)
            }
        }
    }

    private const val BYTE_MASK = 0xFF

    private fun platform(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        checkSizes(key, nonce)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
    }

    private fun streaming(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): GCMModeCipher {
        checkSizes(key, nonce)
        return GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad))
        }
    }

    private fun checkSizes(key: ByteArray, nonce: ByteArray) {
        require(key.size == KEY_LEN) { "a vault key is $KEY_LEN bytes" }
        require(nonce.size == NONCE_LEN) { "a GCM nonce is $NONCE_LEN bytes" }
    }
}
