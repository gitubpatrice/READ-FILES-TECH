package com.readfilestech.read_files_tech.vault.format

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The two engines of the vault's AES-GCM (docs D-023) write and read the same bytes — at every boundary
 * of the 16-byte block and of the stream's 64 KiB chunk, where a streaming implementation goes wrong
 * first. The Dart vectors check both against the Flutter app (VaultFormatVectorsTest).
 */
class GcmTest {
    private val key = ByteArray(Gcm.KEY_LEN) { (it * 3 + 1).toByte() }
    private val nonce = ByteArray(Gcm.NONCE_LEN) { (it * 5 + 2).toByte() }
    private val aad = "rft-vault-v2|test.enc".toByteArray()

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 15, 16, 17, 65_535, 65_536, 65_537, 200_003])
    fun `the stream seals exactly what the block seals, and each opens the other`(size: Int) {
        val plain = ByteArray(size) { (it * 31 + 7).toByte() }
        val block = Gcm.seal(plain, key, nonce, aad)
        val streamed = ByteArrayOutputStream().also { out ->
            val sealing = Gcm.SealingStream(out, key, nonce, aad)
            sealing.write(plain)
            sealing.finish()
        }.toByteArray()
        assertThat(streamed).isEqualTo(block)
        assertThat(Gcm.OpeningStream(ByteArrayInputStream(block), key, nonce, aad).readBytes()).isEqualTo(plain)
        assertThat(Gcm.open(streamed, 0, key, nonce, aad)).isEqualTo(plain)
        // Read in place from an offset, as the vault formats do after their header.
        assertThat(Gcm.open(byteArrayOf(9, 9, 9) + streamed, 3, key, nonce, aad)).isEqualTo(plain)
    }

    @Test
    fun `written in odd pieces, the stream still seals the same bytes`() {
        val plain = ByteArray(150_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val sealing = Gcm.SealingStream(out, key, nonce, aad)
        var offset = 0
        for (piece in generateSequence(1) { it * 3 + 1 }) {
            val n = minOf(piece, plain.size - offset)
            if (n == 0) break
            sealing.write(plain, offset, n)
            offset += n
        }
        sealing.write(0x41)
        sealing.finish()
        assertThat(out.toByteArray()).isEqualTo(Gcm.seal(plain + 0x41.toByte(), key, nonce, aad))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 70_000, 140_015])
    fun `one altered byte anywhere fails the stream at its end, not before`(position: Int) {
        val sealed = Gcm.seal(ByteArray(140_000) { it.toByte() }, key, nonce, aad)
        sealed[position] = (sealed[position].toInt() xor 1).toByte()
        val opening = Gcm.OpeningStream(ByteArrayInputStream(sealed), key, nonce, aad)
        // Everything but the tag check comes out: what was read is UNVERIFIED, which callers must respect.
        val chunk = ByteArray(1_000)
        var read = 0L
        val failure = assertThrows<VaultAuthenticationException> {
            while (true) {
                val n = opening.read(chunk)
                if (n == -1) break
                read += n
            }
        }
        assertThat(failure).isNotNull()
        assertThat(read).isAtLeast(139_000L)
    }

    @Test
    fun `a wrong key, a wrong AAD and a truncated input all fail`() {
        val sealed = Gcm.seal(ByteArray(1_000), key, nonce, aad)
        val wrongKey = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows<VaultAuthenticationException> {
            drain(Gcm.OpeningStream(ByteArrayInputStream(sealed), wrongKey, nonce, aad))
        }
        assertThrows<VaultAuthenticationException> {
            drain(Gcm.OpeningStream(ByteArrayInputStream(sealed), key, nonce, "x".toByteArray()))
        }
        assertThrows<VaultAuthenticationException> {
            drain(Gcm.OpeningStream(ByteArrayInputStream(sealed.copyOf(sealed.size - 1)), key, nonce, aad))
        }
        assertThrows<VaultAuthenticationException> { Gcm.open(sealed, 0, wrongKey, nonce, aad) }
        // Shorter than a tag: refused too, by the stream as by the block.
        assertThrows<VaultAuthenticationException> {
            drain(Gcm.OpeningStream(ByteArrayInputStream(ByteArray(5)), key, nonce, aad))
        }
    }

    @Test
    fun `the one-block budget is a twelfth of the heap`() {
        assertThat(Gcm.fitsInOneBlock(21L * MB, 256L * MB)).isTrue()
        assertThat(Gcm.fitsInOneBlock(22L * MB, 256L * MB)).isFalse()
    }

    private fun drain(input: InputStream) {
        val chunk = ByteArray(4_096)
        while (input.read(chunk) != -1) Unit
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
