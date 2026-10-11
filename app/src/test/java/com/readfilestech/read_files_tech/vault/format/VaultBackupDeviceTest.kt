package com.readfilestech.read_files_tech.vault.format

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * A `.rftvault` written ON A DEVICE by Read Files Tech 2.15.2 (API 34 emulator, 2026-10-09), through
 * `VaultService.exportToBackup` — the real code, not a reconstruction. Fingerprints recorded by the
 * Flutter build when the files were imported (logcat `RFTSEED IMPORT`).
 */
class VaultBackupDeviceTest {

    private val raw = javaClass.getResource("/device/emu34.rftvault")!!.readBytes()
    private val password = "Sauvegarde ✓ export"

    private val expected = mapOf(
        "Relevé été 2026 ✓.txt" to "646385b512e5eb78e7153ad409dde88297fbaa4df1296b43e66f821ce5029428",
        "vide.txt" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "photo-200k.jpg" to "27c643827cee16fbff7f10f3243dceb9ad78d15214b8b406b63c8f95bbb0c489",
        "scan-2M.pdf" to "39bdab5cef6cc135ae3419453ca59031a3e34deee73ac44b0c88c5d580d32401",
        "gros-6M.bin" to "11707b8478c614f584ba01761ff13a254a034aa903df0838c2c96f7c678f3c71",
    )

    @Test
    fun `the backup the 2_15_2 wrote on a device restores identical`() {
        val header = VaultBackupFormat.readHeader(raw)
        assertThat(header.version).isEqualTo(VaultBackupFormat.VERSION_2)
        val payload = VaultBackupPayload.parsePayload(decrypt(header))
        assertThat(payload.refused).isEmpty()
        assertThat(payload.entries.associate { it.name to sha256(it.data) }).isEqualTo(expected)
    }

    @Test
    fun `the port writes that backup again byte for byte - payload and envelope`() {
        val header = VaultBackupFormat.readHeader(raw)
        val key = VaultKdf.argon2id(password, header.salt, header.params.memoryKB, header.params.iterations)
        val payload = VaultBackupFormat.decryptPayload(raw, header, key)
        val reencoded = VaultBackupPayload.encodePayload(VaultBackupPayload.parsePayload(payload).entries)
        assertThat(reencoded).isEqualTo(payload)
        val rewritten = VaultBackupFormat.writeV2(reencoded, key, header.salt, header.nonce, header.params)
        assertThat(rewritten).isEqualTo(raw)
    }

    @Test
    fun `the same backup restores identical through the stream, no entry held whole`() {
        val header = VaultBackupFormat.readHeader(raw.copyOf(VaultBackupFormat.MAX_HEADER_LEN), raw.size.toLong())
        val key = VaultKdf.argon2id(password, header.salt, header.params.memoryKB, header.params.iterations)
        val payload = VaultBackupFormat.openPayload(ByteArrayInputStream(raw, header.length, raw.size - header.length), header, key)
        val digests = mutableMapOf<String, String>()
        val payloadLength = (raw.size - header.length - VaultCrypto.TAG_LEN).toLong()
        val refused = VaultBackupPayload.readPayload(payload, payloadLength) { name, size, data ->
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(4096) // smaller than the 6 MB entry: it is read in pieces
            var total = 0L
            while (true) {
                val n = data.read(chunk)
                if (n == -1) break
                digest.update(chunk, 0, n)
                total += n
            }
            assertThat(total).isEqualTo(size)
            digests[name] = digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertThat(payload.read()).isEqualTo(-1)
        assertThat(refused).isEmpty()
        assertThat(digests).isEqualTo(expected)
    }

    @Test
    fun `the port streams that backup again byte for byte`() {
        val header = VaultBackupFormat.readHeader(raw)
        val key = VaultKdf.argon2id(password, header.salt, header.params.memoryKB, header.params.iterations)
        val entries = VaultBackupPayload.parsePayload(VaultBackupFormat.decryptPayload(raw, header, key)).entries
        val out = ByteArrayOutputStream()
        VaultBackupFormat.writeV2(out, key, header.salt, header.nonce, header.params) { sealing ->
            val writer = VaultBackupPayload.PayloadWriter(sealing, entries.size)
            entries.forEach { entry -> writer.entry(entry.name, entry.data.size.toLong()) { it.write(entry.data) } }
            writer.finish()
        }
        assertThat(out.toByteArray()).isEqualTo(raw)
    }

    private fun decrypt(header: VaultBackupFormat.Header): ByteArray {
        val key = VaultKdf.argon2id(password, header.salt, header.params.memoryKB, header.params.iterations)
        return VaultBackupFormat.decryptPayload(raw, header, key)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
