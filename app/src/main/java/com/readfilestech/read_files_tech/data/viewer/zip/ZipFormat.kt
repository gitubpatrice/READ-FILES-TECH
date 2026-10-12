package com.readfilestech.read_files_tech.data.viewer.zip

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** The records of the ZIP format this reader uses, and their fields (PKWARE APPNOTE 6.3.10, §4.3 to §4.5). */
internal object ZipFormat {
    const val U16 = 2
    const val U32 = 4
    const val U64 = 8
    const val MASK32 = 0xFFFFFFFFL

    /** End of central directory record (§4.3.16), and its comment of at most 64 KiB after it. */
    const val EOCD_SIG = 0x06054b50L
    const val EOCD_SIZE = 22
    const val EOCD_DIRECTORY_SIZE = 12
    const val EOCD_DIRECTORY_OFFSET = 16
    const val EOCD_COMMENT_LENGTH = 20
    const val MAX_COMMENT = 0xFFFF

    /** ZIP64 end of central directory locator (§4.3.15), just before the end record. */
    const val LOCATOR_SIG = 0x07064b50L
    const val LOCATOR_SIZE = 20
    const val LOCATOR_END_OFFSET = 8

    /** ZIP64 end of central directory record (§4.3.14). */
    const val ZIP64_END_SIG = 0x06064b50L
    const val ZIP64_END_SIZE = 56
    const val ZIP64_END_DIRECTORY_SIZE = 40
    const val ZIP64_END_DIRECTORY_OFFSET = 48

    /** Central directory record (§4.3.12). */
    const val CEN_SIG = 0x02014b50L
    const val CEN_SIZE = 46
    const val CEN_MADE_BY = 4
    const val CEN_FLAGS = 8
    const val CEN_METHOD = 10
    const val CEN_CRC = 16
    const val CEN_COMPRESSED = 20
    const val CEN_UNCOMPRESSED = 24
    const val CEN_NAME_LENGTH = 28
    const val CEN_EXTRA_LENGTH = 30
    const val CEN_COMMENT_LENGTH = 32
    const val CEN_ATTRIBUTES = 38
    const val CEN_LOCAL_OFFSET = 42

    /** Local file header (§4.3.7). */
    const val LOC_SIG = 0x04034b50L
    const val LOC_SIZE = 30
    const val LOC_NAME_LENGTH = 26
    const val LOC_EXTRA_LENGTH = 28

    /** An extra field block: its tag and length (§4.5.1); the ZIP64 one (§4.5.3). */
    const val EXTRA_HEADER = 4
    const val ZIP64_EXTRA = 0x0001

    /** General purpose bits (§4.4.4): encrypted; name in UTF-8. */
    const val FLAG_ENCRYPTED = 1
    const val FLAG_UTF8 = 0x800

    /** Compression methods (§4.4.5) this reader reads. */
    const val STORED = 0
    const val DEFLATED = 8

    /** "Version made by" (§4.4.2): the host in its high byte; Unix and macOS keep a mode in the attributes. */
    const val HOST_SHIFT = 8
    const val HOST_UNIX = 3
    const val HOST_OSX = 19
    const val MODE_SHIFT = 16
    const val MODE_TYPE = 0xF000L
    const val MODE_LINK = 0xA000L
}

/** Little-endian fields of the format, unsigned. */
internal object LittleEndian {
    private const val BYTE = 0xFFL
    private const val BITS = 8

    fun u16(b: ByteArray, at: Int): Int = unsigned(b, at, ZipFormat.U16).toInt()

    fun u32(b: ByteArray, at: Int): Long = unsigned(b, at, ZipFormat.U32)

    /** Above 2^63 - 1, negative: a caller refuses it as it refuses any negative size or offset. */
    fun u64(b: ByteArray, at: Int): Long = unsigned(b, at, ZipFormat.U64)

    private fun unsigned(b: ByteArray, at: Int, width: Int): Long = (width - 1 downTo 0).fold(
        0L
    ) { value, i -> value shl BITS or (b[at + i].toLong() and BYTE) }
}

/**
 * Positional reads of an archive, by pieces of at most 64 KiB: a channel read goes through a temporary
 * direct buffer of the size asked, which its thread keeps (one read of 50 MB left 50 MB, corpus of the
 * text viewer). Positional, so the share of an entry and an extraction read one channel at once.
 */
internal class ArchiveBytes(private val channel: FileChannel, val size: Long) {
    fun readFully(position: Long, into: ByteArray, offset: Int = 0, length: Int = into.size - offset) {
        var done = 0
        while (done < length) {
            val n = channel.read(ByteBuffer.wrap(into, offset + done, minOf(CHUNK, length - done)), position + done)
            if (n < 0) throw EOFException("end of the archive at ${position + done}")
            done += n
        }
    }

    /** [length] bytes at [position], in a new array. */
    fun read(position: Long, length: Int): ByteArray = ByteArray(length).also { readFully(position, it) }

    companion object {
        const val CHUNK = 64 * 1024
    }
}
