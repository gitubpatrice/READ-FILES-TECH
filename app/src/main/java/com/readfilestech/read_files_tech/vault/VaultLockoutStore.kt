package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two brute-force lockouts — unlocking, and restoring a `.rftvault` — stored in the Flutter app's
 * own keys (VaultKeys.Lockout) and computed by VaultLockout, the 2.15.3 rule.
 *
 * Fails CLOSED: a counter or a deadline that cannot be read is not taken for zero (docs D-019) — the
 * attempt is refused as a corrupted vault, and nothing is rewritten. A failure that the preferences
 * file refuses to record is reported, never swallowed: an uncounted failure is a free attempt.
 */
@Singleton
class VaultLockoutStore @Inject constructor(
    private val prefs: FlutterPreferences,
    private val clock: VaultClock,
) {
    /** The instant of an attempt, read once: the failure it may record is armed from the same instant. */
    class Attempt(val wallMs: Long, val monoMs: Long)

    fun now() = Attempt(clock.wallMs(), clock.elapsedRealtimeMs())

    /**
     * @throws VaultLockoutException the lockout is running.
     * @throws VaultCorruptedException one of its keys cannot be read.
     */
    fun ensureFree(lockout: VaultKeys.Lockout, at: Attempt) {
        val remaining = VaultLockout.remainingMs(
            fails = read(lockout.fails) ?: 0,
            wallNow = at.wallMs,
            monoNow = at.monoMs,
            wallDeadline = read(lockout.until) ?: 0,
            monoDeadline = read(lockout.untilElapsed) ?: 0,
            monoArmedAt = read(lockout.armedElapsed),
        )
        if (remaining > 0) throw VaultLockoutException(remaining)
    }

    /**
     * One more failure, and the delay it now carries — the counter and the deadlines in ONE commit, so a
     * process killed in between cannot keep the count without the deadline (the 2.15.3 wrote four
     * times, the arming instant first for that reason).
     *
     * @throws VaultStorageException the file refused it.
     */
    fun recordFailure(lockout: VaultKeys.Lockout, at: Attempt) {
        val fails = (read(lockout.fails) ?: 0) + 1
        val delay = VaultLockout.delayMs(fails)
        prefs.commitOrThrow("a failed attempt") {
            putLong(lockout.fails, fails)
            if (delay > 0) {
                putLong(lockout.armedElapsed, at.monoMs)
                putLong(lockout.until, at.wallMs + delay)
                putLong(lockout.untilElapsed, at.monoMs + delay)
            }
        }
    }

    /** After a success: the counter and both deadlines go. False if the file refused the write. */
    fun clear(lockout: VaultKeys.Lockout): Boolean = prefs.commit { lockout.all.forEach(::remove) }

    private fun read(key: String): Long? = prefs.long(key).orCorrupted(key)
}
