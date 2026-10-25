package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.vault.format.Argon2Params
import com.readfilestech.read_files_tech.vault.format.VaultKdf
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Argon2id cost of a NEW vault, measured on the device at setup (docs D-007,
 * docs/RAPPORT-ARGON2.md). The algorithm is the 2.15.x one (`calibrateArgon2Params`,
 * `lib/services/vault/vault_kdf.dart`): a bench at minimal cost, the best of three, memory raised
 * first then passes, towards about 2.5 s. Only the bounds change: Kotlin derives 4-5× faster than the
 * Dart pointycastle, so the 2.15.x ceiling (32 MiB × 4) costs 286 ms on the S9.
 *
 * **Written once, never recomputed**: an underestimate weakens the vault for its whole life, in
 * silence. Hence strict bounds and a fallback that is not the minimum. Existing vaults keep the
 * parameters stored with them; nothing is re-encrypted.
 */
object Argon2Calibration {
    const val BENCH_MEMORY_KB = 4_096
    const val BENCH_ITERATIONS = 2
    const val BENCH_SAMPLES = 3
    const val TARGET_MS = 2_500L

    /** 19 MiB × 2: the OWASP minimum for Argon2id. */
    const val MIN_MEMORY_KB = 19_456
    const val MIN_ITERATIONS = 2

    /** 64 MiB: RFC 9106's memory-constrained option, a quarter of a 256 MiB heap. */
    const val MAX_MEMORY_KB = 65_536

    /** On a small heap: the 2.15.x ceiling, to stay clear of an OutOfMemoryError at unlock. */
    const val SMALL_HEAP_MAX_MEMORY_KB = 32_768
    const val MAX_ITERATIONS = 10

    /** A `.rftvault` written by the port: the header states it, so the Flutter 2.15.x can still read it. */
    const val EXPORT_ITERATIONS = 3

    private const val FALLBACK_MEMORY_KB = 32_768
    private const val FALLBACK_ITERATIONS = 4
    private const val KB_PER_MB = 1_024

    fun memoryCeilingKB(memory: DeviceMemory): Int = if (memory.isSmallHeap) SMALL_HEAP_MAX_MEMORY_KB else MAX_MEMORY_KB

    /** When the measure is absurd or every bench failed: the old ceiling, under this device's. */
    fun fallback(memoryCeilingKB: Int) = Argon2Params(minOf(FALLBACK_MEMORY_KB, memoryCeilingKB), FALLBACK_ITERATIONS)

    /** The parameters of every `.rftvault` the port writes: the device's ceiling, three passes. */
    fun exportParams(memoryCeilingKB: Int) = Argon2Params(memoryCeilingKB, EXPORT_ITERATIONS)

    /** Pure arithmetic from a bench time [benchMs] at the bench parameters. */
    fun fromBench(benchMs: Long, memoryCeilingKB: Int): Argon2Params {
        require(memoryCeilingKB >= MIN_MEMORY_KB) { "a memory ceiling below the minimum" }
        return when {
            // A measure that cannot be trusted (a clock at zero): nothing is deduced from it.
            benchMs <= 0 -> fallback(memoryCeilingKB)
            // Already beyond the target at the minimum cost: the floor.
            benchMs >= TARGET_MS -> Argon2Params(MIN_MEMORY_KB, MIN_ITERATIONS)
            else -> scaled(benchMs, memoryCeilingKB)
        }
    }

    private fun scaled(benchMs: Long, memoryCeilingKB: Int): Argon2Params {
        // Argon2id costs roughly in proportion to memory × passes.
        val benchWork = BENCH_MEMORY_KB.toLong() * BENCH_ITERATIONS
        val targetWork = (benchWork * (TARGET_MS.toDouble() / benchMs)).roundToLong()
        val (memory, iterations) = if (targetWork <= memoryCeilingKB.toLong() * MIN_ITERATIONS) {
            // The target fits by raising memory alone: the fewest passes.
            (targetWork.toDouble() / MIN_ITERATIONS).roundToLong() to MIN_ITERATIONS.toLong()
        } else {
            // Memory at its ceiling: the rest goes into passes.
            memoryCeilingKB.toLong() to (targetWork.toDouble() / memoryCeilingKB).roundToLong()
        }
        // Rounded to the mebibyte, for a readable stored value.
        val roundedMemory = ((memory.toDouble() / KB_PER_MB).roundToInt() * KB_PER_MB).coerceIn(MIN_MEMORY_KB, memoryCeilingKB)
        return Argon2Params(roundedMemory, iterations.coerceIn(MIN_ITERATIONS.toLong(), MAX_ITERATIONS.toLong()).toInt())
    }

    /** The best of [BENCH_SAMPLES] timed derivations, in ms; null if none could run (out of memory). */
    fun bench(): Long? {
        val samples = (1..BENCH_SAMPLES).mapNotNull { timedBench() }
        return samples.minOrNull()
    }

    @Suppress("SwallowedException") // a sample that cannot run is skipped, as in the 2.15.x
    private fun timedBench(): Long? = try {
        val start = System.nanoTime()
        VaultKdf.argon2id("", ByteArray(VaultKdf.SALT_LEN), BENCH_MEMORY_KB, BENCH_ITERATIONS).fill(0)
        (System.nanoTime() - start) / NANOS_PER_MS
    } catch (e: OutOfMemoryError) {
        null
    }

    private const val NANOS_PER_MS = 1_000_000L
}
