package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.vault.format.Argon2Params
import javax.inject.Inject

/** The Argon2id costs the vault chooses or accepts on THIS device (docs D-007, plan review finding 19). */
interface Argon2Policy {
    /** For a new vault: measured once, stored with it, never recomputed. */
    fun newVaultParams(): Argon2Params

    /** For a `.rftvault` the port writes: the header states them. */
    fun exportParams(): Argon2Params

    /** Whether deriving at [params] fits this device's heap — checked BEFORE deriving. */
    fun canAfford(params: Argon2Params): Boolean
}

class DeviceArgon2Policy @Inject constructor(private val memory: DeviceMemory) : Argon2Policy {
    private val ceilingKB get() = Argon2Calibration.memoryCeilingKB(memory)

    override fun newVaultParams(): Argon2Params =
        Argon2Calibration.bench()?.let { Argon2Calibration.fromBench(it, ceilingKB) } ?: Argon2Calibration.fallback(ceilingKB)

    override fun exportParams(): Argon2Params = Argon2Calibration.exportParams(ceilingKB)

    /** Half the heap at most: a backup's 1 GiB header must be refused, not attempted (finding 19). */
    override fun canAfford(params: Argon2Params): Boolean = params.memoryKB.toLong() * KIB <= memory.maxHeapBytes / 2

    private companion object {
        const val KIB = 1_024L
    }
}
