package com.readfilestech.read_files_tech.data.files

import java.io.File
import java.io.IOException
import java.io.InputStream

/** A file compared with bytes held in memory, read a chunk at a time. */
object FileBytes {
    private const val CHUNK = 64 * 1024

    /** Whether [file] holds [bytes], to the byte; a file that cannot be read holds something else. */
    fun holds(file: File, bytes: ByteArray): Boolean {
        if (!file.isFile || file.length() != bytes.size.toLong()) return false
        return try {
            file.inputStream().use { input -> readsAs(input, bytes) }
        } catch (_: IOException) {
            false
        }
    }

    private fun readsAs(input: InputStream, bytes: ByteArray): Boolean {
        val chunk = ByteArray(CHUNK)
        var at = 0
        var same = true
        var read = input.read(chunk)
        while (same && read >= 0) {
            same = at + read <= bytes.size
            var i = 0
            while (same && i < read) same = chunk[i] == bytes[at + i++]
            at += read
            read = input.read(chunk)
        }
        return same && at == bytes.size
    }
}
