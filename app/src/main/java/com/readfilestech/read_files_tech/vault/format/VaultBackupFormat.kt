package com.readfilestech.read_files_tech.vault.format

import java.io.ByteArrayOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Argon2id cost of a `.rftvault`: stated in a v2 header, implied by a v1 one. */
data class Argon2Params(val memoryKB: Int, val iterations: Int)

/**
 * The `.rftvault` backup of the Flutter app (`VaultService.exportToBackup` / `_restoreFromBackup` /
 * `_parseBackupPayload`, 2.15.3), read and written byte for byte. Checked against a backup written
 * on a device by the real 2.15.2 (VaultBackupDeviceTest): the port writes it again identical.
 *
 * File: `"RFTVAULT" ‖ version(1) ‖ reserved(3) ‖ [v2: memoryKB u32 BE ‖ iterations u32 BE] ‖
 * salt(16) ‖ nonce(12) ‖ AES-GCM(payload) ‖ tag(16)`. AAD: v1 the constant `"rftvault-v1"`; v2 the
 * whole header, so altering the Argon2 parameters or the salt breaks the tag.
 *
 * Payload: `count u32 BE` then, per file, `nameLen u16 BE ‖ name UTF-8 ‖ dataLen u32 BE ‖ data`.
 *
 * The header is read on its own ([readHeader]) so the caller can refuse parameters the device cannot
 * afford BEFORE deriving the key (external review of the plan, finding 19).
 */
object VaultBackupFormat {
    const val VERSION_1 = 1
    const val VERSION_2 = 2
    const val MAX_ENTRIES = 100_000

    /** The bounds the 2.15.x enforces on a v2 header; anything outside is a malformed file. */
    private const val MIN_MEMORY_KB = 1_024
    private const val MAX_MEMORY_KB = 1_048_576
    private const val MAX_ITERATIONS = 64
    val ARGON2_MEMORY_KB_RANGE = MIN_MEMORY_KB..MAX_MEMORY_KB
    val ARGON2_ITERATIONS_RANGE = 1..MAX_ITERATIONS

    /** What every backup of the Flutter app used: a v1 header implies it, a v2 one states it. */
    val FLUTTER_EXPORT_PARAMS = Argon2Params(VaultKdf.ARGON2_EXPORT_MEMORY_KB, VaultKdf.ARGON2_EXPORT_ITERATIONS)

    private val MAGIC = "RFTVAULT".toByteArray(Charsets.US_ASCII)
    private const val AAD_V1 = "rftvault-v1"
    private const val RESERVED_LEN = 3
    private const val VERSION_OFFSET = 8
    private const val PARAMS_OFFSET = 12
    private const val PARAMS_LEN = 2 * Int.SIZE_BYTES
    private const val HEADER_V1_LEN = 8 + 1 + RESERVED_LEN + VaultKdf.SALT_LEN + VaultCrypto.NONCE_LEN
    private const val HEADER_V2_LEN = HEADER_V1_LEN + PARAMS_LEN
    private const val MIN_FILE_LEN = 8 + 1 + 16
    private const val U16_MAX = 0xFFFF

    class Header internal constructor(
        val version: Int,
        val params: Argon2Params,
        val salt: ByteArray,
        val nonce: ByteArray,
        val length: Int,
        val aad: ByteArray,
    )

    class Entry(val name: String, val data: ByteArray)

    /** The restorable entries, and the names refused — counted, where the 2.15.x skipped them silently. */
    class Payload(val entries: List<Entry>, val refused: List<Refusal>)

    data class Refusal(val name: String, val reason: String)

