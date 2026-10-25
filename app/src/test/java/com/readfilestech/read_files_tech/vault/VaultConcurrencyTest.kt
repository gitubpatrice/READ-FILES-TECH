package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The vault on several threads at once — the port's own exposure: the Dart app ran each step on one
 * thread between awaits (reviews of 2026-10-11).
 */
class VaultConcurrencyTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `failures recorded at the same time are all counted`() {
        val vault = TestVault(root)
        val threads = 8
        val each = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(each) { vault.lockouts.recordFailure(VaultKeys.BACKUP_LOCKOUT, vault.lockouts.now()) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        assertThat(vault.stored(VaultKeys.BACKUP_FAILS)).isEqualTo((threads * each).toLong())
    }

    @Test
    fun `two setups at once - one vault, whose password opens it`() = runBlocking<Unit> {
        val vault = TestVault(root, dispatcher = Dispatchers.Default)
        // The first setup waits, inside its derivation, for the second to reach its own. Serialised,
        // the second never can: the wait times out and the second then finds a vault already there.
        val bothInside = CountDownLatch(2)
        vault.argon2.onNewVaultParams = {
            bothInside.countDown()
            bothInside.await(500, TimeUnit.MILLISECONDS)
        }
        val outcomes = listOf("first", "second").map { password ->
            async { runCatching { vault.service.setup(password) }.map { password } }
        }.awaitAll()
        val created = outcomes.mapNotNull { it.getOrNull() }
        assertThat(created).hasSize(1)
        assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(VaultAlreadySetUpException::class.java)
        vault.argon2.onNewVaultParams = null
        vault.service.lock()
        assertThat(vault.service.unlock(created.single())).isTrue()
    }
}
