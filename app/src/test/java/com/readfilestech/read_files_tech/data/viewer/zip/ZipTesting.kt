package com.readfilestech.read_files_tech.data.viewer.zip

import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One entry of a forged archive, every field chosen — what no archiver would write. */
data class Forged(
    val name: String,
    val content: ByteArray = ByteArray(0),
    val method: Int = ZipFormat.DEFLATED,
    /** The name's bytes as stored; flagged UTF-8 when [utf8Flag]. */
    val nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8),
    val utf8Flag: Boolean = true,
    /** Instead of [content]: this many zero bytes, deflated as they are made (a bomb never held whole). */
    val zeros: Long = 0,
    /** A lie about the uncompressed size, in both headers (the Dart suite's `forgeZip`). */
    val declaredSize: Long? = null,
    val declaredCrc: Int? = null,
    val encrypted: Boolean = false,
    /** Made by Unix (3) or by MS-DOS (0) in the high byte. */
    val versionMadeBy: Int = UNIX shl 8 or 30,
    val externalAttributes: Long = 0,
    /** Sizes and offset in a ZIP64 extra block, the record's fields at 0xFFFFFFFF. */
    val zip64: Boolean = false,
    /** Points the record at the local header of entry N instead of its own (overlapping entries). */
    val sharesDataOf: Int? = null,
) {
    companion object {
        const val UNIX = 3

        /** `lrwxrwxrwx` in the high half of the external attributes. */
        const val LINK_ATTRIBUTES = 0xA1FFL shl 16
    }
}

/** Writes forged archives at the byte level (APPNOTE 6.3.10). */
object ZipForge {
    private const val MASK32 = 0xFFFFFFFFL

    /** What one entry became: its data as stored, its real size and CRC-32, where its local header is. */
    private class Written(val entry: Forged, val data: ByteArray, val size: Long, val crc: Int, val offset: Long)

    fun write(
        entries: List<Forged>,
        prefix: ByteArray = ByteArray(0),
        comment: ByteArray = ByteArray(0),
        zip64End: Boolean = false,
        /** The central directory's last bytes dropped, the end record kept: a directory cut short. */
        cutDirectory: Int = 0,
    ): ByteArray {
        val out = Le()
        val written = entries.map { entry -> localHeader(out, entry) }
        val cenStart = out.size().toLong()
        written.forEach { centralRecord(out, it, written) }
        val directory = Directory(entries.size, cenStart, out.size() - cenStart)
        val bytes = out.toByteArray().let { if (cutDirectory > 0) it.copyOf(it.size - cutDirectory) else it }
        return prefix + bytes + end(directory, bytes.size.toLong(), zip64End, comment)
    }

    private fun localHeader(out: Le, entry: Forged): Written {
        val (data, size, crc) = payload(entry)
        val offset = out.size().toLong()
        out.u32(0x04034b50).u16(20).u16(flags(entry)).u16(entry.method).u16(0).u16(0).u32(crcOf(entry, crc))
            .u32(data.size.toLong()).u32(entry.declaredSize ?: size)
            .u16(entry.nameBytes.size).u16(0).bytes(entry.nameBytes).bytes(data)
        return Written(entry, data, size, crc, offset)
    }

    private fun centralRecord(out: Le, w: Written, all: List<Written>) {
        val entry = w.entry
        val size = entry.declaredSize ?: w.size
        val compressed = w.data.size.toLong()
        val at = entry.sharesDataOf?.let { all[it].offset } ?: w.offset
        val zip64 = entry.zip64
        val extra = if (zip64) Le().u16(1).u16(24).u64(size).u64(compressed).u64(at).toByteArray() else ByteArray(0)
        out.u32(0x02014b50).u16(entry.versionMadeBy).u16(if (zip64) 45 else 20).u16(flags(entry)).u16(entry.method)
            .u16(0).u16(0).u32(crcOf(entry, w.crc))
            .u32(if (zip64) MASK32 else compressed).u32(if (zip64) MASK32 else size)
            .u16(entry.nameBytes.size).u16(extra.size).u16(0).u16(0).u16(0).u32(entry.externalAttributes)
            .u32(if (zip64) MASK32 else at).bytes(entry.nameBytes).bytes(extra)
    }

    /** The central directory written: its record count, start and size. */
    private class Directory(val count: Int, val start: Long, val size: Long)

