package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.vault.format.Argon2Params
import com.readfilestech.read_files_tech.vault.format.VaultBackupFormat
import org.junit.jupiter.api.Test

/**
 * The calibration of a NEW vault's Argon2id — the six tests of the Flutter 2.15.3 on
 * `calibrateArgon2Params` (`test/vault_kdf_test.dart`), at the port's bounds (docs D-007: 19-64 MiB,
 * 32 MiB on a small heap, 2-10 passes, fallback 32 MiB × 4). Written once and never recomputed: an
 * underestimate weakens the vault for its whole life, with no symptom.
 */
class Argon2CalibrationTest {
    private val ceilings = listOf(
        Argon2Calibration.memoryCeilingKB(FakeMemory(maxHeapBytes = 512L shl 20)),
        Argon2Calibration.memoryCeilingKB(FakeMemory(maxHeapBytes = 128L shl 20, isSmallHeap = true)),
    )

    @Test
    fun `the two ceilings are those of D-007`() {
        assertThat(ceilings).containsExactly(65_536, 32_768).inOrder()
    }

    @Test
    fun `an absurd measure gives the fallback, stronger than the floor, never the floor`() {
        for (ceiling in ceilings) {
            for (absurd in listOf(0L, -1L, -10_000L)) {
                val params = Argon2Calibration.fromBench(absurd, ceiling)
                assertThat(params).isEqualTo(Argon2Params(32_768, 4))
                assertThat(params.memoryKB).isGreaterThan(Argon2Calibration.MIN_MEMORY_KB)
            }
        }
    }

    @Test
    fun `a device already slow stays at the floor`() {
        for (ceiling in ceilings) {
            for (slow in listOf(Argon2Calibration.TARGET_MS, Argon2Calibration.TARGET_MS + 1, 60_000L)) {
                assertThat(Argon2Calibration.fromBench(slow, ceiling))
                    .isEqualTo(Argon2Params(Argon2Calibration.MIN_MEMORY_KB, Argon2Calibration.MIN_ITERATIONS))
            }
        }
    }

    @Test
    fun `a fast device climbs, never beyond the bounds`() {
        for (ceiling in ceilings) {
            for (fast in listOf(1L, 2L, 5L, 10L, 50L, 200L, 1_000L, 2_499L)) {
                val params = Argon2Calibration.fromBench(fast, ceiling)
                assertThat(params.memoryKB).isIn(Argon2Calibration.MIN_MEMORY_KB..ceiling)
                assertThat(params.iterations).isIn(Argon2Calibration.MIN_ITERATIONS..Argon2Calibration.MAX_ITERATIONS)
            }
            // The fastest device reaches both ceilings: memory first, then passes.
            assertThat(Argon2Calibration.fromBench(1, ceiling)).isEqualTo(Argon2Params(ceiling, Argon2Calibration.MAX_ITERATIONS))
        }
    }

    @Test
    fun `the faster the device, the stronger the parameters`() {
        // If monotony broke, a powerful phone could get weaker parameters than a slow one, unsaid.
        for (ceiling in ceilings) {
            var previous = 0L
            for (ms in listOf(2_400L, 2_000L, 1_500L, 1_000L, 500L, 200L, 100L, 50L, 10L, 1L)) {
                val params = Argon2Calibration.fromBench(ms, ceiling)
                val work = params.memoryKB.toLong() * params.iterations
                assertThat(work).isAtLeast(previous)
                previous = work
            }
        }
    }

    @Test
    fun `memory is always a whole number of mebibytes`() {
        for (ceiling in ceilings) {
            for (ms in 1L..2_500L step 7) {
                assertThat(Argon2Calibration.fromBench(ms, ceiling).memoryKB % 1_024).isEqualTo(0)
            }
        }
    }

    @Test
    fun `no input gives parameters below the floor`() {
        // A wide sweep: the only way to rule out a rounding hole.
        for (ceiling in ceilings) {
            for (ms in -100L..5_000L) {
                val params = Argon2Calibration.fromBench(ms, ceiling)
                assertThat(params.memoryKB).isAtLeast(Argon2Calibration.MIN_MEMORY_KB)
                assertThat(params.iterations).isAtLeast(Argon2Calibration.MIN_ITERATIONS)
            }
        }
    }

    /**
     * The port's form of "the backup parameters do not depend on the device" (D-007): they DO now —
     * the device's ceiling, three passes — so the v2 header states them, and any device reads them
     * from there (VaultBackupsTest restores with the header's, never its own).
     */
    @Test
    fun `export parameters are the device's ceiling at three passes, stated by the header`() {
        assertThat(ceilings.map { Argon2Calibration.exportParams(it) })
            .containsExactly(Argon2Params(65_536, 3), Argon2Params(32_768, 3)).inOrder()
        for (ceiling in ceilings) {
            val params = Argon2Calibration.exportParams(ceiling)
            val header = VaultBackupFormat.headerV2(ByteArray(16), ByteArray(12), params)
            assertThat(VaultBackupFormat.readHeader(header + ByteArray(16)).params).isEqualTo(params)
        }
    }
}
