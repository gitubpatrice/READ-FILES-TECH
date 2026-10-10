package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The eight tests of the Flutter 2.15.3 (`test/vault_lockout_test.dart`), same values, same
 * expectations (docs D-016 §4).
 *
 * D2 — reproduced on 2026-10-10 on an emulator with the 2.15.2: after a reboot, the RIGHT password
 * stayed refused for about the previous uptime ("Réessayez dans 88 s", 141 s after the deadline of a
 * one-minute lock; ~13 days on an S9 running for 13 days).
 */
class VaultLockoutTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Test
    fun `the delay follows the number of failures and caps at 30 min`() {
        listOf(0L, 1L, 4L).forEach { assertThat(VaultLockout.delayMs(it)).isEqualTo(0) }
        assertThat(VaultLockout.delayMs(5)).isEqualTo(1 * minute)
        assertThat(VaultLockout.delayMs(6)).isEqualTo(2 * minute)
        assertThat(VaultLockout.delayMs(9)).isEqualTo(16 * minute)
        assertThat(VaultLockout.delayMs(10)).isEqualTo(30 * minute)
        assertThat(VaultLockout.delayMs(500)).isEqualTo(30 * minute)
    }

    @Test
    fun `within the same boot, the lock runs then expires`() {
        fun at(t: Long) = VaultLockout.remainingMs(
            fails = 5,
            wallNow = 1_000_000 + t,
            monoNow = 5 * day + t,
            wallDeadline = 1_000_000 + minute,
            monoDeadline = 5 * day + minute,
            monoArmedAt = 5 * day,
        )
        assertThat(at(0)).isEqualTo(minute)
        assertThat(at(30_000)).isEqualTo(30_000)
        assertThat(at(minute)).isEqualTo(0)
        assertThat(at(2 * minute)).isEqualTo(0)
    }

    @Test
    fun `moving the wall clock forward does not lift the lock - F3 v2_13_0`() {
        val remaining = VaultLockout.remainingMs(
            fails = 5,
            wallNow = 1_000_000 + 10 * day, // date moved ten days forward
            monoNow = 5 * day + 1000,
            wallDeadline = 1_000_000 + minute,
            monoDeadline = 5 * day + minute,
            monoArmedAt = 5 * day,
        )
        assertThat(remaining).isEqualTo(minute - 1000)
    }

    @Test
    fun `after a reboot, the monotonic deadline of the previous boot is ignored`() {
        // Armed at 5 days of uptime; rebooted, uptime 30 s; the wall deadline is past.
        assertThat(
            VaultLockout.remainingMs(
                fails = 5,
                wallNow = 1_000_000 + 3 * minute,
                monoNow = 30_000,
                wallDeadline = 1_000_000 + minute,
                monoDeadline = 5 * day + minute,
                monoArmedAt = 5 * day,
            ),
        ).isEqualTo(0)
        // Wall deadline not yet past: it decides, not the previous uptime.
        assertThat(
            VaultLockout.remainingMs(
                fails = 5,
                wallNow = 1_000_000 + 20_000,
                monoNow = 30_000,
                wallDeadline = 1_000_000 + minute,
                monoDeadline = 5 * day + minute,
                monoArmedAt = 5 * day,
            ),
        ).isEqualTo(40_000)
    }

    @Test
    fun `a deadline inherited from the 2_15_2, without arming instant, does not lock for days`() {
        fun at(wallNow: Long) = VaultLockout.remainingMs(
            fails = 5,
            wallNow = wallNow,
            monoNow = 30_000,
            wallDeadline = 1_000_000 + minute,
            monoDeadline = 13 * day + minute, // set at 13 days of uptime, before the reboot
            monoArmedAt = null,
        )
        assertThat(at(1_000_000 + 20_000)).isEqualTo(40_000)
        assertThat(at(1_000_000 + minute)).isEqualTo(0) // free at the wall deadline, not in 13 days
    }

    @Test
    fun `a wall clock set back does not lengthen the lock`() {
        val remaining = VaultLockout.remainingMs(
            fails = 5,
            wallNow = 1_000_000 - 2 * day, // date set two days back after arming
            monoNow = 5 * day + minute + 1,
            wallDeadline = 1_000_000 + minute,
            monoDeadline = 5 * day + minute,
            monoArmedAt = 5 * day,
        )
        assertThat(remaining).isEqualTo(0) // the monotonic clock, valid in this boot, says the delay is over
    }

    @Test
    fun `rebooting THEN setting the time back does not free the vault - fail closed`() {
        val remaining = VaultLockout.remainingMs(
            fails = 5,
            wallNow = 1_000_000 - 2 * day,
            monoNow = 30_000,
            wallDeadline = 1_000_000 + minute,
            monoDeadline = 5 * day + minute,
            monoArmedAt = 5 * day,
        )
        assertThat(remaining).isEqualTo(2 * day + minute)
    }

    /**
     * Not one of the eight Dart tests — added because sabotaging the arming check left all eight green.
     * In the normal flow the invariant alone recognises another boot (deadline = armedAt + delay), so
     * the arming instant only decides here: the counter was raised to 6 (delay 2 min) and the process
     * died before re-arming — the deadline is still the 1-minute one of the 5th failure — then the phone
     * rebooted, and its new uptime sits just below the arming instant.
     */
    @Test
    fun `an interrupted re-arming then a reboot - the arming instant alone recognises the old boot`() {
        val remaining = VaultLockout.remainingMs(
            fails = 6,
            wallNow = 1_000_000 + 3 * minute, // wall deadline past
            monoNow = 5 * day - 30_000, // new boot, uptime just below the arming instant
            wallDeadline = 1_000_000 + minute,
            monoDeadline = 5 * day + minute, // 90 s ahead: within the 2-minute delay, the invariant passes
            monoArmedAt = 5 * day,
        )
        assertThat(remaining).isEqualTo(0)
    }

    @Test
    fun `no failure, no lock, even with leftover deadlines`() {
        val remaining = VaultLockout.remainingMs(
            fails = 0,
            wallNow = 0,
            monoNow = 0,
            wallDeadline = day,
            monoDeadline = day,
            monoArmedAt = null,
        )
        assertThat(remaining).isEqualTo(0)
    }
}
