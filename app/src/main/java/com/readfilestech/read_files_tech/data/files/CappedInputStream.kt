package com.readfilestech.read_files_tech.data.files

import java.io.FilterInputStream
import java.io.InputStream

/**
 * At most [limit] bytes of [input], then a quiet end of stream; closing it closes [input]. For readers
 * that must stay bounded whatever the file became since its size was read — a text grown, or replaced,
 * between a size check and the read. Unlike LimitedInput, a shorter source is not an error.
 */
class CappedInputStream(input: InputStream, limit: Long) : FilterInputStream(input) {
    private var left = limit

    override fun read(): Int {
        if (left <= 0) return -1
        val byte = super.read()
        if (byte != -1) left--
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, left.coerceAtLeast(0)))
        left -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), left.coerceAtLeast(0)).toInt()

    override fun markSupported(): Boolean = false
}
