package com.readfilestech.read_files_tech.vault

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.vault.format.Argon2Params
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Too long and too large for the CI emulator: excluded there (ci.yml), run by hand on each device. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class CapacityTest

/**
 * The vault at its caps, on the device's REAL heap (port plan, phase 2, finding 20; docs D-023): two files
 * at the import cap (100 MiB), exported together at the export cap (200 MiB), the backup restored over
 * them, every file decrypted and compared. On a 128 MB heap none of this fits in one block: the stream
 * must carry it. The 2.15.3 itself cannot write such files (INVENTAIRE D29), hence a test of the port alone.
 *
 * By hand, one device at a time:
 *   adb -s <serial> shell am instrument -w -r -e class com.readfilestech.read_files_tech.vault.VaultCapacityTest \
 *     com.readfilestech.read_files_tech.next.test/androidx.test.runner.AndroidJUnitRunner
 */
@CapacityTest
class VaultCapacityTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "capacity-test")
    private val memory = AndroidDeviceMemory(context)
    private val prefs = FlutterPreferences(context.getSharedPreferences("capacity-test", Context.MODE_PRIVATE))
    private val dirs = VaultDirs(vault = File(root, "vault"), cache = File(root, "cache"))
    private val session = VaultSession()
    private val cipher = VaultCipher(memory)
    private val lockouts = VaultLockoutStore(prefs, SystemVaultClock())
    private val argon2 = object : Argon2Policy {
        // The KDF is not under test: the cheapest the formats accept.
        override fun newVaultParams() = Argon2Params(memoryKB = 1_024, iterations = 1)

        override fun exportParams() = Argon2Params(memoryKB = 1_024, iterations = 1)

        override fun canAfford(params: Argon2Params) = true
    }
    private val service = VaultService(prefs, dirs, session, lockouts, cipher, argon2, StoredKdf(prefs, argon2), Dispatchers.IO)
    private val files = VaultFiles(dirs, session, cipher, Dispatchers.IO)
    private val backups = VaultBackups(dirs, session, cipher, lockouts, argon2, files, Dispatchers.IO)

    @Before
    fun setUp() {
        FileTrees.delete(root)
        check(prefs.commit { VaultKeys.VAULT_STATE.forEach(::remove) })
    }

    @After
    fun tearDown() {
        service.lock()
        FileTrees.delete(root)
        prefs.commit { VaultKeys.VAULT_STATE.forEach(::remove) }
    }

    @Test
    fun theVaultAtItsCapsOnThisHeap() = runBlocking {
        Log.i(TAG, "heap ${memory.maxHeapBytes / MIB} MiB")
        assertThat(cipher.fitsInOneBlock(VaultFiles.IMPORT_MAX_BYTES)).isFalse() // the stream is what is tested
        assertThat(service.setup(PASSWORD)).isTrue()

        val first = timed("import 100 MiB") { importGenerated("au plafond.bin", VaultFiles.IMPORT_MAX_BYTES, 1) }
        // Both files together at the export cap: the .enc lengths are what export counts.
        val secondSize = VaultBackups.EXPORT_MAX_BYTES - VaultFiles.IMPORT_MAX_BYTES - 2 * ENC_OVERHEAD
        val second = timed("import ~100 MiB") { importGenerated("presque.bin", secondSize, 2) }
        val expected = mapOf("au plafond.bin" to first, "presque.bin" to second)
        assertThat(decryptedDigests()).isEqualTo(expected)

        val out = File(root, "backups")
        val destination = VaultBackups.Destination(out) {
            File(out, "capacity.rftvault").also { check(it.createNewFile()) }
        }
        val backup = timed("export 200 MiB") { backups.export(BACKUP_PASSWORD, destination) }
        Log.i(TAG, "backup ${backup.length()} bytes")
        val result = timed("restore 200 MiB") { backups.restore(backup, BACKUP_PASSWORD, overwriteExisting = true) }
        assertThat(result.restored).isEqualTo(2)
        assertThat(result.failed).isEmpty()
        assertThat(decryptedDigests()).isEqualTo(expected)
    }

    /** Writes [size] pseudo-random bytes in chunks (never [size] in memory), imports them, returns their SHA-256. */
    private suspend fun importGenerated(name: String, size: Long, seed: Int): String {
        val source = File(File(root, "sources"), name) // the import keeps the file's own name
        source.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(CHUNK)
        var x = seed
        FileOutputStream(source).use { out ->
            var left = size
            while (left > 0) {
                val n = minOf(left, CHUNK.toLong()).toInt()
                for (i in 0 until n) {
                    x = x * LCG_A + LCG_C
                    chunk[i] = (x ushr Byte.SIZE_BITS * 2).toByte()
                }
                out.write(chunk, 0, n)
                digest.update(chunk, 0, n)
                left -= n
            }
        }
        files.import(source)
        source.delete()
        return hex(digest.digest())
    }

    private suspend fun decryptedDigests(): Map<String, String> = files.list().associate { encrypted ->
        val plain = files.decryptToCache(encrypted)
        try {
            encrypted.name.removeSuffix(".enc") to hex(sha256(plain))
        } finally {
            FileTrees.delete(plain.parentFile ?: plain)
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    private inline fun <T> timed(what: String, block: () -> T): T {
        val started = System.nanoTime()
        return block().also { Log.i(TAG, "$what: ${(System.nanoTime() - started) / NANOS_PER_MS} ms") }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "RftCapacity"
        const val PASSWORD = "Coffre été 🔐 2026"
        const val BACKUP_PASSWORD = "Sauvegarde ✓ export"
        const val MIB = 1024L * 1024
        const val CHUNK = 1024 * 1024
        const val LCG_A = 1_103_515_245
        const val LCG_C = 12_345

        /** "RFT2" + nonce + tag: what a v2 `.enc` adds to its content. */
        const val ENC_OVERHEAD = 4L + 12 + 16
        const val NANOS_PER_MS = 1_000_000L
    }
}
