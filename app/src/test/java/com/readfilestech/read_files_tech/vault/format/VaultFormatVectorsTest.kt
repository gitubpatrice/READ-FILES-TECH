package com.readfilestech.read_files_tech.vault.format

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Vectors produced by the REAL Dart code of the Flutter vault, 2.15.2 (`lib/services/vault/`), with
 * tools/dart-vectors/gen_vault_vectors.dart. One byte off and the port could not open existing vaults.
 * (The `.rftvault` envelopes of this file are rebuilt by hand in Dart; VaultBackupDeviceTest checks a
 * backup written by the app itself.)
 */
class VaultFormatVectorsTest {

    private val vectors = JSONObject(javaClass.getResource("/vault-vectors-2.15.2.json")!!.readText())

    @Test
    fun `every key the Dart code derived is found byte for byte`() {
        val salt = hex(vectors.getString("salt"))
        val cases = vectors.getJSONArray("kdf")
        var pbkdf2 = 0
        var argon2 = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val password = case.getString("password")
            val key = when (case.getString("kdf")) {
                "pbkdf2" -> VaultKdf.pbkdf2(password, salt).also { pbkdf2++ }
                "argon2id" -> VaultKdf.argon2id(password, salt, case.getInt("memoryKB"), case.getInt("iterations")).also { argon2++ }
                else -> error("unknown KDF in the vectors")
            }
            assertThat(key).isEqualTo(hex(case.getString("key")))
        }
        assertThat(pbkdf2).isEqualTo(2)
        assertThat(argon2).isEqualTo(12)
    }

    @Test
    fun `the Dart v2 and v1 blobs decrypt`() {
        val key = hex(vectors.getString("blobKey"))
        val blobs = vectors.getJSONArray("blobs")
        for (i in 0 until blobs.length()) {
            val blob = blobs.getJSONObject(i)
            val plain = VaultCrypto.decryptAuto(hex(blob.getString("blob")), key, blob.getString("filename"), v2Only = false)
            assertThat(plain).isEqualTo(hex(blob.getString("plain")))
        }
        assertThat(blobs.length()).isEqualTo(6)
    }

    @Test
    fun `Kotlin encryption gives the same v2 blob as Dart`() {
        val key = hex(vectors.getString("blobKey"))
        val blobs = vectors.getJSONArray("blobs")
        var compared = 0
        for (i in 0 until blobs.length()) {
            val blob = blobs.getJSONObject(i)
            if (blob.getString("format") != "v2") continue
            val expected = hex(blob.getString("blob"))
            val nonce = expected.copyOfRange(4, 4 + VaultCrypto.NONCE_LEN)
            assertThat(VaultCrypto.encryptV2(hex(blob.getString("plain")), key, nonce, blob.getString("filename"))).isEqualTo(expected)
            compared++
        }
        assertThat(compared).isEqualTo(3)
    }

    @Test
    fun `the Dart v2 and v1 blobs decrypt as a stream too`() {
        val key = hex(vectors.getString("blobKey"))
        val blobs = vectors.getJSONArray("blobs")
        for (i in 0 until blobs.length()) {
            val blob = blobs.getJSONObject(i)
            val bytes = hex(blob.getString("blob"))
            val out = ByteArrayOutputStream()
            VaultCrypto.decryptAuto(ByteArrayInputStream(bytes), bytes.size.toLong(), out, key, blob.getString("filename"), v2Only = false)
            assertThat(out.toByteArray()).isEqualTo(hex(blob.getString("plain")))
        }
    }

    @Test
    fun `Kotlin stream encryption gives the same v2 blob as Dart`() {
        val key = hex(vectors.getString("blobKey"))
        var compared = 0
        for (blob in blobs("v2")) {
            val expected = hex(blob.getString("blob"))
            val nonce = expected.copyOfRange(4, 4 + VaultCrypto.NONCE_LEN)
            val out = ByteArrayOutputStream()
            VaultCrypto.encryptV2(ByteArrayInputStream(hex(blob.getString("plain"))), out, key, nonce, blob.getString("filename"))
            assertThat(out.toByteArray()).isEqualTo(expected)
            compared++
        }
        assertThat(compared).isEqualTo(3)
    }

    @Test
    fun `streamed, a renamed blob fails at its end and a v1 blob is refused on a v2-only vault`() {
        val key = hex(vectors.getString("blobKey"))
        val v2 = blobs("v2").first { it.getString("filename") != VaultNames.SENTINEL_FILE }
        assertThrows<VaultAuthenticationException> { streamDecrypt(hex(v2.getString("blob")), key, "other.pdf.enc") }
        val v1 = blobs("v1").first()
        assertThrows<VaultFormatException> { streamDecrypt(hex(v1.getString("blob")), key, v1.getString("filename")) }
    }

    /** The stream form of decryptAuto, on a v2-only vault. */
    private fun streamDecrypt(blob: ByteArray, key: ByteArray, fileName: String) {
        val out = ByteArrayOutputStream()
        VaultCrypto.decryptAuto(ByteArrayInputStream(blob), blob.size.toLong(), out, key, fileName, v2Only = true)
    }

    @Test
    fun `rftvault v2 and v1 backups open as a stream`() {
        val backup = vectors.getJSONObject("backup")
        val expected = backup.getJSONArray("entries")
        for (version in listOf("v2", "v1")) {
            val raw = hex(backup.getString(version))
            val header = VaultBackupFormat.readHeader(raw.copyOf(VaultBackupFormat.MAX_HEADER_LEN), raw.size.toLong())
            val key = VaultKdf.argon2id(backup.getString("exportPassword"), header.salt, header.params.memoryKB, header.params.iterations)
            val input = ByteArrayInputStream(raw, header.length, raw.size - header.length)
            val payload = VaultBackupFormat.openPayload(input, header, key)
            val read = mutableListOf<Pair<String, ByteArray>>()
            val payloadLength = (raw.size - header.length - VaultCrypto.TAG_LEN).toLong()
            val refused = VaultBackupPayload.readPayload(payload, payloadLength) { name, _, data ->
                read += name to data.readBytes()
            }
            assertThat(payload.read()).isEqualTo(-1) // the end, where the tag is checked
            assertThat(refused).isEmpty()
            val expectedNames = (0 until expected.length()).map { expected.getJSONObject(it).getString("name") }
            assertThat(read.map { it.first }).isEqualTo(expectedNames)
            read.forEachIndexed { i, (_, data) ->
                assertThat(data).isEqualTo(hex(expected.getJSONObject(i).getString("data")))
            }
        }
    }

    @Test
    fun `a renamed blob does not decrypt - the AAD binds the name`() {
        val key = hex(vectors.getString("blobKey"))
        val blob = blobs("v2").first { it.getString("filename") != VaultNames.SENTINEL_FILE }
        assertThrows<VaultAuthenticationException> {
            VaultCrypto.decryptAuto(hex(blob.getString("blob")), key, "other.pdf.enc", v2Only = true)
        }
    }

    @Test
    fun `the v1 fallback is refused on a v2-only vault`() {
        val key = hex(vectors.getString("blobKey"))
        val blob = blobs("v1").first()
        assertThrows<VaultFormatException> {
            VaultCrypto.decryptAuto(hex(blob.getString("blob")), key, blob.getString("filename"), v2Only = true)
        }
    }

    @Test
    fun `rftvault v2 and v1 backups open`() {
        val backup = vectors.getJSONObject("backup")
        val expected = backup.getJSONArray("entries")
        for (version in listOf("v2", "v1")) {
            val raw = hex(backup.getString(version))
            val entries = open(raw, backup.getString("exportPassword")).entries
            assertThat(entries).hasSize(expected.length())
            for (i in 0 until expected.length()) {
                assertThat(entries[i].name).isEqualTo(expected.getJSONObject(i).getString("name"))
                assertThat(entries[i].data).isEqualTo(hex(expected.getJSONObject(i).getString("data")))
            }
        }
    }

    @Test
    fun `a v2 backup whose header is altered is refused by the AAD, under the right key`() {
        val backup = vectors.getJSONObject("backup")
        val raw = hex(backup.getString("v2"))
        val original = VaultBackupFormat.readHeader(raw)
        // The key of the UNALTERED header: only the AAD can refuse what follows (a changed salt alone
        // would also change the derived key, and prove nothing about the AAD — review of 2026-10-11).
        val key = VaultKdf.argon2id(backup.getString("exportPassword"), original.salt, original.params.memoryKB, original.params.iterations)
        assertThat(VaultBackupFormat.decryptPayload(raw, original, key)).isNotEmpty()
        val alterations = mapOf<String, (ByteArray) -> Unit>(
            "a reserved byte" to { b -> b[9] = 1 },
            "the iterations, 4 to 5, still in bounds" to { b -> ByteBuffer.wrap(b).putInt(16, 5) },
            "a byte of the salt" to { b -> b[20] = (b[20].toInt() xor 1).toByte() },
        )
        for ((label, alter) in alterations) {
            val altered = raw.copyOf().also(alter)
            val header = VaultBackupFormat.readHeader(altered)
            assertThrows<VaultAuthenticationException>(label) { VaultBackupFormat.decryptPayload(altered, header, key) }
        }
    }

    @Test
    fun `a v2 header with Argon2 parameters out of bounds is refused before any derivation`() {
        val raw = hex(vectors.getJSONObject("backup").getString("v2"))
        for ((offset, value) in listOf(12 to 1023, 12 to 1024 * 1024 + 1, 16 to 0, 16 to 65)) {
            val altered = raw.copyOf()
            ByteBuffer.wrap(altered).putInt(offset, value)
            assertThrows<VaultFormatException> { VaultBackupFormat.readHeader(altered) }
        }
    }

    private fun open(raw: ByteArray, password: String): VaultBackupPayload.Payload {
        val header = VaultBackupFormat.readHeader(raw)
        val key = VaultKdf.argon2id(password, header.salt, header.params.memoryKB, header.params.iterations)
        return VaultBackupPayload.parsePayload(VaultBackupFormat.decryptPayload(raw, header, key))
    }

    private fun blobs(format: String): List<JSONObject> {
        val all = vectors.getJSONArray("blobs")
        return (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("format") == format }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
