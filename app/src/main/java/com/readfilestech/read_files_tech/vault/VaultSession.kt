package com.readfilestech.read_files_tech.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The open vault: its master key, held in memory only, and the lock EPOCH.
 *
 * D1 of the 2.15.2 (reproduced on 2026-10-10, docs D-013): an operation used the cached key buffer
 * itself, and locking filled that buffer with zeros — an import running during a lock finished
 * WITHOUT ERROR under a key of 32 zero bytes. Here, as in the 2.15.3:
 * - each operation gets its own COPY of the key ([open]), which it wipes when it ends ([Op.close]);
 * - each lock bumps the epoch, and an operation checks it ([ensureOpen]) right before it writes, so
 *   one interrupted by a lock fails instead of finishing.
 *
 * Unlike the Dart app, operations run on several threads: every read and write of the state goes
 * through one monitor, so a lock cannot wipe the key while an operation copies it.
 */
@Singleton
class VaultSession @Inject constructor() {
    private val monitor = Any()
    private var key: ByteArray? = null
    private var v2Only = false
    private var epoch = 0L
    private val unlockedState = MutableStateFlow(false)

    /** True while a key is held; the screens follow it to arm their timers and leave the vault on lock. */
    val unlocked: StateFlow<Boolean> = unlockedState.asStateFlow()

    /** One operation's own view of the vault: a copy of the key, the epoch and the v2-only flag. */
    class Op internal constructor(val key: ByteArray, val epoch: Long, val v2Only: Boolean) : AutoCloseable {
        override fun close() = key.fill(0)
    }

    /** @throws VaultLockedException no key held. */
    fun open(): Op = synchronized(monitor) {
        val current = key ?: throw VaultLockedException()
        Op(current.copyOf(), epoch, v2Only)
    }

    /** @throws LockedDuringOperationException the vault was locked since [op] opened. */
    fun ensureOpen(op: Op) = whileOpen(op) {}

    /**
     * Runs [action] — publishing a file, deleting one — only if the vault is still open at [op]'s
     * epoch, and holds the lock state while it runs: a lock() waits for it, and none can slip between
     * the check and the write (the 2.15.x left that window open: a rename started after a lock went
     * through). Keep [action] short, a rename or a deletion.
     *
     * @throws LockedDuringOperationException the vault was locked since [op] opened.
     */
    fun <T> whileOpen(op: Op, action: () -> T): T = synchronized(monitor) {
        if (epoch != op.epoch || key == null) throw LockedDuringOperationException()
        action()
    }

    /** The epoch now: a derivation started at it installs its key only if it has not changed. */
    fun epoch(): Long = synchronized(monitor) { epoch }

    /**
     * Holds [newKey] (taken over: the session wipes it on lock) if no lock happened since [startEpoch].
     * Otherwise wipes it and returns false — a lock during the seconds of Argon2 must not be followed
     * by the vault opening behind the user's back.
     */
    fun install(newKey: ByteArray, newV2Only: Boolean, startEpoch: Long): Boolean = synchronized(monitor) {
        // The published state changes under the same monitor as the key: a lock() between the two
        // would otherwise leave "unlocked" true with no key, or the reverse.
        if (epoch != startEpoch) {
            newKey.fill(0)
            false
        } else {
            // Replacing a held key closes its operations too: one opened under the old key must not
            // write after another key took its place (Claude review of 2026-10-11).
            key?.let {
                it.fill(0)
                epoch++
            }
            key = newKey
            v2Only = newV2Only
            unlockedState.value = true
            true
        }
    }

    /** Wipes the key and closes every operation's epoch. Callers purge the plaintext left in the cache. */
    fun lock() = synchronized(monitor) {
        epoch++
        key?.fill(0)
        key = null
        v2Only = false
        unlockedState.value = false
    }
}
