package com.readfilestech.read_files_tech.vault.format

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
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
        val payload = VaultBackupFormat.parsePayload(decrypt(header))
        assertThat(payload.refused).isEmpty()
        assertThat(payload.entries.associate { it.name to sha256(it.data) }).isEqualTo(expected)
    }

    @Test
    fun `the port writes that backup again byte for byte - payload and envelope`() {
        val header = VaultBackupFormat.readHeader(raw)
        val key = VaultKdf.argon2id(password, header.salt, header.params.memoryKB, header.params.iterations)
        val payload = VaultBackupFormat.decryptPayload(raw, header, key)
        val reencoded = VaultBackupFormat.encodePayload(VaultBackupFormat.parsePayload(payload).entries)
        assertThat(reencoded).isEqualTo(payload)
        val rewritten = VaultBackupFormat.writeV2(reencoded, key, header.salt, header.nonce, header.params)
        assertThat(rewritten).isEqualTo(raw)
    }

    private fun decrypt(header: VaultBackupFormat.Header): ByteArray {
        val key = VaultKdf.argon2id(password, header.salt, header.params.memoryKB, header.params.iterations)
        return VaultBackupFormat.decryptPayload(raw, header, key)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
