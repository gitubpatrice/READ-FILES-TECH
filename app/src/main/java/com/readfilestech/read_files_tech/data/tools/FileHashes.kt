package com.readfilestech.read_files_tech.data.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

/** One digest of a file: its algorithm's name as shown ("SHA-256") and its value in lower-case hex. */
data class FileHash(val algorithm: String, val hex: String)

/**
 * The hash tool's digests (`HashScreen` of the 2.15.3): MD5, SHA-1, SHA-256 and SHA-512 of a file in
 * ONE pass, read by pieces — never the file in memory, whatever its size, so no cap. Cancellable between
 * two pieces: the 2.15.3 ran it in an isolate nobody could stop, and went on after the screen was left.
 */
object FileHashes {
    /** In the 2.15.3's order. */
    val ALGORITHMS = listOf("MD5", "SHA-1", "SHA-256", "SHA-512")

    private const val PIECE = 64 * 1024

    /**
     * The digests of everything [input] gives. [onProgress] gets the bytes read so far after each piece.
     */
    suspend fun of(input: InputStream, onProgress: (Long) -> Unit = {}): List<FileHash> {
        val digests = ALGORITHMS.map(MessageDigest::getInstance)
        val buffer = ByteArray(PIECE)
        var read = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n == -1) break
            digests.forEach { it.update(buffer, 0, n) }
            read += n
            onProgress(read)
        }
        return ALGORITHMS.zip(digests) { name, digest -> FileHash(name, digest.digest().toHex()) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE) }

    private const val BYTE = 0xFF
}
