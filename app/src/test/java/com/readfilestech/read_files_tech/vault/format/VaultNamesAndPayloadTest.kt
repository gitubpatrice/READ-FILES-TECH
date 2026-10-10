package com.readfilestech.read_files_tech.vault.format

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class VaultNamesAndPayloadTest {

    // ---------------------------------------------------------------------------------------------
    // Names in the vault directory
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the sentinel is recognised by its exact name - D26`() {
        assertThat(VaultNames.isSentinel("_check.enc")).isTrue()
        assertThat(VaultNames.isVaultFile("_check.enc")).isFalse()
        // The 2.15.x hid this one with endsWith("_check.enc"): out of the list and of every backup.
        assertThat(VaultNames.isVaultFile("rapport_check.enc")).isTrue()
    }

    @Test
    fun `the residue of an interrupted write is not a vault file - D24`() {
        assertThat(VaultNames.isVaultFile("photo.jpg.enc.3-a1b2c3d4.tmp")).isFalse()
        assertThat(VaultNames.isVaultFile("photo.jpg.enc")).isTrue()
    }

    @Test
    fun `restorable names follow the 2_15_x rule, the sentinel excepted - D25`() {
        val refused = listOf(
            "", ".", "..", "../x", "a/b", "a\\b", "a" + Char(0),
            "tab" + Char(9), "a<b", "a>b", "a:b", "a\"b", "a|b", "a?b", "a*b", "_check",
        )
        refused.forEach { assertThat(VaultNames.restoreRefusal(it)).isNotNull() }
        val accepted = listOf("photo.jpg", "Relevé été 2026 ✓.txt", ".hidden", "a..b", "_check.txt", "x_check", "😀")
        accepted.forEach { assertThat(VaultNames.restoreRefusal(it)).isNull() }
    }

    // ---------------------------------------------------------------------------------------------
    // Backup payload
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `refused names are reported, not silently skipped, and their data is wiped`() {
        val payload = rawPayload(
            listOf(
                "ok.txt" to byteArrayOf(1),
                "../evil" to byteArrayOf(2),
                "_check" to byteArrayOf(3),
                "a:b" to byteArrayOf(4),
            ),
        )
        val parsed = VaultBackupFormat.parsePayload(payload)
        assertThat(parsed.entries.map { it.name }).containsExactly("ok.txt")
        assertThat(parsed.refused.map { it.name }).containsExactly("../evil", "_check", "a:b").inOrder()
        assertThat(parsed.refused.first { it.name == "_check" }.reason).contains("sentinel")
    }

    @Test
    fun `a payload round-trips`() {
        val entries = listOf(VaultBackupFormat.Entry("été.txt", "x".toByteArray()), VaultBackupFormat.Entry("vide", ByteArray(0)))
        val parsed = VaultBackupFormat.parsePayload(VaultBackupFormat.encodePayload(entries))
        assertThat(parsed.entries.map { it.name to it.data.toList() }).isEqualTo(entries.map { it.name to it.data.toList() })
    }

    @Test
    fun `truncated or inconsistent payloads are format errors, never a partial restore`() {
        val good = rawPayload(listOf("a.txt" to byteArrayOf(1, 2, 3)))
        val broken = listOf(
            ByteArray(0),
            good.copyOf(3),
            good.copyOf(good.size - 1),
            good.copyOf(4 + 2 + 2),
            u32(2) + good.copyOfRange(4, good.size),
            u32(VaultBackupFormat.MAX_ENTRIES + 1),
            u32(-1),
            u32(1) + byteArrayOf(0, 1) + byteArrayOf(0x41) + u32(Int.MAX_VALUE),
        )
        broken.forEach { assertThrows<VaultFormatException> { VaultBackupFormat.parsePayload(it) } }
    }

    @Test
    fun `a file name that is not UTF-8 is a format error, as Dart's utf8_decode throws`() {
        val payload = u32(1) + byteArrayOf(0, 2) + byteArrayOf(0xC3.toByte(), 0x28) + u32(0)
        assertThrows<VaultFormatException> { VaultBackupFormat.parsePayload(payload) }
    }

    // ---------------------------------------------------------------------------------------------
    // Backup file
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a v2 file written by the port opens again`() {
        val key = ByteArray(VaultCrypto.KEY_LEN) { it.toByte() }
        val salt = ByteArray(VaultKdf.SALT_LEN) { 7 }
        val nonce = ByteArray(VaultCrypto.NONCE_LEN) { 9 }
        val payload = VaultBackupFormat.encodePayload(listOf(VaultBackupFormat.Entry("a", byteArrayOf(42))))
        val file = VaultBackupFormat.writeV2(payload, key, salt, nonce, Argon2Params(memoryKB = 65_536, iterations = 3))
        val header = VaultBackupFormat.readHeader(file)
        assertThat(header.params).isEqualTo(Argon2Params(65_536, 3))
        assertThat(header.salt).isEqualTo(salt)
        assertThat(VaultBackupFormat.decryptPayload(file, header, key)).isEqualTo(payload)
    }

    @Test
    fun `not a backup, an unknown version, or a cut v1 file are format errors`() {
        val v1Header = "RFTVAULT".toByteArray() + byteArrayOf(1, 0, 0, 0)
        val cases = listOf(
            ByteArray(10),
            "NOTVAULT".toByteArray() + ByteArray(40),
            "RFTVAULT".toByteArray() + byteArrayOf(3) + ByteArray(60),
            // Between the 25-byte minimum and a full v1 header plus tag: an index error in the 2.15.x.
            v1Header + ByteArray(20),
        )
        cases.forEach { assertThrows<VaultFormatException> { VaultBackupFormat.readHeader(it) } }
    }

    private fun rawPayload(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().use { out ->
        out.write(u32(entries.size))
        entries.forEach { (name, data) ->
            val bytes = name.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()))
            out.write(bytes)
            out.write(u32(data.size))
            out.write(data)
        }
        out.toByteArray()
    }

    private fun u32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
}
