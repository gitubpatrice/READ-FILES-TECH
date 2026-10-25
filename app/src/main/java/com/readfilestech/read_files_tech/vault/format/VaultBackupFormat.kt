package com.readfilestech.read_files_tech.vault.format

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Argon2id cost of a `.rftvault`: stated in a v2 header, implied by a v1 one. */
data class Argon2Params(val memoryKB: Int, val iterations: Int)

/**
 * The `.rftvault` backup of the Flutter app (`VaultService.exportToBackup` / `_restoreFromBackup`,
 * 2.15.3): its header and its envelope, read and written byte for byte. Checked against a backup
 * written on a device by the real 2.15.2 (VaultBackupDeviceTest): the port writes it again identical.
 * What the envelope holds is VaultBackupPayload.
 *
 * File: `"RFTVAULT" ‖ version(1) ‖ reserved(3) ‖ [v2: memoryKB u32 BE ‖ iterations u32 BE] ‖
 * salt(16) ‖ nonce(12) ‖ AES-GCM(payload) ‖ tag(16)`. AAD: v1 the constant `"rftvault-v1"`; v2 the
 * whole header, so altering the Argon2 parameters or the salt breaks the tag.
 *
 * The header is read on its own ([readHeader]) so the caller can refuse parameters the device cannot
 * afford BEFORE deriving the key (external review of the plan, finding 19). The envelope opens in one
 * block or as a stream: a backup goes up to 240 MB, three times what a phone's heap holds (docs D-023).
 */
object VaultBackupFormat {
    const val VERSION_1 = 1
    const val VERSION_2 = 2

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

    /** Read at least this many bytes (or the whole file if shorter) before [readHeader]. */
    const val MAX_HEADER_LEN = HEADER_V2_LEN

    class Header internal constructor(
        val version: Int,
        val params: Argon2Params,
        val salt: ByteArray,
        val nonce: ByteArray,
        val length: Int,
        val aad: ByteArray,
    )

    /** @throws VaultFormatException not a `.rftvault`, an unknown version, or parameters out of bounds. */
    fun readHeader(raw: ByteArray): Header = readHeader(raw, raw.size.toLong())

    /**
     * The header, from the first bytes of a file of [fileLength] bytes ([head] holds at least
     * [MAX_HEADER_LEN] of them, or the whole file).
     */
    fun readHeader(head: ByteArray, fileLength: Long): Header {
        ensureFormat(fileLength >= MIN_FILE_LEN && head.size >= MIN_FILE_LEN) { "file too short" }
        ensureFormat(head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not a .rftvault file" }
        return when (val version = head[VERSION_OFFSET].toInt()) {
            VERSION_1 -> {
                // The 2.15.x checked only the 25-byte minimum here, and a v1 file cut between 25 and 56
                // bytes failed on an index out of range: a clean format error instead.
                ensureFormat(fileLength >= HEADER_V1_LEN + VaultCrypto.TAG_LEN && head.size >= HEADER_V1_LEN) { "file too short" }
                header(head, version, FLUTTER_EXPORT_PARAMS, HEADER_V1_LEN, AAD_V1.toByteArray(Charsets.UTF_8))
            }
            VERSION_2 -> {
                ensureFormat(fileLength >= HEADER_V2_LEN + VaultCrypto.TAG_LEN && head.size >= HEADER_V2_LEN) { "file too short" }
                val buffer = ByteBuffer.wrap(head) // big-endian, as `_readU32be`
                val params = Argon2Params(buffer.getInt(PARAMS_OFFSET), buffer.getInt(PARAMS_OFFSET + Int.SIZE_BYTES))
                ensureFormat(params.withinBounds()) { "Argon2 parameters out of bounds" }
                header(head, version, params, HEADER_V2_LEN, head.copyOfRange(0, HEADER_V2_LEN))
            }
            else -> throw VaultFormatException("unsupported version $version")
        }
    }

    /** In one block. @throws VaultAuthenticationException wrong export password, or an altered file. */
    fun decryptPayload(raw: ByteArray, header: Header, exportKey: ByteArray): ByteArray =
        Gcm.open(raw, header.length, exportKey, header.nonce, header.aad)

    /**
     * The payload as a stream, from [afterHeader] positioned just after the header. ⚠️ UNVERIFIED until
     * its end (Gcm.OpeningStream): nothing read from it may be published before the end is reached.
     */
    fun openPayload(afterHeader: InputStream, header: Header, exportKey: ByteArray): InputStream =
        Gcm.OpeningStream(afterHeader, exportKey, header.nonce, header.aad)

    /** The v2 header: it carries the Argon2 parameters, and the whole of it is the AAD. */
    fun headerV2(salt: ByteArray, nonce: ByteArray, params: Argon2Params): ByteArray {
        require(salt.size == VaultKdf.SALT_LEN && nonce.size == VaultCrypto.NONCE_LEN) {
            "salt or nonce of the wrong size"
        }
        require(params.withinBounds()) { "Argon2 parameters out of bounds" }
        return MAGIC + byteArrayOf(VERSION_2.toByte()) + ByteArray(RESERVED_LEN) +
            u32(params.memoryKB.toLong()) + u32(params.iterations.toLong()) + salt + nonce
    }

    /** A v2 file in one block. */
    fun writeV2(payload: ByteArray, exportKey: ByteArray, salt: ByteArray, nonce: ByteArray, params: Argon2Params): ByteArray {
        val header = headerV2(salt, nonce, params)
        return header + Gcm.seal(payload, exportKey, nonce, header)
    }

    /** A v2 file to [out] as a stream: [writePayload] writes the payload in clear into the stream it gets. */
    @Suppress("LongParameterList") // the file's own fields: key, salt, nonce, parameters, and its content
    fun writeV2(
        out: OutputStream,
        exportKey: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        params: Argon2Params,
        writePayload: (OutputStream) -> Unit,
    ) {
        val header = headerV2(salt, nonce, params)
        out.write(header)
        val sealing = Gcm.SealingStream(out, exportKey, nonce, header)
        writePayload(sealing)
        sealing.finish()
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
}

/** `require`, for the formats: a broken file is a [VaultFormatException], not a programming error. */
internal inline fun ensureFormat(condition: Boolean, message: () -> String) {
    if (!condition) throw VaultFormatException(message())
}

/** A big-endian u32, as `u32be` of `vault_bytes.dart`. */
internal fun u32(value: Long): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.toInt()).array()
