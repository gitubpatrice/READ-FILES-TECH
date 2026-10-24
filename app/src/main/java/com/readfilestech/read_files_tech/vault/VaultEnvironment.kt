package com.readfilestech.read_files_tech.vault

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The two clocks of the lockout (VaultLockout): the wall clock, and `elapsedRealtime`, which restarts at boot. */
interface VaultClock {
    fun wallMs(): Long

    fun elapsedRealtimeMs(): Long
}

class SystemVaultClock @Inject constructor() : VaultClock {
    override fun wallMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * A wake-up of the phone at an `elapsedRealtime` instant, asleep or not — what a coroutine timer cannot be:
 * its clock stops in deep sleep (VaultAutoLock). One at a time: setting one replaces the one before.
 */
interface WakeAlarm {
    /** [onWake] runs at [atElapsedMs] or soon after, on a thread of its own, while the phone is kept awake. */
    fun set(atElapsedMs: Long, onWake: () -> Unit)

    fun cancel()
}

/**
 * AlarmManager, inexact and with a listener: no permission (an exact alarm needs one), no receiver in the
 * manifest. It may fire late in Doze, at the next maintenance window — still before the user is back.
 */
class AndroidWakeAlarm @Inject constructor(@ApplicationContext context: Context) : WakeAlarm {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val handler by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }
    private var listener: AlarmManager.OnAlarmListener? = null

    @Synchronized
    override fun set(atElapsedMs: Long, onWake: () -> Unit) {
        cancel()
        val next = AlarmManager.OnAlarmListener { onWake() }
        listener = next
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, TAG, next, handler)
    }

    @Synchronized
    override fun cancel() {
        listener?.let(alarms::cancel)
        listener = null
    }

    private companion object {
        const val TAG = "rft-vault-autolock"
    }
}

/**
 * What the Java heap allows. Argon2 and AES-GCM in one block both allocate in it, and the vault must
 * refuse what would end in an `OutOfMemoryError` instead of crashing (docs D-007, D-023).
 */
interface DeviceMemory {
    /** `Runtime.maxMemory()`: the ceiling of this app's heap. */
    val maxHeapBytes: Long

    /** A device where new vaults keep Argon2 at 32 MiB (docs/RAPPORT-ARGON2.md). */
    val isSmallHeap: Boolean
}

class AndroidDeviceMemory @Inject constructor(@ApplicationContext context: Context) : DeviceMemory {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    override val maxHeapBytes: Long get() = Runtime.getRuntime().maxMemory()

    override val isSmallHeap: Boolean
        get() = activityManager.isLowRamDevice || activityManager.memoryClass < SMALL_HEAP_MEMORY_CLASS_MB

    private companion object {
        /** Below it, a 64 MiB Argon2 is a quarter of the heap or more (docs/RAPPORT-ARGON2.md). */
        const val SMALL_HEAP_MEMORY_CLASS_MB = 192
    }
}
