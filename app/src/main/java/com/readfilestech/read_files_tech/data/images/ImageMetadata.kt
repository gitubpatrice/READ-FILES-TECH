// The markers, chunk and box names, tags and offsets of the formats, byte by byte: the numbers ARE the definition.
@file:Suppress("MagicNumber")

package com.readfilestech.read_files_tech.data.images

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import java.io.File
import java.io.RandomAccessFile

/**
 * What the EXIF tool lists of an image before it removes it — `ExifService.inspect` of the 2.15.3: whether it
 * says where it was taken, when, by what camera and what software. [orientation] is the EXIF one (1 to 8):
 * 1 when there is none, and in a HEIF, which turns its pictures by its own boxes and ignores this tag.
 */
data class ImageMetadata(
    val gps: Boolean = false,
    val taken: String? = null,
    val make: String? = null,
    val model: String? = null,
    val software: String? = null,
    val orientation: Int = UPRIGHT,
) {
    /** Nothing the screen lists. The orientation is not listed: the copy is turned upright instead. */
    val isEmpty: Boolean get() = !gps && taken == null && make == null && model == null && software == null

    companion object {
        const val UPRIGHT = 1
    }
}

/**
 * Reads the EXIF of a JPEG, a PNG (`eXIf`), a WebP (`EXIF`) or a HEIF (its `Exif` item) WITHOUT decoding a
 * pixel — its callers run it off the main thread: the 2.15.3 decoded the whole picture on the UI thread to
 * read six tags (inventory F12). Any other format (GIF, BMP) carries no EXIF.
 *
 * Bounded whatever the file says: [FileCaps.IMAGE_FILE] first; at most [FileCaps.IMAGE_METADATA_CHUNKS]
 * segments, chunks or boxes walked; a block or a `meta` box read up to [FileCaps.IMAGE_METADATA]; in the block,
 * three directories read — the first, its EXIF one, its GPS one — and no chain followed, so offsets that loop
 * cannot; a count or an offset that leaves the block reads what it holds and no more; a thumbnail is never
 * read. A damaged block is a block that says less: never an exception — only the file's own I/O errors are.
 *
 * @throws com.readfilestech.read_files_tech.data.files.FileTooLargeException over [FileCaps.IMAGE_FILE].
 * @throws UnreadableFileException an empty file.
 */
object ImageMetadataReader {
    private val JPEG = bytesOf(0xFF, 0xD8)
    private val PNG = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

    /** What precedes the TIFF header in a JPEG's APP1 segment, and in some writers' PNG, WebP and HEIF blocks. */
    private val EXIF_HEADER = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)

    private const val HEAD_BYTES = 12
    private const val APP1 = "APP1"

    fun read(file: File): ImageMetadata {
        FileCaps.check(file, FileCaps.IMAGE_FILE)
        return RandomAccessFile(file, "r").use { handle ->
            val bytes = FileBytes(handle)
            if (bytes.length == 0L) throw UnreadableFileException("empty image")
            val head = bytes.at(0, HEAD_BYTES)
            val block = head?.let { exifOf(bytes, it) }?.let(::withoutExifHeader)
            // HEIF turns its pictures by its own boxes (`irot`, `imir`): its EXIF orientation is not the one shown.
            block?.let { Tiff.read(it, orientation = head?.startsWith(FTYP, at = 4) != true) } ?: ImageMetadata()
        }
    }

    private fun exifOf(bytes: FileBytes, head: ByteArray): ByteArray? = when {
        head.startsWith(JPEG) -> jpeg(bytes)
        head.startsWith(PNG) -> png(bytes)
        head.startsWith(RIFF) && head.startsWith(WEBP, at = 8) -> webp(bytes)
        head.startsWith(FTYP, at = 4) -> Heif.exif(bytes)
        else -> null
    }

    /** The APP1 segment that starts with `Exif\0\0`, met before the picture's data. A segment holds 64 KiB at most. */
    private fun jpeg(bytes: FileBytes): ByteArray? =
        walk(bytes, from = 2L, ::jpegSegmentAt)
            .filter { it.type == APP1 }
            .mapNotNull { bytes.at(it.contentAt, it.length.toInt()) }
            .firstOrNull { it.startsWith(EXIF_HEADER) }

    /** A JPEG segment at [at]; null at the picture's data (SOS) or the end (EOI): the EXIF comes before, or not at all. */
    private fun jpegSegmentAt(bytes: FileBytes, at: Long): Chunk? {
        val marker = bytes.at(at, 4)?.takeIf { it.u8(0) == 0xFF } ?: return null
        val type = marker.u8(1)
        return when (type) {
            // A fill byte before a marker.
            0xFF -> Chunk("", at + 1, 0, at + 1)
            // The markers that stand alone, without a length: TEM, RST0-7, a second SOI.
            0x01, in 0xD0..0xD8 -> Chunk("", at + 2, 0, at + 2)
            0xDA, 0xD9 -> null
            else -> marker.u16be(2).toLong().takeIf { it >= 2 }?.let { length ->
                Chunk(if (type == 0xE1) APP1 else "", at + 4, length - 2, at + 2 + length)
            }
        }
    }

    /** The `eXIf` chunk — before the pixels or after them, both are met. */
    private fun png(bytes: FileBytes): ByteArray? =
        walk(bytes, from = PNG.size.toLong()) { source, at ->
            source.at(at, 8)?.let { head ->
                val length = head.u32be(0)
                Chunk(head.ascii(4, 4), at + 8, length, at + 12 + length)
            }
        }.takeWhile { it.type != "IEND" }.firstOrNull { it.type == "eXIf" }?.let { bytes.upTo(it.contentAt, it.length) }

    /** The `EXIF` chunk of a WebP (VP8X), which a writer usually puts after the picture. */
    private fun webp(bytes: FileBytes): ByteArray? =
        walk(bytes, from = HEAD_BYTES.toLong()) { source, at ->
            source.at(at, 8)?.let { head ->
                val size = head.u32le(4)
                // A chunk of an odd size is followed by one byte of padding.
                Chunk(head.ascii(0, 4), at + 8, size, at + 8 + size + (size and 1))
            }
        }.firstOrNull { it.type == "EXIF" }?.let { bytes.upTo(it.contentAt, it.length) }

    private fun withoutExifHeader(block: ByteArray): ByteArray =
        if (block.startsWith(EXIF_HEADER)) block.copyOfRange(EXIF_HEADER.size, block.size) else block
}

