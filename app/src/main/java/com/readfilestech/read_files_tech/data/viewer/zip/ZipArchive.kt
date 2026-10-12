package com.readfilestech.read_files_tech.data.viewer.zip

import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.SourceFile
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * An archive opened for the ZIP viewer (`ZipViewerScreen` of the 2.15.3): its central directory read
 * once, off the main thread (the 2.15.3 decoded it on the UI thread — inventory §1.C), and each entry
 * read from its own record when it is shared or extracted. Open until [close]: the file of the source
 * (for the vault, a private copy, docs D-028) and one channel, read by position from any thread.
 *
 * Not java.util.zip.ZipFile, which Android's own sources show refusing the WHOLE archive when one entry
 * is encrypted or compressed by another method (`ZipFile.java:1329-1334`, API 36.1), or — from API 34,
 * targetSdk 34 and up — when one name holds `..` or starts with `/` (`:1932`, ZipPathValidator): the
 * listing of a hostile archive would differ between the two test phones. It also reads an entry by its
 * NAME (`:421`), so of two entries of one name it reads whichever its lookup finds, exposes neither the
 * attributes that mark a link nor the code page of a name ([ZipNames]). The format is read here; the
 * decompression stays the platform's [Inflater].
 */
class ZipArchive private constructor(
    private val source: SourceFile,
    private val channel: FileChannel,
    private val bytes: ArchiveBytes,
    private val directory: ZipDirectory,
) : Closeable {
    /** Every entry, in the order of the central directory. */
    val entries: List<ZipItem> get() = directory.entries

    /** The same, by name, as the 2.15.3 listed them: a folder's entries together. Sorted once, as it opens. */
    val byName: List<ZipItem> = directory.entries.sortedBy(ZipItem::name)

    /**
     * The content of [item], checked as it is read: never more than its declared size, which is at most
     * [cap] — a header declaring 1 KB over 10 MB of zeros stops one byte past the kilobyte — and, at its
     * end, exactly that size and its checksum. Whatever does not hold is a [ZipEntryException]: a
     * consumer that writes what it reads throws its file away then.
     *
     * @throws ZipEntryException the entry cannot be read ([ZipItem.problem]), or its data does not hold.
     * @throws FileTooLargeException its declared size is above [cap].
     */
    fun read(item: ZipItem, cap: Long = FileCaps.ZIP_ENTRY_DECOMPRESSED): InputStream {
        requireEntry(item)
        item.problem?.let { throw ZipEntryException(item, it) }
        if (item.size > cap) throw FileTooLargeException(cap)
        return EntryInput(item, bytes, dataStart(item))
    }

    /**
     * The start of [item]'s content, [cap] bytes of it at most, for a reader that stops on its own — the
     * spreadsheet viewer reads a sheet up to the last row it shows. An entry larger than an entry may be
     * ([ZipProblem.TOO_LARGE]) is read that far too; past [cap] the read is a [FileTooLargeException]. Every
     * other check of [read] holds, and its checksum when it is read to its end.
     */
    fun readStart(item: ZipItem, cap: Long): InputStream {
        requireEntry(item)
        item.problem?.takeIf { it != ZipProblem.TOO_LARGE }?.let { throw ZipEntryException(item, it) }
        return BoundedInputStream(EntryInput(item, bytes, dataStart(item)), cap)
    }

    private fun requireEntry(item: ZipItem) {
        require(item.index in entries.indices && entries[item.index] === item) { "not an entry of this archive" }
        require(!item.isDirectory) { "a folder has no content" }
    }

    /**
     * Where [item]'s data starts: after its local header, by that header's own name and extra lengths
     * (they may differ from the directory's) — a header that is not one, or data that would run into
     * the directory, is a damaged entry.
     */
    private fun dataStart(item: ZipItem): Long {
        val header = bytes.read(item.data.localOffset, ZipFormat.LOC_SIZE)
        val start = item.data.localOffset + ZipFormat.LOC_SIZE +
            LittleEndian.u16(header, ZipFormat.LOC_NAME_LENGTH) + LittleEndian.u16(header, ZipFormat.LOC_EXTRA_LENGTH)
        val holds = LittleEndian.u32(header, 0) == ZipFormat.LOC_SIG && start + item.data.compressedSize <= directory.start
        return if (holds) start else throw ZipEntryException(item, ZipProblem.DAMAGED)
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            source.close()
        }
    }

    companion object {
        /**
         * The archive of [source], within [cap] — the 2.15.3's for the ZIP viewer (`FileCaps.ZIP_VIEWER`) by
         * default, a workbook's for the spreadsheet viewer — its directory read: whatever it opened is closed
         * if it fails, cancellation included.
         *
         * @throws com.readfilestech.read_files_tech.data.viewer.UnreadableFileException no archive there.
         * @throws com.readfilestech.read_files_tech.data.viewer.TooManyItemsException a listing above its bounds.
         */
        suspend fun open(source: ViewerSource, cap: Long = FileCaps.ZIP_VIEWER): ZipArchive {
            val file = source.acquireFile(cap)
            var archive: ZipArchive? = null
            var channel: FileChannel? = null
            try {
                channel = FileChannel.open(file.file.toPath(), StandardOpenOption.READ)
                // Its size now, read through the channel that will be read: the file may have grown.
                val size = channel.size()
                if (size > cap) throw FileTooLargeException(cap)
                val bytes = ArchiveBytes(channel, size)
                archive = ZipArchive(file, channel, bytes, ZipDirectory.read(bytes))
                return archive
            } finally {
                if (archive == null) {
                    runCatching { channel?.close() }
                    file.close()
                }
            }
        }
    }
}

