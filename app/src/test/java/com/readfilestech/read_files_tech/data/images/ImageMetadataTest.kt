package com.readfilestech.read_files_tech.data.images

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The EXIF reader of Remove EXIF: on files Pillow and System.Drawing wrote (`tools/corpus/`), then on blocks
 * and containers built here, hostile ones among them — each must come back bounded, never as an exception.
 */
class ImageMetadataTest {
    @TempDir
    lateinit var tmp: File

    private fun file(name: String, bytes: ByteArray) = File(tmp, name).apply { writeBytes(bytes) }

    private fun read(name: String, bytes: ByteArray) = ImageMetadataReader.read(file(name, bytes))

    private val everything = ImageMetadata(
        gps = true,
        taken = "2026:10:14 12:34:56",
        make = "Files Tech Test",
        model = "Pillow camera",
        software = "make_image_inputs.py",
    )

    @Nested
    inner class MadeByOtherSoftware {
        private val tools = File(System.getProperty("user.dir"), "../tools/corpus/image-tools")
        private val image = File(System.getProperty("user.dir"), "../tools/corpus/image")

        private fun corpus(dir: File, name: String) = File(dir, name).also { check(it.isFile) { "missing corpus file: $it" } }

        @Test
        fun `a JPEG by Pillow — GPS, date, camera, software`() {
            assertThat(ImageMetadataReader.read(corpus(tools, "pillow-gps-photo.jpg"))).isEqualTo(everything)
        }

        @Test
        fun `a JPEG by Pillow turned by its orientation 6`() {
            assertThat(ImageMetadataReader.read(corpus(tools, "pillow-gps-orientation-6.jpg"))).isEqualTo(everything.copy(orientation = 6))
        }

        @Test
        fun `a PNG's eXIf chunk and a WebP's EXIF chunk, by Pillow`() {
            assertThat(ImageMetadataReader.read(corpus(tools, "pillow-gps.png"))).isEqualTo(everything)
            assertThat(ImageMetadataReader.read(corpus(tools, "pillow-gps.webp"))).isEqualTo(everything)
        }

        @Test
        fun `a JPEG by System Drawing, orientation 6 and nothing to list`() {
            val metadata = ImageMetadataReader.read(corpus(image, "system-drawing-exif-orientation-6.jpg"))
            assertThat(metadata.orientation).isEqualTo(6)
            assertThat(metadata.isEmpty).isTrue()
        }

        @Test
        fun `a GIF and a BMP carry no EXIF`() {
            assertThat(ImageMetadataReader.read(corpus(image, "system-drawing.gif"))).isEqualTo(ImageMetadata())
            assertThat(ImageMetadataReader.read(corpus(image, "system-drawing.bmp"))).isEqualTo(ImageMetadata())
        }
    }

    @Nested
    inner class Containers {
        private val block = Tiff(ByteOrder.BIG_ENDIAN).standard(orientation = 6)
        private val expected = everything.copy(orientation = 6)

        @Test
        fun `JPEG APP1, after another segment`() {
            assertThat(read("a.jpg", jpeg(block))).isEqualTo(expected)
        }

        @Test
        fun `a little-endian block in a PNG, after the pixels`() {
            val littleBlock = Tiff(ByteOrder.LITTLE_ENDIAN).standard(orientation = 6)
            assertThat(read("a.png", png(littleBlock, beforePixels = false))).isEqualTo(expected)
        }

        @Test
        fun `a WebP's EXIF chunk with the Exif header some writers put there, after an odd-sized chunk`() {
            assertThat(read("a.webp", webp(EXIF_HEADER + block))).isEqualTo(expected)
        }

        @Test
        fun `a HEIF's Exif item, its orientation ignored — HEIF turns its pictures by its own boxes`() {
            assertThat(read("a.heic", heif(block))).isEqualTo(everything)
            // The TIFF header is where the item's first field says, whatever precedes it.
            assertThat(read("b.heic", heif(block, prefix = bytes(1, 2, 3, 4)))).isEqualTo(everything)
        }

        @Test
        fun `a picture without EXIF lists nothing, and a GPS directory alone is enough to warn`() {
            assertThat(read("plain.jpg", jpeg(null))).isEqualTo(ImageMetadata())
            val north = Tiff.Entry(1, 2, 2, byteArrayOf('N'.code.toByte(), 0))
            val gpsOnly = Tiff(ByteOrder.BIG_ENDIAN).build(emptyList(), gps = listOf(north))
            assertThat(read("gps.jpg", jpeg(gpsOnly))).isEqualTo(ImageMetadata(gps = true))
            assertThat(ImageMetadata(gps = true).isEmpty).isFalse()
        }

        @Test
        fun `texts cut at their NUL, control characters dropped, UTF-8 read, malformed bytes replaced`() {
            val tiff = Tiff(ByteOrder.BIG_ENDIAN)
            val raw = "Caméra".toByteArray() + byteArrayOf(0x07, 0xFF.toByte(), 0, 'X'.code.toByte(), 0)
            val metadata = read("t.jpg", jpeg(tiff.build(listOf(Tiff.Entry(MAKE, 2, raw.size.toLong(), raw)))))
            assertThat(metadata.make).isEqualTo("Caméra�")
        }
    }

