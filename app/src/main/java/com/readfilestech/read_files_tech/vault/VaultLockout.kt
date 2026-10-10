package com.readfilestech.read_files_tech.vault

/**
 * The brute-force lockout of the vault — shared by unlocking and by restoring a `.rftvault` — as the
 * Flutter 2.15.3 computes it (`lib/services/vault/vault_lockout.dart`), ported with its eight tests.
 *
 * Why it is a function of its own: in the 2.15.2 both locks computed their remaining time each on its
 * side, with the same fault (D2, reproduced on 2026-10-10): the "monotonic" deadline is an
 * `elapsedRealtime`, which RESTARTS FROM ZERO at every boot, so a deadline set before a reboot lasted
 * about as long as the previous uptime — days — correct password included.
 */
object VaultLockout {
    private const val FREE_FAILURES = 5
    private const val MAX_DELAY_MINUTES = 30L
    private const val MS_PER_MINUTE = 60_000L

    /** Past the 32-minute step everything is capped anyway: stops the shift before it overflows. */
    private const val LAST_DOUBLING = 5

    /**
     * No delay before the fifth failure, then a delay that doubles from one minute, capped at 30:
     * 1, 2, 4, 8, 16, 30, 30… minutes — the table of the 2.15.x, stated as the rule it is.
     */
    fun delayMs(fails: Long): Long {
        if (fails < FREE_FAILURES) return 0
        val step = (fails - FREE_FAILURES).coerceAtMost(LAST_DOUBLING.toLong()).toInt()
        return minOf(1L shl step, MAX_DELAY_MINUTES) * MS_PER_MINUTE
    }

    /**
     * Remaining lockout, in milliseconds (0 = free).
     *
     * Both deadlines ([wallDeadline] on the wall clock, [monoDeadline] on `elapsedRealtime`) were set
     * at most [delayMs] ahead of their clock. Within one boot, with a wall clock that did not go back,
     * their remaining time can therefore NEVER exceed that delay. A deadline further away does not
     * describe this boot:
     * - monotonic: it belongs to an earlier boot (the clock restarted from zero); [monoArmedAt], the
     *   `elapsedRealtime` of arming, also says so when the clock is now below it. `null` for a deadline
     *   set by a 2.15.2, which did not record it — the invariant alone recognises it then;
     * - wall: the clock was set back after arming.
     *
     * Such a deadline is set aside and the other clock decides. Within the current boot the monotonic
     * one holds alone when the wall clock is moved forward. When the monotonic one does not belong to
     * this boot, the wall clock is the only one left: it decides even when set back (fail closed),
     * otherwise rebooting then setting the time back would free the vault.
     *
     * Known limits, accepted (as in the 2.15.3): rebooting AND moving the wall clock forward lifts the
     * lock, one reboot per attempt; a reboot whose new uptime is above the arming one but below the
     * deadline is not recognised, so the undue lock lasts at most one delay (30 min at worst).
     */
    @Suppress("LongParameterList") // the two clocks, their two deadlines, the arming instant and the count
    fun remainingMs(fails: Long, wallNow: Long, monoNow: Long, wallDeadline: Long, monoDeadline: Long, monoArmedAt: Long?): Long {
        val delay = delayMs(fails)
        if (delay == 0L) return 0
        val monoRemaining = monoDeadline - monoNow
        val monoBelongsToThisBoot = (monoArmedAt == null || monoNow >= monoArmedAt) && monoRemaining <= delay
        val wallRemaining = wallDeadline - wallNow
        val wallTrusted = wallRemaining <= delay
        val mono = if (monoBelongsToThisBoot) monoRemaining else 0
        val wall = if (wallTrusted || !monoBelongsToThisBoot) wallRemaining else 0
        return maxOf(mono, wall, 0)
    }
}