/**
 * One entry's content, from its data in the archive: stored bytes as they are, deflated ones through an
 * [Inflater] fed by pieces of 64 KiB. Each read gives at most one byte more than the declared size
 * leaves: a lie is caught one byte past it, whatever the data would inflate to.
 */
private class EntryInput(private val item: ZipItem, private val bytes: ArchiveBytes, dataStart: Long) : InputStream() {
    private var next = dataStart
    private var compressedLeft = item.data.compressedSize
    private val inflater = if (item.data.method == ZipFormat.DEFLATED) Inflater(true) else null
    private val input = ByteArray(ArchiveBytes.CHUNK)
    private val crc = CRC32()
    private var produced = 0L
    private var dummyGiven = false
    private var ended = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (ended || len == 0) return if (ended) -1 else 0
        // One byte more than the declared size may give: produced, it proves the size false.
        val asked = minOf(len.toLong(), item.size - produced + 1).toInt()
        val n = if (inflater == null) readStored(b, off, asked) else readDeflated(inflater, b, off, asked)
        if (n == -1) ended() else produced(b, off, n)
        return n
    }

    /** At the end of the data: exactly the declared size, and its checksum. */
    private fun ended() {
        ended = true
        if (produced != item.size || crc.value.toInt() != item.data.crc) damaged()
    }

    private fun produced(b: ByteArray, off: Int, n: Int) {
        produced += n
        if (produced > item.size) damaged()
        crc.update(b, off, n)
    }

    private fun readStored(b: ByteArray, off: Int, len: Int): Int {
        if (compressedLeft == 0L) return -1
        val n = minOf(len.toLong(), compressedLeft).toInt()
        bytes.readFully(next, b, off, n)
        next += n
        compressedLeft -= n
        return n
    }

    private fun readDeflated(inflater: Inflater, b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            val n = try {
                inflater.inflate(b, off, len)
            } catch (e: DataFormatException) {
                throw ZipEntryException(item, ZipProblem.DAMAGED).apply { initCause(e) }
            }
            when {
                n > 0 -> return n
                inflater.finished() -> return -1
                inflater.needsDictionary() -> damaged()
                inflater.needsInput() -> feed(inflater)
                // inflate() gives nothing only when it needs input or a dictionary, or is done.
                else -> damaged()
            }
        }
    }

    private fun feed(inflater: Inflater) {
        if (compressedLeft > 0) {
            val n = minOf(input.size.toLong(), compressedLeft).toInt()
            bytes.readFully(next, input, 0, n)
            next += n
            compressedLeft -= n
            inflater.setInput(input, 0, n)
        } else if (!dummyGiven) {
            // `nowrap` inflation may ask for one byte past the data (Inflater's own documentation).
            dummyGiven = true
            inflater.setInput(ByteArray(1))
        } else {
            throw ZipEntryException(item, ZipProblem.DAMAGED).apply { initCause(EOFException("deflated data cut")) }
        }
    }

    private fun damaged(): Nothing = throw ZipEntryException(item, ZipProblem.DAMAGED)

    override fun close() {
        ended = true
        inflater?.end()
    }

    private companion object {
        const val BYTE = 0xFF
    }
}