    @Nested
    inner class Hostile {
        @Test
        fun `an empty file is refused, a file over the cap too, before a byte is read`() {
            assertThrows<UnreadableFileException> { read("empty.jpg", ByteArray(0)) }
            val large = File(tmp, "large.jpg")
            RandomAccessFile(large, "rw").use { it.setLength(FileCaps.IMAGE_FILE + 1) }
            assertThrows<FileTooLargeException> { ImageMetadataReader.read(large) }
        }

        @Test
        fun `a directory announcing 65 535 entries reads those that fit`() {
            val block = Tiff(ByteOrder.BIG_ENDIAN).standard()
            // IFD0's count, right after the 8-byte header.
            block[8] = 0xFF.toByte()
            block[9] = 0xFF.toByte()
            assertThat(read("count.jpg", jpeg(block)).make).isEqualTo("Files Tech Test")
        }

        @Test
        fun `a text announcing 4 G characters, or lying outside the block, reads no more than it holds`() {
            val tiff = Tiff(ByteOrder.BIG_ENDIAN)
            val huge = Tiff.Entry(MAKE, 2, 0xFFFF_FFFFL, ByteArray(300) { 'A'.code.toByte() })
            val outside = Tiff.Entry(MODEL, 2, 100, ByteArray(4)).pointingAt(0x7FFF_0000)
            val metadata = read("text.jpg", jpeg(tiff.build(listOf(huge, outside))))
            assertThat(metadata.make!!.length).isAtMost(128)
            assertThat(metadata.model).isNull()
        }

        @Test
        fun `pointers that loop back to the first directory, or to themselves, end`() {
            val tiff = Tiff(ByteOrder.BIG_ENDIAN)
            val block = tiff.build(listOf(tiff.ascii(MAKE, "Loop")), nextIfd = 8)
            val looping = block + ByteArray(0)
            // An EXIF pointer to IFD0 itself, and a GPS pointer to IFD0: one level read, nothing followed.
            val withPointers = tiff.build(
                listOf(tiff.ascii(MAKE, "Loop"), tiff.long(EXIF_POINTER, 8), tiff.long(GPS_POINTER, 8)),
                nextIfd = 8,
            )
            assertThat(read("loop1.jpg", jpeg(looping)).make).isEqualTo("Loop")
            val metadata = read("loop2.jpg", jpeg(withPointers))
            assertThat(metadata.make).isEqualTo("Loop")
            assertThat(metadata.gps).isTrue()
            assertThat(metadata.taken).isNull()
        }

        @Test
        fun `a thumbnail claiming 50 MB is never read`() {
            val tiff = Tiff(ByteOrder.BIG_ENDIAN)
            val ifd0 = listOf(tiff.ascii(MAKE, "Thumb"))
            // IFD1, the thumbnail's directory: JPEGInterchangeFormat 0x0201 and its length 0x0202.
            val block = tiff.build(ifd0, nextIfd = 64) +
                ByteArray(64) +
                tiff.directory(listOf(tiff.long(0x0201, 200), tiff.long(0x0202, 50L * 1024 * 1024)))
            assertThat(read("thumb.png", png(block, beforePixels = true)).make).isEqualTo("Thumb")
        }

        @Test
        fun `a PNG chunk announcing 2 GB, a WebP chunk announcing 4 GB - read what is there, nothing after`() {
            val block = Tiff(ByteOrder.BIG_ENDIAN).standard()
            val png = png(block, beforePixels = true, declaredLength = 0x7FFF_FFF0)
            assertThat(read("huge.png", png)).isEqualTo(everything)
            val webp = webp(block, padding = 0xFFFF_FFF0L)
            assertThat(read("huge.webp", webp)).isEqualTo(ImageMetadata())
        }

        @Test
        fun `HEIF boxes that lie - an extent out of the file, a size of 1 with a huge large size, a box of size 0`() {
            val block = Tiff(ByteOrder.BIG_ENDIAN).standard()
            assertThat(read("far.heic", heif(block, extentOffset = 0x7FFF_FFF0L))).isEqualTo(ImageMetadata())
            assertThat(read("long.heic", heif(block, extentLength = 0xFFFF_FFFFL))).isEqualTo(everything)
            val ftyp = box("ftyp", "heic".toByteArray() + ByteArray(4))
            val large = ftyp + bytes(0, 0, 0, 1) + "meta".toByteArray() + bytes(0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
            assertThat(read("large.heic", large)).isEqualTo(ImageMetadata())
            val zero = ftyp + bytes(0, 0, 0, 0) + "free".toByteArray() + ByteArray(32)
            assertThat(read("zero.heic", zero)).isEqualTo(ImageMetadata())
        }

        @Test
        fun `cut files, a segment shorter than its length field, and markers past the walking cap`() {
            val whole = jpeg(Tiff(ByteOrder.BIG_ENDIAN).standard())
            assertThat(read("cut.jpg", whole.copyOf(40))).isEqualTo(ImageMetadata())
            assertThat(read("short.jpg", bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x01, 0, 0))).isEqualTo(ImageMetadata())
            val cutPng = png(Tiff(ByteOrder.BIG_ENDIAN).standard(), beforePixels = true).copyOf(60)
            assertThat(read("cut.png", cutPng)).isEqualTo(ImageMetadata())
            // RST markers stand alone: past the cap, the EXIF after them is not looked for.
            val restarts = ByteArrayOutputStream().apply {
                write(bytes(0xFF, 0xD8))
                repeat(FileCaps.IMAGE_METADATA_CHUNKS + 1) { write(bytes(0xFF, 0xD0)) }
                write(whole, 2, whole.size - 2)
            }.toByteArray()
            assertThat(read("restarts.jpg", restarts)).isEqualTo(ImageMetadata())
        }

        @Test
        fun `a text file named jpg, and a block that is not TIFF`() {
            assertThat(read("text.jpg", "hello, this is not a picture".toByteArray())).isEqualTo(ImageMetadata())
            assertThat(read("garbage.jpg", jpeg(ByteArray(40) { 0x41 }))).isEqualTo(ImageMetadata())
        }
    }

