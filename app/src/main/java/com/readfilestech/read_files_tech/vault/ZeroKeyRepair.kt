package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead
import com.readfilestech.read_files_tech.data.prefs.valueOrNull
import com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException
import com.readfilestech.read_files_tech.vault.format.VaultCrypto
import com.readfilestech.read_files_tech.vault.format.VaultFormatException
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one-off repair of the files the 2.5.5 to 2.15.2 encrypted under a key of 32 zero bytes (D1: a
 * lock during an import or a restore), for whoever goes from those versions to the port without the
 * 2.15.3 (docs D-016). Same pass as `repairZeroKeyFilesOnce` of the 2.15.3, same keys: a repair the
 * 2.15.3 finished is never redone, one it left half-way resumes.
 *
 * Each v2 file is tried with the ZERO key only: if it opens, the real key cannot, and a sound file is
 * never decrypted in clear. An opened file is re-encrypted under the real key, under the same name,
 * checked by decrypting the result back (SHA-256 of both plaintexts), then put in place atomically —
 * provided it did not change meanwhile. Called once after an unlock; a vault the port creates is
 * marked repaired from the start.
 *
 * Window, as in the 2.15.3: accepting a zero-key ciphertext widens what the vault accepts, once. The
 * flag is an ordinary preference: whoever can WRITE the app's private storage (a rooted phone) could
 * erase it and have a planted file accepted. Backups of app data are off; nothing the app controls
 * prevents that rollback.
 */
