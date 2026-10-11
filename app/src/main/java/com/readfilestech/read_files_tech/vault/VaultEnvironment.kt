package com.readfilestech.read_files_tech.vault

import android.app.ActivityManager
import android.content.Context
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
