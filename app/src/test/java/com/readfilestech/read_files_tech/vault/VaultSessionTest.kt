package com.readfilestech.read_files_tech.vault

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** D1 of the 2.15.2 at its root: what an operation holds is its own copy of the key. */
class VaultSessionTest {
    private val session = VaultSession()
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `an operation keeps its own key when the vault locks under it`() {
        session.install(key, newV2Only = true, startEpoch = session.epoch())
        val op = session.open()
        session.lock()
        // The 2.15.2 handed out the cached buffer itself, which lock() filled with zeros.
        assertThat(op.key).isEqualTo(ByteArray(32) { (it + 1).toByte() })
        assertThat(key).isEqualTo(ByteArray(32)) // the session's own copy is wiped
        assertThrows<LockedDuringOperationException> { session.ensureOpen(op) }
        op.close()
        assertThat(op.key).isEqualTo(ByteArray(32))
    }

    @Test
    fun `a key derived before a lock is not installed, and is wiped`() {
        val start = session.epoch()
        session.lock()
        assertThat(session.install(key, newV2Only = false, startEpoch = start)).isFalse()
        assertThat(session.unlocked.value).isFalse()
        assertThat(key).isEqualTo(ByteArray(32))
        assertThrows<VaultLockedException> { session.open() }
    }

    @Test
    fun `a second unlock replaces the key and wipes the first`() {
        session.install(key, newV2Only = true, startEpoch = session.epoch())
        val second = ByteArray(32) { 9 }
        session.install(second, newV2Only = true, startEpoch = session.epoch())
        assertThat(key).isEqualTo(ByteArray(32))
        session.open().use { assertThat(it.key).isEqualTo(ByteArray(32) { 9 }) }
    }
}
