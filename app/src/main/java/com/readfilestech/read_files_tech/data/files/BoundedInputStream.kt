package com.readfilestech.read_files_tech.data.files

import java.io.FilterInputStream
import java.io.InputStream

/**
 * At most [cap] bytes of [input]; one byte more is a [FileTooLargeException]. For a viewer, which must
 * never show a part of a file as if it were the whole: a file grown past its cap since its size was
 * read is refused, where [CappedInputStream] ends quietly (a preview may stop early, a viewer may not).
 * Closing it closes [input].
 */
class BoundedInputStream(input: InputStream, private val cap: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val byte = super.read()
        if (byte != -1) counted(1)
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) counted(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) counted(skipped)
        return skipped
    }

    override fun markSupported(): Boolean = false

    private fun counted(n: Long) {
        count += n
        if (count > cap) throw FileTooLargeException(cap)
    }
}