    /** An EXIF block (TIFF), written entry by entry: IFD0, then its EXIF and GPS directories if any. */
    private class Tiff(private val order: ByteOrder) {
        class Entry(val tag: Int, val type: Int, val count: Long, val data: ByteArray, val offset: Long? = null) {
            fun pointingAt(offset: Long) = Entry(tag, type, count, data, offset)
        }

        private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(order)

        fun ascii(tag: Int, text: String): Entry {
            val data = text.toByteArray() + 0
            return Entry(tag, 2, data.size.toLong(), data)
        }

        fun short(tag: Int, value: Int) = Entry(tag, 3, 1, buffer(4).putShort(value.toShort()).array())

        fun long(tag: Int, value: Long) = Entry(tag, 4, 1, buffer(4).putInt(value.toInt()).array())

        fun standard(orientation: Int? = null): ByteArray = build(
            listOfNotNull(
                ascii(MAKE, "Files Tech Test"),
                ascii(MODEL, "Pillow camera"),
                ascii(SOFTWARE, "make_image_inputs.py"),
                orientation?.let { short(ORIENTATION, it) },
            ),
            exif = listOf(ascii(DATE_TIME_ORIGINAL, "2026:10:14 12:34:56")),
            gps = listOf(Entry(1, 2, 2, byteArrayOf('N'.code.toByte(), 0))),
        )