@Singleton
class ZeroKeyRepair @Inject constructor(
    private val prefs: FlutterPreferences,
    private val session: VaultSession,
    private val cipher: VaultCipher,
    private val files: VaultFiles,
    @param:VaultDispatcher dispatcher: CoroutineDispatcher,
) {
    data class Result(val scanned: Int, val repaired: List<String>, val unexamined: List<String>)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = Any()
    private var inFlight: Pair<Long, Deferred<Result?>>? = null

    /**
     * The repair pass, if it is still due; null when done (or never needed). Two calls during the same
     * unlock share one pass; a pass of an earlier unlock (doomed by the lock) is waited for first.
     *
     * @throws VaultLockedException the vault is not open.
     * @throws LockedDuringOperationException locked during the pass: it resumes at the next unlock,
     *   without counting as an attempt.
     */
    suspend fun runOnce(): Result? {
        val job = synchronized(gate) {
            val previous = inFlight
            // The operation opens NOW, before any suspension: a lock right after the call is seen.
            val op = session.open()
            if (previous != null && previous.first == op.epoch && previous.second.isActive) {
                op.close()
                previous.second
            } else {
                scope.async {
                    previous?.second?.join()
                    op.use { scan(it) }
                }.also { inFlight = op.epoch to it }
            }
        }
        return job.await()
    }

    /** What is left to show: names repaired, names it could not examine. Null when nothing. */
    fun pendingReport(): Result? {
        val repaired = prefs.stringList(VaultKeys.ZERO_KEY_REPAIR_REPORT).valueOrNull().orEmpty()
        val unexamined = prefs.stringList(VaultKeys.ZERO_KEY_REPAIR_UNEXAMINED).valueOrNull().orEmpty()
        return if (repaired.isEmpty() && unexamined.isEmpty()) null else Result(0, repaired, unexamined)
    }

    /** The user has seen the report. */
    fun clearReport() = prefs.commitOrThrow("the repair report") {
        remove(VaultKeys.ZERO_KEY_REPAIR_REPORT)
        remove(VaultKeys.ZERO_KEY_REPAIR_UNEXAMINED)
    }

    private suspend fun scan(op: VaultSession.Op): Result? {
        // Due only when the flag is absent or false. Done — or a flag that cannot be read: nothing is
        // widened on a state the app cannot read (D-019).
        val done = prefs.boolean(VaultKeys.ZERO_KEY_REPAIR_DONE)
        if (done != PrefRead.Absent && done != PrefRead.Present(false)) return null
        val attempts = prefs.long(VaultKeys.ZERO_KEY_REPAIR_ATTEMPTS).orCorrupted(VaultKeys.ZERO_KEY_REPAIR_ATTEMPTS) ?: 0
        val skipped = setAsideCrashed()
        val report = prefs.listOrEmpty(VaultKeys.ZERO_KEY_REPAIR_REPORT).toMutableList()
        val pass = Pass()
        // Only `.enc` files: the residue of a write, or anything else, is not a vault file D1 wrote.
        for (file in files.list().filter { it.name.endsWith(VaultNames.ENC_SUFFIX) }) {
            session.ensureOpen(op)
            pass.examine(file, op, skipped, report)
        }
        val finished = pass.unexamined.isEmpty() || attempts + 1 >= MAX_ATTEMPTS
        prefs.commitOrThrow("the repair state") {
            if (finished) {
                putStringList(VaultKeys.ZERO_KEY_REPAIR_UNEXAMINED, pass.unexamined)
                putBoolean(VaultKeys.ZERO_KEY_REPAIR_DONE, true)
                remove(VaultKeys.ZERO_KEY_REPAIR_ATTEMPTS)
                remove(VaultKeys.ZERO_KEY_REPAIR_SKIPPED)
            } else {
                putLong(VaultKeys.ZERO_KEY_REPAIR_ATTEMPTS, attempts + 1)
            }
        }
        return Result(pass.scanned, pass.repaired, if (finished) pass.unexamined else emptyList())
    }

    /** The file the process died on during the previous pass (memory) is set aside, not reopened. */
    private fun setAsideCrashed(): MutableSet<String> {
        val skipped = prefs.listOrEmpty(VaultKeys.ZERO_KEY_REPAIR_SKIPPED).toMutableSet()
        val crashed = prefs.string(VaultKeys.ZERO_KEY_REPAIR_CURRENT).orCorrupted(VaultKeys.ZERO_KEY_REPAIR_CURRENT)
        if (crashed != null) {
            skipped += crashed
            prefs.commitOrThrow("the repair state") {
                putStringList(VaultKeys.ZERO_KEY_REPAIR_SKIPPED, skipped.toList())
                remove(VaultKeys.ZERO_KEY_REPAIR_CURRENT)
            }
        }
        return skipped
    }

    private inner class Pass {
        var scanned = 0
        val repaired = mutableListOf<String>()
        val unexamined = mutableListOf<String>()

        /**
         * An error on one file stops only it: it is "unexamined", the pass goes on. Its name is recorded
         * before the work, so a process killed on it sets it aside next time instead of dying again.
         */
        @Suppress("SwallowedException") // the error is the reason a file is listed "unexamined"
        fun examine(file: File, op: VaultSession.Op, skipped: Set<String>, report: MutableList<String>) {
            val label = VaultNames.plainName(file.name)
            if (file.name in skipped) {
                unexamined += label
                return
            }
            scanned++
            prefs.commitOrThrow("the repair state") { putString(VaultKeys.ZERO_KEY_REPAIR_CURRENT, file.name) }
            try {
                if (repairOne(file, op)) {
                    repaired += label
                    report += label
                    prefs.commitOrThrow("the repair report") { putStringList(VaultKeys.ZERO_KEY_REPAIR_REPORT, report) }
                }
            } catch (e: IOException) {
                unexamined += label
            } catch (e: TooLargeException) {
                unexamined += label
            } catch (e: RepairNotVerifiedException) {
                unexamined += label
            } catch (e: OutOfMemoryError) {
                unexamined += label
            } finally {
                prefs.commit { remove(VaultKeys.ZERO_KEY_REPAIR_CURRENT) }
            }
        }
    }

    /**
     * Re-encrypts [file] if the zero key opens it. True if replaced; false if sound, unreadable for
     * another reason, or changed meanwhile.
     */
    private fun repairOne(file: File, op: VaultSession.Op): Boolean {
        val size = file.length()
        val modified = file.lastModified()
        if (size > REPAIR_MAX_BYTES) throw TooLargeException(size, REPAIR_MAX_BYTES)
        // D1 wrote only v2 (it was born with the v2 format, in 2.5.5).
        val zeroKeyDigest = if (VaultCrypto.isV2(size, cipher.head(file, MAGIC_LEN))) digestOf(file, ZERO_KEY) else null
        return zeroKeyDigest != null && replace(file, op, zeroKeyDigest, size, modified)
    }

    private fun replace(file: File, op: VaultSession.Op, plainDigest: ByteArray, size: Long, modified: Long): Boolean {
        val tmp = AtomicFiles.temporaryFor(file)
        var published = false
        try {
            FileOutputStream(tmp).use { out ->
                val sealing = VaultCrypto.encryptingV2(out, op.key, cipher.randomBytes(VaultCrypto.NONCE_LEN), file.name)
                FileInputStream(file).use { VaultCrypto.decryptAuto(it, size, sealing, ZERO_KEY, file.name, v2Only = true) }
                sealing.finish()
                out.fd.sync()
            }
            // Round trip before replacing the ONLY copy: a fault of the encryption must not replace it
            // by an unreadable file.
            // Under the name it will take: the AAD binds the final name, not the temporary one.
            val check = digestOf(tmp, op.key, file.name) ?: throw RepairNotVerifiedException("the re-encrypted file does not open")
            if (!check.contentEquals(plainDigest)) throw RepairNotVerifiedException("the re-encrypted file differs")
            // A lost update: an import, a restore or a deletion may have changed the file since it was read.
            val unchanged = file.isFile && file.length() == size && file.lastModified() == modified
            if (unchanged) {
                session.ensureOpen(op) // right before the write
                AtomicFiles.publish(tmp, file)
                published = true
            }
            return published
        } catch (expected: VaultAuthenticationException) {
            return false // the file changed between the trial and the copy: left as it is
        } finally {
            if (!published) tmp.delete()
        }
    }

    /** SHA-256 of [file]'s plaintext under [key] and the AAD of [name], or null when the tag refuses it. */
    @Suppress("SwallowedException") // a refused tag is the answer "not this key"
    private fun digestOf(file: File, key: ByteArray, name: String = file.name): ByteArray? {
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            cipher.decryptFile(file, key, v2Only = true, out = DigestSink(digest), name = name)
            digest.digest()
        } catch (e: VaultAuthenticationException) {
            null
        } catch (e: VaultFormatException) {
            null
        }
    }

    private class DigestSink(private val digest: MessageDigest) : OutputStream() {
        override fun write(b: Int) = digest.update(b.toByte())

        override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
    }

    private class RepairNotVerifiedException(reason: String) : Exception(reason)

    private companion object {
        val ZERO_KEY = ByteArray(VaultCrypto.KEY_LEN)

        /** Passes leaving files unexamined after which the repair ends anyway, listing them. */
        const val MAX_ATTEMPTS = 3

        /** The largest file examined: a restore (also hit by D1) writes an entry up to a backup's size. */
        const val REPAIR_MAX_BYTES = VaultBackups.EXPORT_MAX_BYTES + 64

        const val MAGIC_LEN = 4
    }
}