/** [ImageMetadataReader]'s reading of a HEIF: ISO boxes, not segments nor chunks. */
private object Heif {
    /**
     * The `Exif` item of a HEIF (or an AVIF): the top-level `meta` box, its `iinf` naming the item, its `iloc`
     * saying where its bytes are — which start with the offset of the TIFF header among them (ISO/IEC 23008-12,
     * annex A.2.1).
     */
    fun exif(bytes: FileBytes): ByteArray? {
        val meta = walk(bytes, from = 0L) { source, at -> boxAt(source, at, source.length) }
            .firstOrNull { it.type == "meta" }?.let { bytes.upTo(it.contentAt, it.length) } ?: return null
        // `meta` is a full box: version and flags, then its children.
        val children = walk(ArrayBytes(meta), from = 4L) { source, at -> boxAt(source, at, source.length) }.toList()
        val item = children.firstOrNull { it.type == "iinf" }?.let { exifItem(meta, it) }
        val extent = item?.let { id -> children.firstOrNull { it.type == "iloc" }?.let { location(meta, it, id) } }
        // A length of 0 is "to the end of the file".
        val data = extent?.let { (offset, length) -> bytes.upTo(offset, if (length == 0L) bytes.length - offset else length) }
        return data?.takeIf { it.size >= 4 }?.let { bytesOfItem ->
            val tiff = 4L + bytesOfItem.u32be(0)
            if (tiff < bytesOfItem.size) bytesOfItem.copyOfRange(tiff.toInt(), bytesOfItem.size) else null
        }
    }

    /** A box of ISO/IEC 14496-12 at [at], lying wholly before [end]. */
    private fun boxAt(source: Bytes, at: Long, end: Long): Chunk? = source.at(at, 8)?.let { head ->
        val declared = head.u32be(0)
        val header = if (declared == 1L) 16L else 8L
        val size = when (declared) {
            0L -> end - at
            1L -> source.at(at + 8, 8)?.u64be(0) ?: 0L
            else -> declared
        }
        // `size <= end - at`, never `at + size <= end`: a large size near Long.MAX_VALUE would overflow.
        Chunk(head.ascii(4, 4), at + header, size - header, at + size).takeIf { size >= header && size <= end - at }
    }

    /** The id of the item of type `Exif` (an `infe` of version 2 or 3; earlier ones name no type). */
    private fun exifItem(meta: ByteArray, iinf: Chunk): Long? {
        val version = Fields(meta, iinf).number(1)
        val entries = iinf.contentAt + 4 + if (version == 0L) 2 else 4
        return walk(ArrayBytes(meta), from = entries) { source, at -> boxAt(source, at, iinf.contentAt + iinf.length) }
            .filter { it.type == "infe" }
            .firstNotNullOfOrNull { infe ->
                val fields = Fields(meta, infe)
                val infeVersion = fields.number(1)
                fields.skip(3)
                val id = when (infeVersion) {
                    2L -> fields.number(2)
                    3L -> fields.number(4)
                    else -> null
                }
                fields.skip(2) // item_protection_index
                id?.takeIf { fields.text(4) == "Exif" }
            }
    }

