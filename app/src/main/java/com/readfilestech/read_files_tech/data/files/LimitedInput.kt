package com.readfilestech.read_files_tech.data.files

import java.io.EOFException
import java.io.InputStream

/**
 * The next [size] bytes of [input], and no more — a backup entry inside its payload, or a source file
 * whose size was announced before it is read. Ending before [size] is an [EOFException]: the data is
 * truncated, or the file changed under the operation.
 */
class LimitedInput(private val input: InputStream, size: Long) : InputStream() {
    private var left = size

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = when {
        len == 0 -> 0
        left == 0L -> -1
        else -> {
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n == -1) throw EOFException("$left bytes missing")
            left -= n
            n
        }
    }

    /** Consumes what the reader left of the [size] bytes; what passed through is wiped (it may be plaintext). */
    fun skipRest() {
        val sink = ByteArray(SKIP_CHUNK)
        try {
            while (left > 0) read(sink, 0, minOf(SKIP_CHUNK.toLong(), left).toInt())
        } finally {
            sink.fill(0)
        }
    }

    /** All [size] bytes were read and [input] ends there: a longer source changed during the operation. */
    fun ensureEnd() {
        if (left != 0L || input.read() != -1) throw EOFException("the source is not the announced size")
    }

    private companion object {
        const val SKIP_CHUNK = 64 * 1024
        const val BYTE_MASK = 0xFF
    }
}
