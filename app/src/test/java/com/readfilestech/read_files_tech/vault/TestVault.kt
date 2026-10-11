package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import com.readfilestech.read_files_tech.vault.format.Argon2Params
import kotlinx.coroutines.Dispatchers
import java.io.File

/** The two clocks, set by the test; [onWallRead] lets a test act at the instant an attempt starts. */
class FakeClock(var wall: Long = 1_760_000_000_000L, var mono: Long = 3_600_000L) : VaultClock {
    var onWallRead: (() -> Unit)? = null

    override fun wallMs(): Long {
        onWallRead?.invoke()
        return wall
    }

    override fun elapsedRealtimeMs(): Long = mono

    fun advance(ms: Long) {
        wall += ms
        mono += ms
    }
}

class FakeMemory(override var maxHeapBytes: Long, override var isSmallHeap: Boolean = false) : DeviceMemory

/** The cheapest Argon2 the formats accept: the KDF itself is checked by the Dart vectors. */
class FixedArgon2Policy(var affordable: Boolean = true) : Argon2Policy {
    /** Runs when an export asks for its parameters: right before the derivation. */
    var onExportParams: (() -> Unit)? = null

    override fun newVaultParams() = CHEAP

    override fun exportParams(): Argon2Params {
        onExportParams?.invoke()
        return CHEAP
    }

    override fun canAfford(params: Argon2Params) = affordable

    companion object {
        val CHEAP = Argon2Params(memoryKB = 1_024, iterations = 1)
    }
}

/**
 * The whole vault wired as Hilt would, on directories of a test, everything on the caller's thread.
 * [heapBytes] decides the engine (docs D-023): [ONE_BLOCK] keeps every test file in one block,
 * [STREAMED] sends anything above 1 KiB through the stream.
 */
class TestVault(root: File, heapBytes: Long = ONE_BLOCK) {
    val raw = InMemorySharedPreferences()
    val prefs = FlutterPreferences(raw)
    val clock = FakeClock()
    val memory = FakeMemory(heapBytes)
    val argon2 = FixedArgon2Policy()
    val dirs = VaultDirs(vault = File(root, "app_flutter/vault"), cache = File(root, "cache"))
    val session = VaultSession()
    val cipher = VaultCipher(memory)
    val lockouts = VaultLockoutStore(prefs, clock)
    val service = VaultService(prefs, dirs, session, lockouts, cipher, argon2, StoredKdf(prefs, argon2), Dispatchers.Unconfined)
    val files = VaultFiles(dirs, session, cipher, Dispatchers.Unconfined)
    val backups = VaultBackups(dirs, session, cipher, lockouts, argon2, files, Dispatchers.Unconfined)
    val repair = ZeroKeyRepair(prefs, session, cipher, files, Dispatchers.Unconfined)

    /** The raw value under a bare Dart key, as the plugin stored it. */
    fun stored(key: String): Any? = raw.all["flutter.$key"]

    fun plant(key: String, value: Any) = raw.plant("flutter.$key", value)

    /** Everything in the vault directory, residues and staging folders included. */
    fun vaultEntries(): List<String> = dirs.vault.list().orEmpty().sorted()

    companion object {
        const val ONE_BLOCK = 1024L * 1024 * 1024
        const val STREAMED = 12L * 1024
        val ENGINES = listOf(ONE_BLOCK, STREAMED)
    }
}