    /** Where [item]'s bytes are in the file: its first extent, stored in the file itself (construction method 0). */
    private fun location(meta: ByteArray, iloc: Chunk, item: Long): Pair<Long, Long>? {
        val fields = Fields(meta, iloc)
        val version = fields.number(1) ?: 0L
        fields.skip(3)
        val sizes = fields.number(1) ?: 0L
        val more = fields.number(1) ?: 0L
        val layout = IlocLayout(version, sizes, more)
        val count = fields.number(if (layout.wide) 4 else 2) ?: 0L
        return generateSequence { layout.item(fields) }
            .take(minOf(count, FileCaps.IMAGE_METADATA_CHUNKS.toLong()).toInt())
            .firstOrNull { it.id == item }
            ?.takeIf { it.construction == 0L }
            ?.extent
    }

    /** The field widths of an `iloc` box, by its version and its two bytes of sizes. */
    private class IlocLayout(private val version: Long, sizes: Long, more: Long) {
        val wide = version >= 2
        private val offsetSize = (sizes shr 4).toInt()
        private val lengthSize = (sizes and 0xF).toInt()
        private val baseSize = (more shr 4).toInt()
        private val indexSize = if (version >= 1) (more and 0xF).toInt() else 0

        /** The next item of the box, its extents read past; null once the box is exhausted. */
        fun item(fields: Fields): IlocItem? {
            val id = fields.number(if (wide) 4 else 2)
            val construction = if (version >= 1) fields.number(2)?.and(0xF) else 0L
            fields.skip(2) // data_reference_index
            val base = fields.number(baseSize)
            val extents = fields.number(2) ?: 0L
            var first: Pair<Long, Long>? = null
            repeat(extents.toInt()) {
                fields.skip(indexSize)
                val offset = fields.number(offsetSize)
                val length = fields.number(lengthSize)
                val extent = if (base != null && offset != null && length != null) base + offset to length else null
                first = first ?: extent
            }
            return if (id != null && construction != null) IlocItem(id, construction, first) else null
        }
    }

    private class IlocItem(val id: Long, val construction: Long, val extent: Pair<Long, Long>?)
}

/** Segments, chunks or boxes laid end to end from [from], each found by [chunkAt]; never more than the cap. */
private fun <S : Bytes> walk(source: S, from: Long, chunkAt: (S, Long) -> Chunk?): Sequence<Chunk> =
    generateSequence(chunkAt(source, from)) { chunkAt(source, it.next) }.take(FileCaps.IMAGE_METADATA_CHUNKS)

/** A segment, chunk or box: its type, where its content starts and how long it says it is, where the next one starts. */
private class Chunk(val type: String, val contentAt: Long, val length: Long, val next: Long)

/** Positional reads: never past the end, never more than asked; null for what is not there. */
private interface Bytes {
    val length: Long

    fun at(offset: Long, count: Int): ByteArray?

    /** The [count] bytes at [offset], cut at [FileCaps.IMAGE_METADATA] and at the end. */
    fun upTo(offset: Long, count: Long): ByteArray? =
        if (offset < 0 || offset > length || count < 0) null else at(offset, minOf(count, FileCaps.IMAGE_METADATA, length - offset).toInt())
}

private class FileBytes(private val handle: RandomAccessFile) : Bytes {
    override val length: Long = handle.length()

    override fun at(offset: Long, count: Int): ByteArray? =
        if (offset < 0 || count < 0 || offset > length - count) {
            null
        } else {
            ByteArray(count).also {
                handle.seek(offset)
                handle.readFully(it)
            }
        }
}

private class ArrayBytes(private val array: ByteArray) : Bytes {
    override val length: Long = array.size.toLong()

    override fun at(offset: Long, count: Int): ByteArray? =
        if (offset < 0 || count < 0 || offset > length - count) null else array.copyOfRange(offset.toInt(), offset.toInt() + count)
}

/**
 * The big-endian fields of a box, read in turn: 0, 1, 2, 4 or 8 bytes. A field past the box's end, or of any
 * other width, is null, and so is every one after it.
 */
private class Fields(private val array: ByteArray, box: Chunk) {
    private var at = box.contentAt
    private val end = minOf(box.contentAt + box.length, array.size.toLong())

    fun skip(count: Int) {
        at += count
    }

    fun number(width: Int): Long? {
        val value = if (at < 0 || at + width > end) {
            null
        } else {
            val i = at.toInt()
            when (width) {
                0 -> 0L
                1 -> array.u8(i).toLong()
                2 -> array.u16be(i).toLong()
                4 -> array.u32be(i)
                8 -> array.u64be(i)
                else -> null
            }
        }
        at += width
        return value
    }

    fun text(count: Int): String? {
        val value = if (at < 0 || at + count > end) null else array.ascii(at.toInt(), count)
        at += count
        return value
    }
}

