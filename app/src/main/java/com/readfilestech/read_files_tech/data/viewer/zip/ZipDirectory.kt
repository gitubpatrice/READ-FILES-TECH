package com.readfilestech.read_files_tech.data.viewer.zip

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.zip.ZipFormat.CEN_SIG
import com.readfilestech.read_files_tech.data.viewer.zip.ZipFormat.CEN_SIZE
import com.readfilestech.read_files_tech.data.viewer.zip.ZipFormat.EOCD_SIZE
import com.readfilestech.read_files_tech.data.viewer.zip.ZipFormat.LOC_SIZE
import com.readfilestech.read_files_tech.data.viewer.zip.ZipFormat.MASK32
import com.readfilestech.read_files_tech.data.viewer.zip.ZipFormat.U16
import com.readfilestech.read_files_tech.data.viewer.zip.ZipFormat.U32
import com.readfilestech.read_files_tech.data.viewer.zip.ZipFormat.U64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The central directory of an archive, read whole before any entry: the format's own index, where
 * each entry's name, sizes, checksum and data offset are. A local header is read only to find where
 * its data starts, never to learn an entry — reading local headers one after the other (ZipInputStream)
 * lists what the central directory may not hold, and stops at the first data descriptor it misreads.
 *
 * Bounded whatever the file declares: at most [FileCaps.ZIP_VIEWER_ENTRIES] entries and
 * [FileCaps.ZIP_VIEWER_DIRECTORY] bytes of directory, the records walked one by one through a 64 KiB
 * window, never loaded at once.
 */
