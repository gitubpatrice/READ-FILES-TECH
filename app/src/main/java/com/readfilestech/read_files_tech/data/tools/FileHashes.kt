package com.readfilestech.read_files_tech.data.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
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

    /**
     * The SHA-256 of everything [input] gives, in lower-case hex, read by pieces — never the whole in
     * memory. [checkpoint] runs before each piece: where a caller checks for a stop. The one such loop of
     * the app: the duplicate finder's full digest, the OCR's trained data, the CSV editor's file, the move
     * of the 2.15.x's extractions.
     */
    fun sha256(input: InputStream, checkpoint: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(PIECE)
        while (true) {
            checkpoint()
            val n = input.read(buffer)
            if (n == -1) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().toHex()
    }

    /** [sha256] of [file]'s content. */
    fun sha256(file: File, checkpoint: () -> Unit = {}): String = FileInputStream(file).use { sha256(it, checkpoint) }
}

/** A digest in lower-case hex, as the hash tool and the duplicate finder show and compare them. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE) }

private const val BYTE = 0xFF
