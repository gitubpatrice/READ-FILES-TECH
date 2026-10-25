package com.readfilestech.read_files_tech.data.viewer.sheet

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * .xls files written for the tests, record by record, and their OLE container sector by sector — never by
 * POI, the reader under test. [Biff] writes the workbook stream (BIFF8, [MS-XLS]); [ole] wraps it in a
 * compound file of version 4 ([MS-CFB], 4 096-byte sectors).
 */
internal object XlsTesting {
    private const val SECTOR = 4096
    private const val FAT_ENTRIES = SECTOR / 4
    private const val HEADER_DIFAT = 109
    private const val END_OF_CHAIN = -2
    private const val FAT_SECTOR = -3
    private const val FREE = -1
    private const val NO_ENTRY = -1
    private const val DIRECTORY_ENTRY = 128

    /** A compound file at [file] holding [stream] as its "Workbook" stream, written as it goes (no copy of it). */
    fun ole(file: File, stream: Biff): File {
        // A stream under 4 096 bytes would live in the mini stream: padded with zeros, which follow the last EOF.
        val size = maxOf(stream.size(), SECTOR)
        val dataSectors = (size + SECTOR - 1) / SECTOR
        var fatSectors = 1
        while (fatSectors * FAT_ENTRIES < dataSectors + 1 + fatSectors) fatSectors++
        check(fatSectors <= HEADER_DIFAT) { "too large for a header's allocation table" }
        val directory = dataSectors
        file.outputStream().buffered().use { out ->
            Le().also { header(it, fatSectors, directory, firstFat = directory + 1) }.writeTo(out)
            stream.writeTo(out)
            out.write(ByteArray(dataSectors * SECTOR - stream.size()))
            Le().also { directory(it, size.toLong()) }.writeTo(out)
            val fat = Le()
            for (i in 0 until fatSectors * FAT_ENTRIES) {
                fat.u32(
                    when {
                        i < dataSectors - 1 -> i + 1
                        i == dataSectors - 1 || i == directory -> END_OF_CHAIN
                        i <= directory + fatSectors -> FAT_SECTOR
                        else -> FREE
                    }
                )
            }
            fat.writeTo(out)
        }
        return file
    }

    /** A file of the size of one sector whose header says it holds [fatSectors] allocation sectors of 512 bytes. */
    fun oleHeaderOnly(file: File, fatSectors: Int): File {
        val out = Le()
        out.bytes(SIGNATURE).zeros(16).u16(0x3E).u16(3).u16(0xFFFE).u16(9).u16(6).zeros(6)
        out.u32(0).u32(fatSectors).u32(1).u32(0).u32(SECTOR).u32(END_OF_CHAIN).u32(0).u32(END_OF_CHAIN).u32(0)
        repeat(HEADER_DIFAT) { out.u32(if (it < fatSectors) it + 2 else FREE) }
        out.zeros(SECTOR - out.size())
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun header(out: Le, fatSectors: Int, directory: Int, firstFat: Int) {
        out.bytes(SIGNATURE).zeros(16).u16(0x3E).u16(4).u16(0xFFFE).u16(12).u16(6).zeros(6)
        out.u32(1).u32(fatSectors).u32(directory).u32(0).u32(SECTOR).u32(END_OF_CHAIN).u32(0).u32(END_OF_CHAIN).u32(0)
        repeat(HEADER_DIFAT) { out.u32(if (it < fatSectors) firstFat + it else FREE) }
        out.zeros(SECTOR - out.size())
    }

    /** The root entry, then the "Workbook" stream from sector 0, then empty entries to the sector's end. */
    private fun directory(out: Le, size: Long) {
        entry("Root Entry", type = 5, child = 1, start = END_OF_CHAIN, size = 0).writeTo(out)
        entry("Workbook", type = 2, child = NO_ENTRY, start = 0, size = size).writeTo(out)
        out.zeros(SECTOR - 2 * DIRECTORY_ENTRY)
    }

    /** A directory entry: its name, its type (5 the root, 2 a stream), black, no sibling. */
    private fun entry(name: String, type: Int, child: Int, start: Int, size: Long): Le {
        val chars = name.toByteArray(Charsets.UTF_16LE)
        return Le().bytes(chars).zeros(64 - chars.size).u16(chars.size + 2).u8(type).u8(1)
            .u32(NO_ENTRY).u32(NO_ENTRY).u32(child).zeros(16 + 4 + 8 + 8).u32(start).u32(size.toInt()).u32(0)
    }

    private val SIGNATURE =
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
}

/** Little-endian bytes. */
internal class Le : ByteArrayOutputStream() {
    fun u8(value: Int) = apply { write(value) }

    fun u16(value: Int) = apply { u8(value and 0xFF).u8(value ushr 8 and 0xFF) }