    /** The end record, after a ZIP64 end record and its locator when [zip64]: their values masked then. */
    private fun end(directory: Directory, endsAt: Long, zip64: Boolean, comment: ByteArray): ByteArray {
        val tail = Le()
        val count = directory.count.toLong()
        if (zip64) {
            tail.u32(0x06064b50).u64(44).u16(45).u16(45).u32(0).u32(0).u64(count).u64(count).u64(directory.size).u64(directory.start)
            tail.u32(0x07064b50).u32(0).u64(endsAt).u32(1)
        }
        val shown = if (zip64) 0xFFFF else directory.count
        tail.u32(0x06054b50).u16(0).u16(0).u16(shown).u16(shown)
            .u32(if (zip64) MASK32 else directory.size).u32(if (zip64) MASK32 else directory.start)
            .u16(comment.size).bytes(comment)
        return tail.toByteArray()
    }

    private fun crcOf(entry: Forged, real: Int): Long = (entry.declaredCrc ?: real).toLong() and MASK32

    private fun flags(entry: Forged): Int = (if (entry.utf8Flag) 0x800 else 0) or (if (entry.encrypted) 1 else 0)

    /** The data as stored, the real size and CRC-32. */
    private fun payload(entry: Forged): Triple<ByteArray, Long, Int> {
        val crc = CRC32()
        if (entry.zeros > 0) {
            val chunk = ByteArray(1 shl 20)
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var left = entry.zeros
            while (left > 0) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                crc.update(chunk, 0, n)
                deflater.setInput(chunk, 0, n)
                while (!deflater.needsInput()) out.write(buffer, 0, deflater.deflate(buffer))
                left -= n
            }
            deflater.finish()
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            return Triple(out.toByteArray(), entry.zeros, crc.value.toInt())
        }
        crc.update(entry.content)
        val data = if (entry.method == ZipFormat.DEFLATED) deflate(entry.content) else entry.content
        return Triple(data, entry.content.size.toLong(), crc.value.toInt())
    }

    fun deflate(content: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(content)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** Little-endian writing. */
    private class Le : ByteArrayOutputStream() {
        fun u16(v: Int) = apply {
            write(v and 0xFF)
            write(v ushr 8 and 0xFF)
        }

        fun u32(v: Long) = apply {
            u16((v and 0xFFFF).toInt())
            u16((v ushr 16 and 0xFFFF).toInt())
        }

        fun u64(v: Long) = apply {
            u32(v and MASK32)
            u32(v ushr 32)
        }

        fun bytes(b: ByteArray) = apply { write(b) }
    }
}

/** An archive written by the JDK's ZipOutputStream: what an honest writer makes (data descriptors included). */
fun jdkZip(file: File, entries: List<Pair<String, ByteArray>>, stored: Boolean = false): File {
    ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(if (stored) storedEntry(name, content) else ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return file
}

/** A STORE entry: its size and CRC-32 given before its data, as the format requires of one. */
private fun storedEntry(name: String, content: ByteArray) = ZipEntry(name).apply {
    method = ZipEntry.STORED
    size = content.size.toLong()
    crc = CRC32().apply { update(content) }.value
}

/** `tools/corpus/zip/` of the repository: the archives made by real software, read by the tests too. */
val corpusZip: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "tools/corpus/zip") }
        .first { it.isDirectory }
}

/**
 * A test of archives: its temporary folder, and every archive it opens closed after it — Windows deletes
 * no file still open, and an archive holds its file open until closed.
 */
abstract class ZipTestBase {
    @TempDir
    lateinit var tmp: File

    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun closeArchives() {
        opened.asReversed().forEach { it.close() }
        opened.clear()
    }

    fun openArchive(file: File): ZipArchive = runBlocking { ZipArchive.open(StorageFileSource(file)) }.also { opened += it }

    /** An archive of [entries] forged by [ZipForge], opened. */
    fun forged(vararg entries: Forged, name: String = "forged.zip"): ZipArchive =
        openArchive(File(tmp, name).apply { writeBytes(ZipForge.write(entries.toList())) })
}

fun ZipArchive.entry(name: String): ZipItem = entries.single { it.name == name }

fun ZipArchive.bytesOf(item: ZipItem, cap: Long = Long.MAX_VALUE): ByteArray = read(item, cap).use { it.readBytes() }
