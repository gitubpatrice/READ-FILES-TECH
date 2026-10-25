package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.ui.common.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the open vault locks by itself (2.15.3, `main.dart`; inventory §6.1): 30 s after the app left the
 * screen — a system picker or the share sheet pauses it for a moment, the pattern of Bitwarden and KeePassDX
 * — and 3 min without a touch while it is on screen (V-H1 of the 2.15.x: a phone left on a table kept its
 * vault open). Pure: its times are `elapsedRealtime`, which goes on while the phone sleeps.
 */
internal object AutoLockRules {
    const val BACKGROUND_MS = 30_000L
    const val IDLE_MS = 180_000L

    /** The time the open vault locks at: the last touch plus [IDLE_MS], or sooner [BACKGROUND_MS] after the app left. */
    fun deadline(lastTouchMs: Long, backgroundSinceMs: Long?): Long {
        val idle = lastTouchMs + IDLE_MS
        return backgroundSinceMs?.let { minOf(idle, it + BACKGROUND_MS) } ?: idle
    }
}

/**
 * The vault's own locks, whatever screen is on top ([AutoLockRules]). The activity tells it when the app
 * comes to the screen and leaves it, and each touch; it locks the vault ([VaultService.lock], which purges
 * the plaintext copies) at the deadline.
 *
 * A timer alone would not do: it counts the time the phone is awake, and a phone asleep for an hour came back
 * with its vault still open until the timer caught up. The deadline is checked again the moment the app
 * comes back ([appStarted]) — before the user sees the vault. And while the app is away a [WakeAlarm] wakes
 * the phone at the deadline: asleep, the key and the plaintext copies stayed until the user came back, for
 * whoever reads the phone without its screen (security audit of phase 6).
 *
 * The 2.15.x also locked when its engine detached; here leaving the vault's screen locks (VaultViewModel),
 * and a process that dies takes the key with it.
 */
@Singleton
class VaultAutoLock @Inject constructor(
    private val vault: VaultService,
    private val clock: VaultClock,
    private val wake: WakeAlarm,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val monitor = Any()
    private var lastTouchMs = 0L
    private var backgroundSinceMs: Long? = null
    private var timer: Job? = null

    init {
        scope.launch {
            vault.unlocked.collect { open ->
                synchronized(monitor) {
                    // Opened: the idle time counts from now, not from the last touch before the password.
                    if (open) lastTouchMs = clock.elapsedRealtimeMs()
                    reschedule()
                }
            }
        }
    }

    /** The app is on screen again: locked at once if its time came while it was away, then timed again. */
    fun appStarted() = synchronized(monitor) {
        lockIfDue()
        backgroundSinceMs = null
        reschedule()
    }

    /** The app left the screen (another app on top, the screen off, the home). */
    fun appStopped() = synchronized(monitor) {
        backgroundSinceMs = clock.elapsedRealtimeMs()
        reschedule()
    }

    /** A finger down on the app: the idle time starts again. */
    fun touched() = synchronized(monitor) {
        if (!vault.unlocked.value) return@synchronized
        lastTouchMs = clock.elapsedRealtimeMs()
        reschedule()
    }

    private fun reschedule() {
        timer?.cancel()
        timer = null
        if (!vault.unlocked.value) {
            wake.cancel()
            return
        }
        val deadline = AutoLockRules.deadline(lastTouchMs, backgroundSinceMs)
        timer = scope.launch {
            delay((deadline - clock.elapsedRealtimeMs()).coerceAtLeast(0))
            synchronized(monitor) { lockIfDue() }
        }
        // On screen the phone is awake; away, it may sleep before the timer fires.
        if (backgroundSinceMs != null) wake.set(deadline, ::woken) else wake.cancel()
    }

    /**
     * The phone woken at the deadline: locked, and the plaintext copies purged here and now — the lock's own
     * purge runs in the background, which the phone may not give the time to before it sleeps again.
     */
    private fun woken() {
        val lockedNow = synchronized(monitor) { lockIfDue() }
        if (lockedNow) runBlocking { vault.purgeDecrypted() }
    }

    /** True when it locked the vault now. */
    private fun lockIfDue(): Boolean {
        val due = vault.unlocked.value && clock.elapsedRealtimeMs() >= AutoLockRules.deadline(lastTouchMs, backgroundSinceMs)
        if (due) vault.lock()
        return due
    }
}