    /** @throws VaultFormatException not a `.rftvault`, an unknown version, or parameters out of bounds. */
    fun readHeader(raw: ByteArray): Header {
        ensureFormat(raw.size >= MIN_FILE_LEN) { "file too short" }
        ensureFormat(raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not a .rftvault file" }
        return when (val version = raw[VERSION_OFFSET].toInt()) {
            VERSION_1 -> {
                // The 2.15.x checked only the 25-byte minimum here, and a v1 file cut between 25 and 56
                // bytes failed on an index out of range: a clean format error instead.
                ensureFormat(raw.size >= HEADER_V1_LEN + VaultCrypto.TAG_LEN) { "file too short" }
                header(raw, version, FLUTTER_EXPORT_PARAMS, HEADER_V1_LEN, AAD_V1.toByteArray(Charsets.UTF_8))
            }
            VERSION_2 -> {
                ensureFormat(raw.size >= HEADER_V2_LEN + VaultCrypto.TAG_LEN) { "file too short" }
                val buffer = ByteBuffer.wrap(raw) // big-endian, as `_readU32be`
                val params = Argon2Params(buffer.getInt(PARAMS_OFFSET), buffer.getInt(PARAMS_OFFSET + Int.SIZE_BYTES))
                ensureFormat(params.withinBounds()) { "Argon2 parameters out of bounds" }
                header(raw, version, params, HEADER_V2_LEN, raw.copyOfRange(0, HEADER_V2_LEN))
            }
            else -> throw VaultFormatException("unsupported version $version")
        }
    }

    /** @throws VaultAuthenticationException wrong export password, or an altered file. */
    fun decryptPayload(raw: ByteArray, header: Header, exportKey: ByteArray): ByteArray =
        VaultCrypto.gcmDecrypt(raw.copyOfRange(header.length, raw.size), exportKey, header.nonce, header.aad)

    /** @throws VaultFormatException a truncated payload, too many entries, or a name that is not UTF-8. */
    fun parsePayload(payload: ByteArray): Payload {
        val buffer = ByteBuffer.wrap(payload)
        val entries = mutableListOf<Entry>()
        val refused = mutableListOf<Refusal>()
        try {
            val count = buffer.getInt()
            ensureFormat(count in 0..MAX_ENTRIES) { "invalid number of files" }
            repeat(count) {
                val nameLen = buffer.getShort().toInt() and U16_MAX
                val name = strictUtf8(ByteArray(nameLen).also(buffer::get))
                val dataLen = buffer.getInt()
                ensureFormat(dataLen >= 0 && dataLen <= buffer.remaining()) { "truncated payload" }
                val data = ByteArray(dataLen).also(buffer::get)
                when (val reason = VaultNames.restoreRefusal(name)) {
                    null -> entries += Entry(name, data)
                    else -> {
                        data.fill(0)
                        refused += Refusal(name, reason)
                    }
                }
            }
        } catch (e: BufferUnderflowException) {
            throw VaultFormatException("truncated payload", e)
        }
        return Payload(entries, refused)
    }

    fun encodePayload(entries: List<Entry>): ByteArray = ByteArrayOutputStream().use { out ->
        out.write(u32(entries.size))
        entries.forEach { entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            require(name.size <= U16_MAX) { "file name too long for a backup" }
            out.write(ByteBuffer.allocate(Short.SIZE_BYTES).putShort(name.size.toShort()).array())
            out.write(name)
            out.write(u32(entry.data.size))
            out.write(entry.data)
        }
        out.toByteArray()
    }

    /** A v2 file: the header carries the Argon2 parameters, and the whole header is the AAD. */
    fun writeV2(payload: ByteArray, exportKey: ByteArray, salt: ByteArray, nonce: ByteArray, params: Argon2Params): ByteArray {
        require(salt.size == VaultKdf.SALT_LEN && nonce.size == VaultCrypto.NONCE_LEN) {
            "salt or nonce of the wrong size"
        }
        require(params.withinBounds()) { "Argon2 parameters out of bounds" }
        val header = MAGIC + byteArrayOf(VERSION_2.toByte()) + ByteArray(RESERVED_LEN) +
            u32(params.memoryKB) + u32(params.iterations) + salt + nonce
        return header + VaultCrypto.gcmEncrypt(payload, exportKey, nonce, header)
    }

    private fun Argon2Params.withinBounds() = memoryKB in ARGON2_MEMORY_KB_RANGE && iterations in ARGON2_ITERATIONS_RANGE

    private fun header(raw: ByteArray, version: Int, params: Argon2Params, length: Int, aad: ByteArray): Header {
        val saltStart = length - VaultCrypto.NONCE_LEN - VaultKdf.SALT_LEN
        return Header(
            version = version,
            params = params,
            salt = raw.copyOfRange(saltStart, saltStart + VaultKdf.SALT_LEN),
            nonce = raw.copyOfRange(length - VaultCrypto.NONCE_LEN, length),
            length = length,
            aad = aad,
        )
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

    /** `require`, for the format: a broken file is a [VaultFormatException], not a programming error. */
    private inline fun ensureFormat(condition: Boolean, message: () -> String) {
        if (!condition) throw VaultFormatException(message())
    }

    private fun u32(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
}