internal class ZipDirectory private constructor(
    val entries: List<ZipItem>,
    /** Where the central directory starts: every entry's data must end before it. */
    val start: Long,
) {
    /** What the end record (or its ZIP64 twin) says: the directory's place and size, and the offset base. */
    private class End(val directoryStart: Long, val directorySize: Long, val base: Long)

    companion object {
        private const val CANCEL_CHECK_EVERY = 4096

        /** The directory of the archive [bytes]; an archive whose end record cannot be found is unreadable. */
        suspend fun read(bytes: ArchiveBytes): ZipDirectory {
            val end = findEnd(bytes)
            if (end.directorySize > FileCaps.ZIP_VIEWER_DIRECTORY) throw TooManyItemsException(FileCaps.ZIP_VIEWER_ENTRIES)
            val records = readRecords(bytes, end)
            markOverlaps(records, end.directoryStart)
            return ZipDirectory(records.map(Record::toItem), end.directoryStart)
        }

        /**
         * The end of central directory record, searched backwards from the end (a comment of up to 64 KiB
         * may follow it), the nearest that holds together: comment length exact, or the directory it
         * points to starting with a directory record (bytes appended after the archive). A random file
         * named `.zip`, or `PK\3\4` followed by noise, has none: unreadable — never shown as an empty
         * archive, which the 2.15.3 had to guard against (`looksLikeZip`, `zipDeclaresEntries`).
         */
        private fun findEnd(bytes: ArchiveBytes): End {
            if (bytes.size < EOCD_SIZE) throw UnreadableFileException("${bytes.size} bytes: no room for an end record")
            val tailLength = minOf(bytes.size, (EOCD_SIZE + ZipFormat.MAX_COMMENT).toLong()).toInt()
            val tailStart = bytes.size - tailLength
            val tail = bytes.read(tailStart, tailLength)
            for (i in tailLength - EOCD_SIZE downTo 0) {
                val end = if (LittleEndian.u32(tail, i) == ZipFormat.EOCD_SIG) endAt(bytes, tail, i, tailStart + i) else null
                if (end != null) return end
            }
            throw UnreadableFileException("no end of central directory record")
        }

        /**
         * The end record at [position] (at [i] in [tail]), resolved through its ZIP64 record when it has
         * one; null when it does not hold — nothing where it points, or offsets that cannot be.
         */
        private fun endAt(bytes: ArchiveBytes, tail: ByteArray, i: Int, position: Long): End? {
            val zip64 = zip64End(bytes, position)
            val size = zip64?.let {
                LittleEndian.u64(it.second, ZipFormat.ZIP64_END_DIRECTORY_SIZE)
            } ?: LittleEndian.u32(tail, i + ZipFormat.EOCD_DIRECTORY_SIZE)
            val offset = zip64?.let {
                LittleEndian.u64(it.second, ZipFormat.ZIP64_END_DIRECTORY_OFFSET)
            } ?: LittleEndian.u32(tail, i + ZipFormat.EOCD_DIRECTORY_OFFSET)
            // The directory ends where the record after it starts: this one, or the ZIP64 one before it.
            val start = (zip64?.first ?: position) - size
            // Bytes prepended to the archive (a self-extracting one) shift every offset by `base`.
            val end = End(start, size, base = start - offset).takeIf { size >= 0 && offset >= 0 && start >= 0 && it.base >= 0 }
            val exactComment = position + EOCD_SIZE + LittleEndian.u16(tail, i + ZipFormat.EOCD_COMMENT_LENGTH) == bytes.size
            return end?.takeIf { exactComment || startsWithRecord(bytes, it) }
        }

        /** The ZIP64 end record its locator points to, just before [endPosition]: its position and bytes. */
        private fun zip64End(bytes: ArchiveBytes, endPosition: Long): Pair<Long, ByteArray>? {
            val locatorAt = endPosition - ZipFormat.LOCATOR_SIZE
            val locator = if (locatorAt >= 0) bytes.read(locatorAt, ZipFormat.LOCATOR_SIZE) else null
            if (locator == null || LittleEndian.u32(locator, 0) != ZipFormat.LOCATOR_SIG) return null
            // Where the locator says; or right before it, where it lies when bytes were prepended.
            val candidates = listOf(LittleEndian.u64(locator, ZipFormat.LOCATOR_END_OFFSET), locatorAt - ZipFormat.ZIP64_END_SIZE)
            return candidates.firstNotNullOfOrNull { at -> zip64EndAt(bytes, at, locatorAt) }
        }

        private fun zip64EndAt(bytes: ArchiveBytes, at: Long, locatorAt: Long): Pair<Long, ByteArray>? {
            if (at < 0 || at + ZipFormat.ZIP64_END_SIZE > locatorAt) return null
            val record = bytes.read(at, ZipFormat.ZIP64_END_SIZE)
            return (at to record).takeIf { LittleEndian.u32(record, 0) == ZipFormat.ZIP64_END_SIG }
        }

        private fun startsWithRecord(bytes: ArchiveBytes, end: End): Boolean = when {
            end.directorySize == 0L -> true
            end.directorySize < U32 -> false
            else -> LittleEndian.u32(bytes.read(end.directoryStart, U32), 0) == CEN_SIG
        }

        /**
         * Every record of the directory, walked from its start to its end — not to the count the end
         * record gives: an old writer stores the count of 70 000 entries modulo 65 536.
         */
        private suspend fun readRecords(bytes: ArchiveBytes, end: End): List<Record> {
            val input = WindowReader(bytes, end.directoryStart, end.directoryStart + end.directorySize)
            val records = ArrayList<Record>()
            while (input.remaining >= CEN_SIZE) {
                if (records.size % CANCEL_CHECK_EVERY == 0) currentCoroutineContext().ensureActive()
                if (records.size == FileCaps.ZIP_VIEWER_ENTRIES) throw TooManyItemsException(FileCaps.ZIP_VIEWER_ENTRIES)
                records += Record.read(input, records.size, end.base)
            }
            return records
        }

        /**
         * Entries whose data overlaps another's, or does not end before the directory: each local header
         * and its data take at least `30 + compressed size` bytes from their offset, and no two may share
         * one. Measured on the record alone — no local header is read for it.
         */
        private fun markOverlaps(records: List<Record>, directoryStart: Long) {
            // A damaged record's offset means nothing: it neither overlaps nor is overlapped.
            val byOffset = records.filter { it.problem != ZipProblem.DAMAGED }.sortedBy { it.data.localOffset }
            byOffset.forEachIndexed { i, record ->
                val end = record.data.localOffset + LOC_SIZE + record.data.compressedSize
                if (end > directoryStart || end < record.data.localOffset) record.problem = record.problem ?: ZipProblem.DAMAGED
                val next = byOffset.getOrNull(i + 1)
                if (next != null && next.data.localOffset < end) {
                    record.problem = record.problem ?: ZipProblem.OVERLAPPING
                    next.problem = next.problem ?: ZipProblem.OVERLAPPING
                }
            }
        }
    }

    /** One record, as read; its problem may still be found by [markOverlaps]. */
    private class Record(val index: Int, val name: String, val size: Long, val data: ZipData, var problem: ZipProblem?) {
        fun toItem(): ZipItem {
            val directory = name.endsWith('/')
            return ZipItem(index, name, directory, size, problem.takeUnless { directory }, data)
        }

        companion object {
            /** The record at [input]'s place, the [index]-th; whatever its fields say, it ends inside the directory. */
            fun read(input: WindowReader, index: Int, base: Long): Record {
                val header = ByteArray(CEN_SIZE).also(input::read)
                if (LittleEndian.u32(header, 0) != CEN_SIG) throw UnreadableFileException("record $index: no signature")
                val nameBytes = ByteArray(LittleEndian.u16(header, ZipFormat.CEN_NAME_LENGTH))
                val extra = ByteArray(LittleEndian.u16(header, ZipFormat.CEN_EXTRA_LENGTH))
                val comment = LittleEndian.u16(header, ZipFormat.CEN_COMMENT_LENGTH)
                if (input.remaining < nameBytes.size + extra.size + comment) {
                    throw UnreadableFileException(
                        "record $index runs past the directory"
                    )
                }
                input.read(nameBytes)
                input.read(extra)
                input.skip(comment)
                return of(index, header, nameBytes, extra, base)
            }

            private fun of(index: Int, header: ByteArray, nameBytes: ByteArray, extra: ByteArray, base: Long): Record {
                val flags = LittleEndian.u16(header, ZipFormat.CEN_FLAGS)
                val name = ZipNames.decode(nameBytes, flags and ZipFormat.FLAG_UTF8 != 0).replace('\\', '/')
                val stored = longArrayOf(
                    LittleEndian.u32(header, ZipFormat.CEN_UNCOMPRESSED),
                    LittleEndian.u32(header, ZipFormat.CEN_COMPRESSED),
                    LittleEndian.u32(header, ZipFormat.CEN_LOCAL_OFFSET),
                )
                // Uncompressed size, compressed size, offset: through the ZIP64 block when stored as 0xFFFFFFFF.
                val (size, compressed, offset) = zip64Values(stored, zip64Block(extra))?.toList() ?: listOf(-1L, -1L, -1L)
                val method = LittleEndian.u16(header, ZipFormat.CEN_METHOD)
                val data = ZipData(
                    maxOf(compressed, 0),
                    method,
                    LittleEndian.u32(header, ZipFormat.CEN_CRC).toInt(),
                    base + maxOf(offset, 0)
                )
                return Record(index, name, maxOf(size, 0), data, problemOf(header, size, compressed))
            }

            /**
             * [stored] with each value at 0xFFFFFFFF replaced by the next 8 bytes of [block], in the
             * order of the record (APPNOTE 4.5.3); null when the block falls short, or a value cannot be.
             */
            private fun zip64Values(stored: LongArray, block: ByteArray?): LongArray? {
                var at = 0
                var short = false
                val values = stored.map { value ->
                    when {
                        value != MASK32 -> value
                        block == null || at + U64 > block.size -> -1L.also { short = true }
                        else -> LittleEndian.u64(block, at).also { at += U64 }
                    }
                }
                return values.toLongArray().takeIf { !short && it.all { value -> value >= 0 } }
            }

            /** The data of the ZIP64 extended information block of [extra], or null when it has none. */
            private fun zip64Block(extra: ByteArray): ByteArray? {
                var at = 0
                var found: ByteArray? = null
                while (found == null && at + ZipFormat.EXTRA_HEADER <= extra.size) {
                    val end = at + ZipFormat.EXTRA_HEADER + LittleEndian.u16(extra, at + U16)
                    if (end > extra.size) break
                    if (LittleEndian.u16(extra, at) == ZipFormat.ZIP64_EXTRA) found = extra.copyOfRange(at + ZipFormat.EXTRA_HEADER, end)
                    at = end
                }
                return found
            }

            /** Why the entry cannot be read, from its record alone; [size] is -1 when its fields could not be read. */
            private fun problemOf(header: ByteArray, size: Long, compressed: Long): ZipProblem? {
                val flags = LittleEndian.u16(header, ZipFormat.CEN_FLAGS)
                val method = LittleEndian.u16(header, ZipFormat.CEN_METHOD)
                return when {
                    size < 0 -> ZipProblem.DAMAGED
                    isLink(header) -> ZipProblem.LINK
                    flags and ZipFormat.FLAG_ENCRYPTED != 0 -> ZipProblem.ENCRYPTED
                    method != ZipFormat.STORED && method != ZipFormat.DEFLATED -> ZipProblem.UNSUPPORTED_METHOD
                    size > FileCaps.ZIP_ENTRY_DECOMPRESSED -> ZipProblem.TOO_LARGE
                    // Stored data is its own content: two sizes that differ are a lie.
                    method == ZipFormat.STORED && size != compressed -> ZipProblem.DAMAGED
                    else -> null
                }
            }

            /** Made on Unix or macOS, its mode a symbolic link's: the target's path is its content. */
            private fun isLink(header: ByteArray): Boolean {
                val host = LittleEndian.u16(header, ZipFormat.CEN_MADE_BY) ushr ZipFormat.HOST_SHIFT
                val mode = LittleEndian.u32(header, ZipFormat.CEN_ATTRIBUTES) ushr ZipFormat.MODE_SHIFT
                return (host == ZipFormat.HOST_UNIX || host == ZipFormat.HOST_OSX) && mode and ZipFormat.MODE_TYPE == ZipFormat.MODE_LINK
            }
        }
    }
}

/** Sequential reads of `[start, end)` of an archive through a 64 KiB buffer. */
internal class WindowReader(private val bytes: ArchiveBytes, start: Long, private val end: Long) {
    private val buffer = ByteArray(ArchiveBytes.CHUNK)
    private var bufferStart = start
    private var bufferFill = 0
    private var at = 0

    val remaining: Long get() = end - (bufferStart + at)

    fun read(into: ByteArray) {
        var done = 0
        while (done < into.size) {
            if (at == bufferFill) refill()
            val n = minOf(into.size - done, bufferFill - at)
            System.arraycopy(buffer, at, into, done, n)
            at += n
            done += n
        }
    }

    fun skip(count: Int) {
        var left = count
        while (left > 0) {
            if (at == bufferFill) refill()
            val n = minOf(left, bufferFill - at)
            at += n
            left -= n
        }
    }

    private fun refill() {
        bufferStart += bufferFill
        at = 0
        bufferFill = minOf(buffer.size.toLong(), end - bufferStart).toInt()
        if (bufferFill <= 0) throw UnreadableFileException("central directory cut")
        bytes.readFully(bufferStart, buffer, 0, bufferFill)
    }
}
