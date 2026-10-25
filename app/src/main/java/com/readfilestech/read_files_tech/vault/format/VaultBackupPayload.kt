package com.readfilestech.read_files_tech.vault.format

import com.readfilestech.read_files_tech.data.DartText
import com.readfilestech.read_files_tech.data.files.LimitedInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What a `.rftvault` envelope holds (`_parseBackupPayload` and the payload half of `exportToBackup`,
 * 2.15.3): `count u32 BE` then, per file, `nameLen u16 BE ‖ name UTF-8 ‖ dataLen u32 BE ‖ data`.
 * Read and written as a stream ([readPayload], [PayloadWriter]): one entry can be most of a 240 MB file.
 */
object VaultBackupPayload {
    const val MAX_ENTRIES = 100_000

    private const val U16_MAX = 0xFFFF
    private const val U32_MAX = 0xFFFF_FFFFL

    class Entry(val name: String, val data: ByteArray)

    /** The restorable entries, and the names refused — counted, where the 2.15.x skipped them silently. */
    class Payload(val entries: List<Entry>, val refused: List<Refusal>)

    data class Refusal(val name: String, val reason: String)

    /** Receives each restorable entry of [readPayload]: its name, its [size], and its data to read. */
    fun interface EntrySink {
        fun accept(name: String, size: Long, data: InputStream)
    }

    /**
     * Reads the payload from [input], [payloadLength] bytes at most, handing each restorable entry to
     * [sink] and returning the refused names. A refused entry's data is skipped, never copied. What the
     * sink leaves unread of an entry is skipped too. Bytes after the last entry are ignored, as by the
     * 2.15.x.
     *
     * @throws VaultFormatException a truncated payload, too many entries, or a name that is not UTF-8.
     */
    fun readPayload(input: InputStream, payloadLength: Long, sink: EntrySink): List<Refusal> {
        val data = DataInputStream(input)
        val refused = mutableListOf<Refusal>()
        var remaining = payloadLength
        try {
            val count = data.readInt()
            remaining -= Int.SIZE_BYTES
            ensureFormat(count in 0..MAX_ENTRIES) { "invalid number of files" }
            repeat(count) {
                val nameLen = data.readUnsignedShort()
                val name = strictUtf8(ByteArray(nameLen).also(data::readFully))
                val dataLen = Integer.toUnsignedLong(data.readInt())
                remaining -= Short.SIZE_BYTES + nameLen + Int.SIZE_BYTES
                ensureFormat(remaining >= 0 && dataLen <= remaining) { "truncated payload" }
                remaining -= dataLen
                val entry = LimitedInput(data, dataLen)
                when (val reason = VaultNames.refusal(name)) {
                    null -> sink.accept(name, dataLen, entry)
                    else -> refused += Refusal(name, reason)
                }
                entry.skipRest()
            }
        } catch (e: EOFException) {
            throw VaultFormatException("truncated payload", e)
        }
        return refused
    }

    /**
     * [readPayload] in one block, every restorable entry copied out. On a format error the copies made
     * so far are wiped: the caller never gets them.
     */
    fun parsePayload(payload: ByteArray): Payload {
        val entries = mutableListOf<Entry>()
        var complete = false
        try {
            val refused = readPayload(ByteArrayInputStream(payload), payload.size.toLong()) { name, size, data ->
                val bytes = ByteArray(size.toInt())
                entries += Entry(name, bytes)
                DataInputStream(data).readFully(bytes)
            }
            complete = true
            return Payload(entries, refused)
        } finally {
            if (!complete) entries.forEach { it.data.fill(0) }
        }
    }

    fun encodePayload(entries: List<Entry>): ByteArray = ByteArrayOutputStream().use { out ->
        val writer = PayloadWriter(out, entries.size)
        entries.forEach { entry -> writer.entry(entry.name, entry.data.size.toLong()) { it.write(entry.data) } }
        writer.finish()
        out.toByteArray()
    }

    /**
     * Writes a payload of [count] entries to [out], each declared with its size before its data — the
     * order the format imposes. Writing more or fewer entries, or another number of bytes than
     * declared, is refused: the file would be unreadable. So is what [readPayload] would refuse — more
     * than [MAX_ENTRIES] files, a name [VaultNames.refusal] refuses: a backup must not be written with
     * a file it cannot give back (external review of 2026-10-11).
     */
    class PayloadWriter(private val out: OutputStream, private val count: Int) {
        private var written = 0

        init {
            require(count in 0..MAX_ENTRIES) { "invalid number of files" }
            out.write(u32(count.toLong()))
        }

        fun entry(name: String, size: Long, writeData: (OutputStream) -> Unit) {
            check(written < count) { "more entries than declared" }
            VaultNames.refusal(name)?.let { throw IllegalArgumentException("$name: $it") }
            val nameBytes = DartText.utf8(name)
            require(nameBytes.size <= U16_MAX) { "file name too long for a backup" }
            require(size in 0..U32_MAX) { "file too large for a backup" }
            out.write(ByteBuffer.allocate(Short.SIZE_BYTES).putShort(nameBytes.size.toShort()).array())
            out.write(nameBytes)
            out.write(u32(size))
            val counting = CountingOutput(out)
            writeData(counting)
            check(counting.count == size) { "$name: ${counting.count} bytes written, $size declared" }
            written++
        }

        fun finish() = check(written == count) { "$written entries written, $count declared" }
    }

    /** Dart's `utf8.decode` throws on malformed bytes; Kotlin's default decoder would replace them silently. */
    private fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw VaultFormatException("a file name is not valid UTF-8", e)
    }

    private class CountingOutput(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
