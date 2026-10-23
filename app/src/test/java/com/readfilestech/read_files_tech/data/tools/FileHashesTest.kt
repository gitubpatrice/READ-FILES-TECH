package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** The oracle is not the code: the digests of the FIPS 180 and RFC 1321 test vectors, as published. */
class FileHashesTest {
    private fun hashes(bytes: ByteArray) = runBlocking {
        FileHashes.of(ByteArrayInputStream(bytes))
    }.map { it.algorithm to it.hex }

    @Test
    fun `the published digests of the empty text and of abc`() {
        assertThat(hashes(ByteArray(0))).containsExactly(
            "MD5" to "d41d8cd98f00b204e9800998ecf8427e",
            "SHA-1" to "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            "SHA-256" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "SHA-512" to "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
        ).inOrder()
        assertThat(hashes("abc".toByteArray())).containsExactly(
            "MD5" to "900150983cd24fb0d6963f7d28e17f72",
            "SHA-1" to "a9993e364706816aba3e25717850c26c9cd0d89d",
            "SHA-256" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "SHA-512" to "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
        ).inOrder()
    }

    /** A million `a`: many pieces of 64 KiB and a last short one, all fed to every digest. */
    @Test
    fun `a million a, read by pieces, and the progress up to the last byte`() {
        val million = ByteArray(1_000_000) { 'a'.code.toByte() }
        val progress = mutableListOf<Long>()
        val digests = runBlocking {
            FileHashes.of(ByteArrayInputStream(million)) { progress += it }
        }.map { it.algorithm to it.hex }
        assertThat(digests).containsExactly(
            "MD5" to "7707d6ae4e027c70eea2a935c2296f21",
            "SHA-1" to "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
            "SHA-256" to "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            "SHA-512" to "e718483d0ce769644e2e42c7bc15b4638e1f98b13b2044285632a803afa973eb" +
                "de0ff244877ea60a4cb0432ce577c31beb009c5c2c49aa2e4eadb217ad8cc09b",
        ).inOrder()
        assertThat(progress.size).isGreaterThan(1)
        assertThat(progress).isInStrictOrder()
        assertThat(progress.last()).isEqualTo(1_000_000L)
    }

    /** Leaving the screen stops the reading: the 2.15.3's isolate read a large file to its end for nobody. */
    @Test
    fun `an endless file stops when the reading is cancelled`() {
        val over = AtomicBoolean(false)
        // Endless until the test ends: a reading deaf to the cancellation still stops then.
        val endless = object : InputStream() {
            override fun read(): Int = if (over.get()) -1 else 0

            override fun read(b: ByteArray, off: Int, len: Int): Int = if (over.get()) -1 else len
        }
        // A scope of its own, not runBlocking's: runBlocking would wait for a deaf child forever (it did,
        // the first time this test was sabotaged).
        val reading = CoroutineScope(Dispatchers.Default).async { FileHashes.of(endless) }
        try {
            runBlocking {
                delay(100)
                val stopped = withTimeoutOrNull(5_000) { reading.cancelAndJoin() }
                assertThat(stopped).isNotNull()
                assertThat(reading.isCancelled).isTrue()
            }
        } finally {
            over.set(true)
        }
    }

    /** The one streamed SHA-256 (the duplicate finder, the OCR's data, the CSV editor): a stop between two pieces. */
    @Test
    fun `the SHA-256 of a stream checks for a stop before each piece, and stops there`() {
        val pieces = 3
        val bytes = ByteArray(pieces * 64 * 1024 - 10) { it.toByte() }
        var checks = 0
        FileHashes.sha256(ByteArrayInputStream(bytes)) { checks++ }
        // Before each of the three pieces, and before the read that finds the end.
        assertThat(checks).isEqualTo(pieces + 1)

        var read = 0
        val counting = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { read += it }
        }
        val stopped = runCatching { FileHashes.sha256(counting) { if (read > 0) throw IllegalStateException("stop") } }
        assertThat(stopped.exceptionOrNull()).hasMessageThat().isEqualTo("stop")
        assertThat(read).isEqualTo(64 * 1024)
        assertThat(FileHashes.sha256("abc".byteInputStream()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
