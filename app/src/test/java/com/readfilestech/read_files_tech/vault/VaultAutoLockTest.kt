package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The vault's own locks (2.15.3 `main.dart`, inventory §6.1): 30 s away, 3 min without a touch, a phone asleep. */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultAutoLockTest {
    @TempDir
    lateinit var root: File

    /** `elapsedRealtime`: the test's virtual time, plus the time the phone [slept] — which no timer counts. */
    private class PhoneClock(private val scheduler: TestCoroutineScheduler) : VaultClock {
        var slept = 0L

        override fun wallMs(): Long = WALL + elapsedRealtimeMs()

        override fun elapsedRealtimeMs(): Long = BOOT + scheduler.currentTime + slept
    }

    private suspend fun TestScope.openVault(): Pair<TestVault, VaultAutoLock> {
        val vault = TestVault(root)
        val autoLock = VaultAutoLock(vault.service, PhoneClock(testScheduler), backgroundScope)
        runCurrent()
        check(vault.service.setup(PASSWORD))
        runCurrent()
        return vault to autoLock
    }

    @Test
    fun `30 s after the app left the screen the vault locks, and a return before keeps it open`() = runTest {
        val (vault, autoLock) = openVault()
        autoLock.appStopped()
        advanceTimeBy(29_000)
        runCurrent()
        assertThat(vault.session.unlocked.value).isTrue()
        // Back from the system's picker in time.
        autoLock.appStarted()
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(vault.session.unlocked.value).isTrue()
        autoLock.appStopped()
        advanceTimeBy(AutoLockRules.BACKGROUND_MS + 1)
        runCurrent()
        assertThat(vault.session.unlocked.value).isFalse()
    }

    @Test
    fun `3 min without a touch lock it, and each touch starts the time again`() = runTest {
        val (vault, autoLock) = openVault()
        advanceTimeBy(170_000)
        autoLock.touched()
        advanceTimeBy(170_000)
        runCurrent()
        assertThat(vault.session.unlocked.value).isTrue()
        advanceTimeBy(10_001)
        runCurrent()
        assertThat(vault.session.unlocked.value).isFalse()
    }

    @Test
    fun `a phone asleep runs no timer - the return locks the vault before anything shows`() = runTest {
        val clock = PhoneClock(testScheduler)
        val vault = TestVault(root)
        val autoLock = VaultAutoLock(vault.service, clock, backgroundScope)
        runCurrent()
        check(vault.service.setup(PASSWORD))
        runCurrent()
        autoLock.appStopped()
        clock.slept += 3_600_000 // an hour, screen off: no virtual time passes, no timer fires
        assertThat(vault.session.unlocked.value).isTrue()
        autoLock.appStarted()
        assertThat(vault.session.unlocked.value).isFalse()
    }

    @Test
    fun `the idle time of a vault opened again counts from its opening, not from the last touch before`() = runTest {
        val (vault, autoLock) = openVault()
        autoLock.touched()
        vault.service.lock()
        runCurrent()
        // Locked: no timer, and a touch changes nothing.
        advanceTimeBy(AutoLockRules.IDLE_MS)
        autoLock.touched()
        runCurrent()
        assertThat(vault.service.unlock(PASSWORD)).isTrue()
        runCurrent()
        advanceTimeBy(AutoLockRules.IDLE_MS - 1_000)
        runCurrent()
        assertThat(vault.session.unlocked.value).isTrue()
        advanceTimeBy(1_001)
        runCurrent()
        assertThat(vault.session.unlocked.value).isFalse()
    }

    @Test
    fun `away, the sooner deadline wins - the idle time already spent counts`() {
        assertThat(AutoLockRules.deadline(lastTouchMs = 0, backgroundSinceMs = null)).isEqualTo(AutoLockRules.IDLE_MS)
        assertThat(AutoLockRules.deadline(lastTouchMs = 0, backgroundSinceMs = 10_000)).isEqualTo(40_000)
        // Left after 2 min 50 s of no touch: locked at 3 min, not 30 s after leaving.
        assertThat(AutoLockRules.deadline(lastTouchMs = 0, backgroundSinceMs = 170_000)).isEqualTo(AutoLockRules.IDLE_MS)
    }

    private companion object {
        const val PASSWORD = "correct horse"
        const val WALL = 1_760_000_000_000L
        const val BOOT = 3_600_000L
    }
}