/**
 * The TIFF structure of an EXIF block (CIPA DC-008): its first directory (IFD0) and, through the pointers it
 * holds, its EXIF and GPS directories. Every read is checked against the block.
 */
private object Tiff {
    private const val MAKE = 0x010F
    private const val MODEL = 0x0110
    private const val ORIENTATION = 0x0112
    private const val SOFTWARE = 0x0131
    private const val EXIF_IFD = 0x8769
    private const val GPS_IFD = 0x8825
    private const val DATE_TIME_ORIGINAL = 0x9003

    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_IFD = 13
    private const val ENTRY_BYTES = 12
    private const val MAGIC = 42

    /** Bytes of a text shown: a date, a make, a model, a software's name are a few dozen. */
    private const val TEXT_BYTES = 128

    private class Entry(val type: Int, val count: Long, val valueAt: Int)

    fun read(block: ByteArray, orientation: Boolean): ImageMetadata {
        val little = when {
            block.size < 8 -> null
            block.ascii(0, 2) == "II" -> true
            block.ascii(0, 2) == "MM" -> false
            else -> null
        }
        val tiff = little?.let { Reader(block, it) }?.takeIf { it.u16(2) == MAGIC } ?: return ImageMetadata()
        val first = tiff.directory(tiff.u32(4))
        val exif = first[EXIF_IFD]?.let(tiff::pointer)?.let(tiff::directory).orEmpty()
        val turn = if (orientation) first[ORIENTATION]?.let(tiff::short)?.takeIf { it in 1..8 } else null
        return ImageMetadata(
            // Its pointer is enough: a GPS directory announced is a GPS directory to remove, readable or not.
            gps = first[GPS_IFD]?.let(tiff::pointer) != null,
            taken = exif[DATE_TIME_ORIGINAL]?.let(tiff::text),
            make = first[MAKE]?.let(tiff::text),
            model = first[MODEL]?.let(tiff::text),
            software = first[SOFTWARE]?.let(tiff::text),
            orientation = turn ?: ImageMetadata.UPRIGHT,
        )
    }

    private class Reader(private val block: ByteArray, private val little: Boolean) {
        fun u16(at: Int): Int = if (little) block.u16le(at) else block.u16be(at)

        fun u32(at: Int): Long = if (little) block.u32le(at) else block.u32be(at)

        /** The entries of the directory at [offset], those that fit in the block; the first of a tag counts. */
        fun directory(offset: Long): Map<Int, Entry> {
            if (offset < 8 || offset > block.size - 2L) return emptyMap()
            val start = offset.toInt()
            val fitting = minOf(u16(start), (block.size - start - 2) / ENTRY_BYTES)
            val entries = HashMap<Int, Entry>(fitting)
            for (i in 0 until fitting) {
                val at = start + 2 + i * ENTRY_BYTES
                entries.putIfAbsent(u16(at), Entry(u16(at + 2), u32(at + 4), at + 8))
            }
            return entries
        }

        /** A directory's offset: a LONG (or an IFD) other than 0. */
        fun pointer(entry: Entry): Long? {
            val isPointer = (entry.type == TYPE_LONG || entry.type == TYPE_IFD) && entry.count >= 1
            return u32(entry.valueAt).takeIf { isPointer && it != 0L }
        }

        fun short(entry: Entry): Int? = u16(entry.valueAt).takeIf { entry.type == TYPE_SHORT && entry.count >= 1 }

        /** An ASCII value, up to its first NUL, without control characters; null when empty or outside the block. */
        fun text(entry: Entry): String? {
            val start = if (entry.count <= 4) entry.valueAt.toLong() else u32(entry.valueAt)
            if (entry.type != TYPE_ASCII || entry.count < 1 || start >= block.size) return null
            val end = start + minOf(entry.count, TEXT_BYTES.toLong(), block.size - start)
            val bytes = block.copyOfRange(start.toInt(), end.toInt())
            val nul = bytes.indexOf(0).takeIf { it >= 0 } ?: bytes.size
            // Cameras write UTF-8 where the standard says ASCII; a byte that is neither shows as U+FFFD.
            return String(bytes, 0, nul, Charsets.UTF_8).filterNot(Char::isISOControl).trim().ifEmpty { null }
        }
    }
}

private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
    size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

private fun ByteArray.ascii(at: Int, count: Int): String =
    if (at < 0 || at + count > size) "" else String(this, at, count, Charsets.ISO_8859_1)

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

private fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

private fun ByteArray.u32be(at: Int): Long = (u16be(at).toLong() shl 16) or u16be(at + 2).toLong()

private fun ByteArray.u32le(at: Int): Long = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)

/** An unsigned 64-bit size, or null when it does not fit a Long — no file is that large. */
private fun ByteArray.u64be(at: Int): Long? = ((u32be(at) shl 32) or u32be(at + 4)).takeIf { it >= 0 }
