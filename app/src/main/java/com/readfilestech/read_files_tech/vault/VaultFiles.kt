package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The files of the vault: list, import, open (decrypt to the cache) and export (decrypt to a folder),
 * delete. Port of the 2.15.3's `importFileSafe`, `listFiles`, `decryptToTemp`, `exportFile`,
 * `deleteFile`, each operation on its own copy of the key and stopped by a lock before it writes (D1).
 */
@Singleton
class VaultFiles @Inject constructor(
    private val dirs: VaultDirs,
    private val session: VaultSession,
    private val cipher: VaultCipher,
    @param:VaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    /**
     * The user's files, sorted by name. Not the sentinel, not the residue of an interrupted write, not
     * a directory (the 2.15.x listed all three: D24, D26).
     */
    suspend fun list(): List<File> = withContext(dispatcher) {
        dirs.vault.listFiles().orEmpty()
            .filter { it.isFile && VaultNames.isVaultFile(it.name) }
            .sortedBy { it.name }
    }

    /**
     * Encrypts the [size] bytes [open] gives under the name [name]; returns the vault file.
     *
     * @throws NameRefusedException a name that is not a single file name, or that would replace the
     *   sentinel (D27).
     * @throws NameTakenException a file of that name is in the vault: ask, then pass [overwrite].
     * @throws TooLargeException beyond [IMPORT_MAX_BYTES] (the 2.15.x cap, kept for low-end phones).
     * @throws LockedDuringOperationException locked meanwhile: nothing is written, the caller keeps its
     *   original (the import of a folder deletes originals only after a success).
     */
    suspend fun import(name: String, size: Long, open: () -> InputStream, overwrite: Boolean = false): File =
        withContext(dispatcher) {
            session.open().use { op ->
                VaultNames.refusal(name)?.let { throw NameRefusedException(name, it) }
                if (size > IMPORT_MAX_BYTES) throw TooLargeException(size, IMPORT_MAX_BYTES)
                val encryptedName = VaultNames.encryptedName(name)
                val dest = File(dirs.vault, encryptedName)
                if (!overwrite && FileTrees.exists(dest)) throw NameTakenException(name)
                dirs.vault.mkdirs()
                AtomicFiles.write(dest) { out ->
                    open().use { input -> cipher.encrypt(input, size, op.key, encryptedName, out) }
                    session.ensureOpen(op) // right before the file appears
                }
                dest
            }
        }

    suspend fun import(source: File, overwrite: Boolean = false): File =
        import(source.name, source.length(), { FileInputStream(source) }, overwrite)

    /**
     * Decrypts [encrypted] to its own new folder of the cache, under its plain name — for a viewer or a
     * share. Each call gets its own folder: two shares of the same file never write the same path.
     * Purged on lock (VaultService.purgeDecrypted).
     */
    suspend fun decryptToCache(encrypted: File): File = withContext(dispatcher) {
        session.open().use { op ->
            val plainName = checkedPlainName(encrypted)
            val slot = File(File(dirs.cache, DECRYPT_DIR), slotName())
            slot.mkdirs()
            var kept = false
            try {
                val out = File(slot, plainName)
                AtomicFiles.write(out) { stream ->
                    cipher.decryptFile(encrypted, op.key, op.v2Only, stream)
                    session.ensureOpen(op)
                }
                // Locked WHILE the file was being published: the purge may have run before it landed.
                session.ensureOpen(op)
                kept = true
                out
            } finally {
                if (!kept) FileTrees.delete(slot)
            }
        }
    }

    /**
     * Decrypts [encrypted] into [destDir] under its plain name. Never over an existing file unless
     * [overwrite]: the user's copy would be lost before any warning (V-M2 of the 2.15.x).
     */
    suspend fun export(encrypted: File, destDir: File, overwrite: Boolean = false): File = withContext(dispatcher) {
        session.open().use { op ->
            val plainName = checkedPlainName(encrypted)
            val out = File(destDir, plainName)
            if (!overwrite && FileTrees.exists(out)) throw NameTakenException(plainName)
            AtomicFiles.write(out) { stream ->
                cipher.decryptFile(encrypted, op.key, op.v2Only, stream)
                session.ensureOpen(op)
            }
            out
        }
    }

    /** Deletes a file of the vault — only one: a path outside the vault directory is refused. */
    suspend fun delete(encrypted: File) = withContext(dispatcher) {
        require(isInVault(encrypted)) { "not a file of the vault" }
        encrypted.delete()
    }

    private fun checkedPlainName(encrypted: File): String {
        require(isInVault(encrypted)) { "not a file of the vault" }
        val plain = VaultNames.plainName(encrypted.name)
        VaultNames.refusal(plain)?.let { throw NameRefusedException(plain, it) }
        return plain
    }

    private fun isInVault(file: File) =
        file.parentFile?.canonicalFile == dirs.vault.canonicalFile && VaultNames.isVaultFile(file.name)

    private fun slotName(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.randomBytes(SLOT_BYTES))

    companion object {
        /** `cache/vault_decrypt/`, the Flutter app's name: its purge covers what the 2.15.x left. */
        const val DECRYPT_DIR = "vault_decrypt"

        /** `FileCaps.vaultBackup` of the 2.15.x: 100 MiB per imported file. */
        const val IMPORT_MAX_BYTES = 100L * 1024 * 1024

        private const val SLOT_BYTES = 9
    }
}