        /** The bytes a directory of [entries] takes, with its values that do not fit in their entry. */
        private fun sizeOf(entries: List<Entry>) = 2 + 12 * entries.size + 4 + entries.sumOf(::outOfLine)

        private fun outOfLine(entry: Entry) = if (entry.data.size > 4 && entry.offset == null) entry.data.size else 0

        fun build(ifd0: List<Entry>, exif: List<Entry> = emptyList(), gps: List<Entry> = emptyList(), nextIfd: Long = 0): ByteArray {
            val pointers = (if (exif.isEmpty()) 0 else 1) + (if (gps.isEmpty()) 0 else 1)
            val firstSize = sizeOf(ifd0) + 12 * pointers
            val exifAt = 8L + firstSize
            val gpsAt = exifAt + if (exif.isEmpty()) 0 else sizeOf(exif)
            val first = ifd0 + listOfNotNull(
                exif.takeIf { it.isNotEmpty() }?.let { long(EXIF_POINTER, exifAt) },
                gps.takeIf { it.isNotEmpty() }?.let { long(GPS_POINTER, gpsAt) },
            )
            val mark = if (order == ByteOrder.LITTLE_ENDIAN) "II" else "MM"
            val header = buffer(8).put(mark.toByteArray()).putShort(42).putInt(8).array()
            return header + directory(first, 8, nextIfd) + (if (exif.isEmpty()) ByteArray(0) else directory(exif, exifAt)) +
                (if (gps.isEmpty()) ByteArray(0) else directory(gps, gpsAt))
        }