    fun u32(value: Int) = apply { u16(value and 0xFFFF).u16(value ushr 16) }

    fun u64(value: Long) = apply { u32(value.toInt()).u32((value ushr 32).toInt()) }

    fun bytes(value: ByteArray) = apply { write(value) }

    fun zeros(count: Int) = apply { write(ByteArray(count)) }

    /** A string as BIFF8 writes one after its length: a byte of flags, then its chars, one byte each. */
    fun latin1(text: String) = apply { u8(0).bytes(text.toByteArray(Charsets.ISO_8859_1)) }
}

/** A BIFF8 workbook stream, record by record ([MS-XLS] 2.4). */
internal class Biff {
    private val out = Le()

    /** The stream's bytes so far. */
    fun size(): Int = out.size()

    fun writeTo(stream: OutputStream) = out.writeTo(stream)

    fun record(sid: Int, data: Le.() -> Unit) = apply {
        val body = Le().apply(data)
        check(body.size() <= MAX_DATA) { "a record of ${body.size()} bytes" }
        out.u16(sid).u16(body.size())
        body.writeTo(out)
    }

    fun bof(type: Int) = record(0x0809) { u16(0x0600).u16(type).u16(0x0DBB).u16(0x07CC).u32(0).u32(6) }

    fun eof() = record(0x000A) {}

    fun sheet(name: String) = record(0x0085) { u32(0).u8(0).u8(0).u8(name.length).latin1(name) }

    fun format(index: Int, code: String) = record(0x041E) { u16(index).u16(code.length).latin1(code) }

    /** A cell style (XF) of number format [format]. */
    fun style(format: Int) = record(0x00E0) { u16(0).u16(format).u16(0).u16(0x20).u16(0).u32(0).u32(0).u16(0) }

    fun date1904() = record(0x0022) { u16(1) }

    /**
     * The shared strings table: [strings] in an SST record and the CONTINUEs it takes, a string's chars cut
     * across two of them when it does not fit (the CONTINUE then starts with the chars' flags byte). [unique]
     * and [total] are the counts the table declares.
     */
    fun sst(strings: List<String>, unique: Int = strings.size, total: Int = strings.size) = sst(strings.size, unique, total, strings::get)

    /** The table of [count] strings, the one of each index made by [text] as it is written. */
    fun sst(count: Int, unique: Int, total: Int, text: (Int) -> String) = apply {
        var sid = 0x00FC
        var body = Le().u32(total).u32(unique)
        fun flush() {
            out.u16(sid).u16(body.size())
            body.writeTo(out)
            sid = 0x003C
            body = Le()
        }
        for (index in 0 until count) {
            if (body.size() + 3 > MAX_DATA) flush()
            var chars = text(index).toByteArray(Charsets.ISO_8859_1)
            body.u16(chars.size).u8(0)
            while (body.size() + chars.size > MAX_DATA) {
                val fits = MAX_DATA - body.size()
                body.bytes(chars.copyOf(fits))
                chars = chars.copyOfRange(fits, chars.size)
                flush()
                body.u8(0)
            }
            body.bytes(chars)
        }
        flush()
    }

    fun number(row: Int, column: Int, value: Double, style: Int = 0) =
        record(0x0203) { u16(row).u16(column).u16(style).u64(value.toRawBits()) }

    fun shared(row: Int, column: Int, index: Int) = record(0x00FD) { u16(row).u16(column).u16(0).u32(index) }

    fun label(row: Int, column: Int, text: String) = record(0x0204) {
        u16(row).u16(column).u16(0).u16(text.length).latin1(text)
    }

    fun bool(row: Int, column: Int, value: Int, error: Boolean = false) =
        record(0x0205) { u16(row).u16(column).u16(0).u8(value).u8(if (error) 1 else 0) }

    /** A formula (empty: no token) whose cached result is [result] (a number, or a special value's bits). */
    fun formula(row: Int, column: Int, result: Long, style: Int = 0) =
        record(0x0006) { u16(row).u16(column).u16(style).u64(result).u16(0).u32(0).u16(0) }

    /** A formula's string result. */
    fun string(text: String) = record(0x0207) { u16(text.length).latin1(text) }

    /** A shared formula, as it comes between a formula and its STRING: its content is never read. */
    fun sharedFormula() = record(0x04BC) { u16(0).u16(0).u8(0).u8(0).u8(0).u8(1).u16(0) }

    companion object {
        /** BIFF8's longest record data. */
        const val MAX_DATA = 8224
        const val GLOBALS = 0x0005
        const val WORKSHEET = 0x0010
        const val CHART = 0x0020

        /** A formula's cached special value: a text (in the STRING after it), a boolean, an error. */
        fun special(type: Int, value: Int = 0): Long = (0xFFFFL shl 48) or (value.toLong() shl 16) or type.toLong()
    }
}
