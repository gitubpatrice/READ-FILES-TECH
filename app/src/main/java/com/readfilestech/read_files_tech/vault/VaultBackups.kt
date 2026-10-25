package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.tools.toHex
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.vault.format.Argon2Params
import com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException
import com.readfilestech.read_files_tech.vault.format.VaultBackupFormat
import com.readfilestech.read_files_tech.vault.format.VaultBackupPayload
import com.readfilestech.read_files_tech.vault.format.VaultCrypto
import com.readfilestech.read_files_tech.vault.format.VaultFormatException
import com.readfilestech.read_files_tech.vault.format.VaultKdf
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `.rftvault` backups: the whole vault re-encrypted under an export password (Argon2id + AES-GCM),
 * restorable on another phone without the master password. Port of `exportToBackup` /
 * `restoreFromBackup` (2.15.3), in the same file format (VaultBackupFormat), as a STREAM (docs D-023):
 * a backup reaches 240 MB, three times what a phone's heap holds.
 *
 * What the stream changes, and how it is kept safe: a payload read as a stream is UNVERIFIED until its
 * end. A restore therefore re-encrypts each entry under the master key into a staging folder of the
 * vault, and publishes nothing before the tag of the whole backup has been checked.
 */
@Singleton
class VaultBackups @Inject constructor(
    private val dirs: VaultDirs,
    private val session: VaultSession,
    private val cipher: VaultCipher,
    private val lockouts: VaultLockoutStore,
    private val argon2: Argon2Policy,
    private val files: VaultFiles,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val restoring = Mutex()

    /** Where an export lands: [directory] holds the temporary file, [reserve] creates the final one there. */
    class Destination(val directory: File, val reserve: () -> File)

    /**
     * What a restore did. [refused] are the entries no file name could hold (counted, where the 2.15.x
     * dropped them silently); [failed] the names whose publication the file system refused.
     */
    data class RestoreResult(
        val total: Int,
        val restored: Int,
        val skipped: Int,
        val failed: List<String>,
        val refused: List<VaultBackupPayload.Refusal>,
    )

    /**
     * Writes the whole vault to a new `.rftvault` in [destination], under [password] (not the master
     * password), at this device's export parameters. Nothing is left behind on failure.
     *
     * @throws TooLargeException beyond [EXPORT_MAX_BYTES] in total, or more files than a backup holds.
     * @throws NameRefusedException a file whose name a restore would refuse: exporting it would lose it.
     */
    suspend fun export(password: String, destination: Destination): File = withContext(dispatcher) {
        session.open().use { op ->
            val sources = files.list()
            checkExportable(sources)
            val params = argon2.exportParams()
            val salt = cipher.randomBytes(VaultKdf.SALT_LEN)
            val nonce = cipher.randomBytes(VaultCrypto.NONCE_LEN)
            session.ensureOpen(op) // before the seconds of Argon2
            val exportKey = VaultKdf.argon2id(password, salt, params.memoryKB, params.iterations)
            try {
                destination.directory.mkdirs()
                val tmp = AtomicFiles.temporaryFor(File(destination.directory, EXPORT_TEMP_NAME))
                var published = false
                try {
                    writeBackup(tmp, sources, op, exportKey, salt, nonce, params)
                    session.ensureOpen(op)
                    val target = destination.reserve()
                    try {
                        AtomicFiles.publish(tmp, target)
                        published = true
                    } finally {
                        if (!published) target.delete() // the empty reservation, not a backup
                    }
                    target
                } finally {
                    if (!published) tmp.delete()
                }
            } finally {
                exportKey.fill(0)
            }
        }
    }

    /**
     * Restores [backup] into the open vault. Files already there are skipped unless [overwriteExisting].
     *
     * @throws VaultLockoutException too many failed restores (a lockout of its own, as in the 2.15.x).
     * @throws VaultAuthenticationException wrong export password, or an altered file — counted.
     * @throws VaultFormatException not a `.rftvault`, or a malformed content behind a valid tag.
     * @throws BackupTooCostlyException Argon2 parameters beyond this device: refused before deriving.
     * @throws TooLargeException beyond [RESTORE_MAX_BYTES].
     */
    suspend fun restore(backup: File, password: String, overwriteExisting: Boolean = false): RestoreResult =
        withContext(dispatcher) {
            restoring.withLock {
                session.open().use { op ->
                    val attempt = lockouts.now()
                    lockouts.ensureFree(VaultKeys.BACKUP_LOCKOUT, attempt)
                    val size = backup.length()
                    if (size > RESTORE_MAX_BYTES) throw TooLargeException(size, RESTORE_MAX_BYTES)
                    val header = VaultBackupFormat.readHeader(cipher.head(backup, VaultBackupFormat.MAX_HEADER_LEN), size)
                    if (!argon2.canAfford(header.params)) throw BackupTooCostlyException(header.params)
                    val exportKey = deriveExportKey(password, header)
                    var staging: Staging? = null
                    try {
                        val entries = Staging(op, overwriteExisting).also { staging = it }
                        val refused = readVerified(backup, size, header, exportKey, entries, attempt)
                        lockouts.clear(VaultKeys.BACKUP_LOCKOUT)
                        entries.publish(refused)
                    } finally {
                        exportKey.fill(0)
                        staging?.discard()
                    }
                }
            }
        }

    /** Everything a backup must be able to give back, checked before the seconds of Argon2. */
    /**
     * Deletes the staging folders a restore killed half-way left in the vault (entries encrypted under
     * the master key, possibly never verified). At startup: no restore can be running — and taken
     * under the restore lock all the same.
     */
    suspend fun discardLeftovers() = withContext(dispatcher) {
        restoring.withLock { deleteStagingFolders(dirs.vault) }
    }

    private fun checkExportable(sources: List<File>) {
        val (count, total) = sources.size.toLong() to sources.sumOf { it.length() }
        val excess = when {
            count > VaultBackupPayload.MAX_ENTRIES -> count to VaultBackupPayload.MAX_ENTRIES.toLong()
            total > EXPORT_MAX_BYTES -> total to EXPORT_MAX_BYTES
            else -> null
        }
        excess?.let { (size, limit) -> throw TooLargeException(size, limit) }
        sources.forEach { file ->
            val name = VaultNames.plainName(file.name)
            VaultNames.refusal(name)?.let { throw NameRefusedException(name, it) }
        }
    }

    @Suppress("LongParameterList") // the backup's own fields
    private fun writeBackup(
        tmp: File,
        sources: List<File>,
        op: VaultSession.Op,
        exportKey: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        params: Argon2Params,
    ) {
        FileOutputStream(tmp).use { out ->
            VaultBackupFormat.writeV2(out, exportKey, salt, nonce, params) { sealing ->
                val writer = VaultBackupPayload.PayloadWriter(sealing, sources.size)
                sources.forEach { file ->
                    session.ensureOpen(op) // at every file: a lock must not let the whole vault decrypt for nothing
                    val plainLength = VaultCrypto.plainLength(file.length(), cipher.head(file, MAGIC_LEN))
                    writer.entry(VaultNames.plainName(file.name), plainLength) { data ->
                        cipher.decryptFile(file, op.key, op.v2Only, data)
                    }
                }
                writer.finish()
            }
            out.flush()
            out.fd.sync()
        }
    }

    @Suppress("SwallowedException") // rethrown as the refusal it means, with its cause
    private fun deriveExportKey(password: String, header: VaultBackupFormat.Header): ByteArray = try {
        VaultKdf.argon2id(password, header.salt, header.params.memoryKB, header.params.iterations)
    } catch (e: OutOfMemoryError) {
        throw BackupTooCostlyException(header.params, e)
    }

    /**
     * Reads the payload into [staging] and returns the refused names once the TAG is verified — and
     * only then. A wrong password counts as a failure; a malformed content behind a valid tag clears
     * the lockout (the password was right) and is a format error.
     */
    @Suppress("LongParameterList") // the file, its header and key, where it goes, and the attempt it counts as
    private fun readVerified(
        backup: File,
        size: Long,
        header: VaultBackupFormat.Header,
        exportKey: ByteArray,
        staging: Staging,
        attempt: VaultLockoutStore.Attempt,
    ): List<VaultBackupPayload.Refusal> {
        val payloadLength = size - header.length - VaultCrypto.TAG_LEN
        try {
            return if (cipher.fitsInOneBlock(size)) {
                readOneBlock(backup, header, exportKey, payloadLength, staging)
            } else {
                readStreamedFile(backup, header, exportKey, payloadLength, staging)
            }
        } catch (e: VaultAuthenticationException) {
            lockouts.recordFailure(VaultKeys.BACKUP_LOCKOUT, attempt)
            throw e
        } catch (e: VaultFormatException) {
            lockouts.clear(VaultKeys.BACKUP_LOCKOUT)
            throw e
        }
    }

    /** The one-block path: the tag is checked first, as by the 2.15.x. */
    private fun readOneBlock(
        backup: File,
        header: VaultBackupFormat.Header,
        exportKey: ByteArray,
        payloadLength: Long,
        staging: Staging,
    ): List<VaultBackupPayload.Refusal> {
        val payload = VaultBackupFormat.decryptPayload(backup.readBytes(), header, exportKey)
        try {
            return VaultBackupPayload.readPayload(ByteArrayInputStream(payload), payloadLength, staging)
        } finally {
            payload.fill(0)
        }
    }

    private fun readStreamedFile(
        backup: File,
        header: VaultBackupFormat.Header,
        exportKey: ByteArray,
        payloadLength: Long,
        staging: Staging,
    ): List<VaultBackupPayload.Refusal> = FileInputStream(backup).use { input ->
        DataInputStream(input).readFully(ByteArray(header.length))
        VaultBackupFormat.openPayload(input, header, exportKey).use { readStreamed(it, payloadLength, staging) }
    }

    /**
     * The stream path. A wrong key decrypts to noise, which usually fails the parse long before the
     * end: the rest is then run through the tag before concluding, so a wrong password is still told
     * from a malformed file — and counted as one.
     */
    private fun readStreamed(opening: InputStream, payloadLength: Long, staging: Staging): List<VaultBackupPayload.Refusal> {
        val refused = try {
            VaultBackupPayload.readPayload(opening, payloadLength, staging)
        } catch (e: VaultFormatException) {
            drain(opening) // throws VaultAuthenticationException when the tag says "wrong key"
            throw e
        } catch (e: IOException) {
            // A full disk while staging noise decrypted by a wrong key: the tag still decides, so the
            // wrong password is counted (GPT review of 2026-10-11).
            drain(opening)
            throw e
        }
        drain(opening) // what follows the last entry, then the tag
        return refused
    }

    /**
     * The entries of one restore, each re-encrypted under the master key, named by its index (an
     * unverified name never names a file), in a folder of the vault that no listing shows.
     */
    private inner class Staging(
        private val op: VaultSession.Op,
        private val overwrite: Boolean,
    ) : VaultBackupPayload.EntrySink {
        private val folder = File(dirs.vault, VaultNames.STAGING_PREFIX + cipher.randomBytes(STAGING_ID_BYTES).toHex())
        private val staged = mutableListOf<Pair<String, File>>()
        private val failedToStage = mutableListOf<String>()
        private var skipped = 0

        init {
            // A restore killed half-way left its folder: none can be running now (one at a time).
            deleteStagingFolders(dirs.vault)
            folder.mkdirs()
        }

        override fun accept(name: String, size: Long, data: InputStream) {
            session.ensureOpen(op)
            val encryptedName = VaultNames.encryptedName(name)
            if (!overwrite && FileTrees.exists(File(dirs.vault, encryptedName))) {
                skipped++
                return
            }
            val file = File(folder, (staged.size + failedToStage.size).toString())
            try {
                FileOutputStream(file).use { out ->
                    cipher.encrypt(data, size, op.key, encryptedName, out)
                    out.fd.sync()
                }
                staged += name to file
            } catch (e: EOFException) {
                throw e // the payload itself is cut short: a format error, not this entry's failure
            } catch (e: IOException) {
                // A full disk, a refused write: this entry fails, the others go on, as in the 2.15.3.
                file.delete()
                failedToStage += "$name: ${e.message}"
            }
        }

        /** After the tag: each staged entry takes its place, in the backup's order. */
        fun publish(refused: List<VaultBackupPayload.Refusal>): RestoreResult {
            var restored = 0
            var skippedNow = 0
            val failed = failedToStage.toMutableList()
            for ((name, file) in staged) {
                val dest = File(dirs.vault, VaultNames.encryptedName(name))
                try {
                    // As one step with the lock state: still open, no namesake appeared, in place.
                    val placed = session.whileOpen(op) {
                        // A second entry of the same name: the first one written stays, as in the 2.15.x.
                        val free = overwrite || !FileTrees.exists(dest)
                        if (free) AtomicFiles.publish(file, dest)
                        free
                    }
                    if (placed) restored++ else skippedNow++
                } catch (e: IOException) {
                    failed += "$name: ${e.message}"
                }
            }
            return RestoreResult(staged.size + failedToStage.size + skipped, restored, skipped + skippedNow, failed, refused)
        }

        fun discard() = FileTrees.delete(folder)
    }

    companion object {
        /** The 2.15.x export cap: the whole vault in clear, 200 MiB. */
        const val EXPORT_MAX_BYTES = 200L * 1024 * 1024

        /** The 2.15.x restore cap: 1.2 × the export cap, for the envelope. */
        const val RESTORE_MAX_BYTES = EXPORT_MAX_BYTES * 6 / 5

        private const val EXPORT_TEMP_NAME = "coffre.rftvault"
        private const val STAGING_ID_BYTES = 8
        private const val MAGIC_LEN = 4
    }
}

/** Reads [input] to its end — where an opening stream checks its tag — wiping what passed through. */
private fun drain(input: InputStream) {
    val sink = ByteArray(DRAIN_CHUNK)
    try {
        while (input.read(sink) != -1) Unit
    } finally {
        sink.fill(0)
    }
}

private fun deleteStagingFolders(vault: File) {
    vault.listFiles().orEmpty()
        .filter { it.isDirectory && VaultNames.isStagingFolder(it.name) }
        .forEach(FileTrees::delete)
}

private const val DRAIN_CHUNK = 64 * 1024