        /** A directory at [at], its values longer than 4 bytes right after it. */
        fun directory(entries: List<Entry>, at: Long = 0, next: Long = 0): ByteArray {
            val sorted = entries.sortedBy { it.tag }
            val table = buffer(2 + 12 * sorted.size + 4)
            val data = ByteArrayOutputStream()
            var dataAt = at + 2 + 12 * sorted.size + 4
            table.putShort(sorted.size.toShort())
            for (entry in sorted) {
                table.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count.toInt())
                when {
                    entry.offset != null -> table.putInt(entry.offset.toInt())
                    entry.data.size <= 4 -> table.put(entry.data.copyOf(4))
                    else -> {
                        table.putInt(dataAt.toInt())
                        data.write(entry.data)
                        dataAt += entry.data.size
                    }
                }
            }
            table.putInt(next.toInt())
            return table.array() + data.toByteArray()
        }
    }

    private companion object {
        const val MAKE = 0x010F
        const val MODEL = 0x0110
        const val ORIENTATION = 0x0112
        const val SOFTWARE = 0x0131
        const val EXIF_POINTER = 0x8769
        const val GPS_POINTER = 0x8825
        const val DATE_TIME_ORIGINAL = 0x9003
        val EXIF_HEADER = "Exif".toByteArray() + byteArrayOf(0, 0)

        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        fun be32(value: Long) = ByteBuffer.allocate(4).putInt(value.toInt()).array()

        fun be16(value: Int) = ByteBuffer.allocate(2).putShort(value.toShort()).array()

        fun le32(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

        /** SOI, a quantisation table, the EXIF in APP1 (if any), the picture's start, then its end. */
        fun jpeg(block: ByteArray?): ByteArray = ByteArrayOutputStream().apply {
            write(bytes(0xFF, 0xD8))
            write(bytes(0xFF, 0xDB) + be16(2 + 65) + ByteArray(65))
            if (block != null) write(bytes(0xFF, 0xE1) + be16(2 + EXIF_HEADER.size + block.size) + EXIF_HEADER + block)
            write(bytes(0xFF, 0xDA) + be16(8) + ByteArray(6) + ByteArray(32) + bytes(0xFF, 0xD9))
        }.toByteArray()

        /** A PNG chunk, its CRC zero: the reader never checks it. */
        fun chunk(type: String, data: ByteArray, declared: Long = data.size.toLong()) =
            be32(declared) + type.toByteArray() + data + ByteArray(4)

        fun png(block: ByteArray, beforePixels: Boolean, declaredLength: Long = block.size.toLong()): ByteArray {
            val exif = chunk("eXIf", block, declaredLength)
            val pixels = chunk("IDAT", ByteArray(20))
            val ihdr = chunk("IHDR", be32(8) + be32(8) + bytes(8, 6, 0, 0, 0))
            val signature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val body = if (beforePixels) exif + pixels else pixels + exif
            return signature + ihdr + body + if (declaredLength == block.size.toLong()) chunk("IEND", ByteArray(0)) else ByteArray(0)
        }

        /** RIFF, a VP8X, a chunk of odd size (its padding byte after it), then the EXIF chunk. */
        fun webp(block: ByteArray, padding: Long = 3): ByteArray {
            val odd = "ICCP".toByteArray() + le32(padding) + ByteArray(4)
            val vp8x = "VP8X".toByteArray() + le32(10) + ByteArray(10)
            val exif = "EXIF".toByteArray() + le32(block.size.toLong()) + block + if (block.size % 2 == 1) ByteArray(1) else ByteArray(0)
            val body = "WEBP".toByteArray() + vp8x + odd + exif
            return "RIFF".toByteArray() + le32(body.size.toLong()) + body
        }

        fun box(type: String, content: ByteArray) = be32(8L + content.size) + type.toByteArray() + content

        fun fullBox(type: String, version: Int, content: ByteArray) = box(type, bytes(version, 0, 0, 0) + content)

        /**
         * ftyp, meta (iinf: a picture item and the Exif item; iloc version 1, offsets and lengths of 4 bytes), then
         * mdat holding the Exif item: the offset of its TIFF header (6), `Exif\0\0`, the block.
         */
        fun heif(block: ByteArray, extentOffset: Long? = null, extentLength: Long? = null, prefix: ByteArray = EXIF_HEADER): ByteArray {
            val item = be32(prefix.size.toLong()) + prefix + block
            val ftyp = box("ftyp", "heic".toByteArray() + ByteArray(4) + "mif1heic".toByteArray())
            val infePicture = fullBox("infe", 2, be16(1) + be16(0) + "hvc1".toByteArray() + bytes(0))
            val infeExif = fullBox("infe", 2, be16(2) + be16(0) + "Exif".toByteArray() + bytes(0))
            val iinf = fullBox("iinf", 0, be16(2) + infePicture + infeExif)

            fun iloc(exifOffset: Long) = fullBox(
                "iloc",
                1,
                // offset_size 4, length_size 4; base_offset_size 0, index_size 0; two items.
                bytes(0x44, 0x00) + be16(2) +
                    be16(1) + be16(0) + be16(0) + be16(1) + be32(0) + be32(0) +
                    be16(2) + be16(0) + be16(0) + be16(1) + be32(extentOffset ?: exifOffset) + be32(extentLength ?: item.size.toLong()),
            )
            val hdlr = fullBox("hdlr", 0, ByteArray(4) + "pict".toByteArray() + ByteArray(13))
            val metaSize = fullBox("meta", 0, hdlr + iinf + iloc(0)).size
            val exifAt = ftyp.size.toLong() + metaSize + 8
            val meta = fullBox("meta", 0, hdlr + iinf + iloc(exifAt))
            return ftyp + meta + box("mdat", item)
        }
    }
}
