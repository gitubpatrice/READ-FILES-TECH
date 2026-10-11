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
 * `deleteFile`, each operation on its own copy of the key, and its output put in place only while
 * the vault is still open (D1, VaultSession.whileOpen).
 */
@Singleton
class VaultFiles @Inject constructor(
    private val dirs: VaultDirs,
    private val session: VaultSession,
    private val cipher: VaultCipher,
    @param:VaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    /**
     * The user's files, sorted by name. Not the sentinel, not the residue of an interrupted write
     * (D24, D26), not a directory, not a symbolic link: a link would have the vault read, decrypt or
     * delete through it a file it does not hold.
     */
    suspend fun list(): List<File> = withContext(dispatcher) {
        dirs.vault.listFiles().orEmpty()
            .filter { FileTrees.isRegularFile(it) && VaultNames.isVaultFile(it.name) }
            .sortedBy { it.name }
    }

    /**
     * Encrypts the [size] bytes [open] gives under the name [name]; returns the vault file.
     *
     * @throws NameRefusedException a name that is not a single file name, or that would replace the
     *   sentinel (D27).
     * @throws NameTakenException a file of that name is in the vault — checked again at the moment the
     *   file is put in place, so a concurrent import is not overwritten: ask, then pass [overwrite].
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
                // The source first: one that cannot be opened leaves nothing in the vault.
                open().use { input ->
                    dirs.vault.mkdirs()
                    AtomicFiles.write(dest, publish = { tmp, target -> publishWhileOpen(op, tmp, target, overwrite, name) }) { out ->
                        cipher.encrypt(input, size, op.key, encryptedName, out)
                    }
                }
                dest
            }
        }

    suspend fun import(source: File, overwrite: Boolean = false): File =
        import(source.name, source.length(), { FileInputStream(source) }, overwrite)

    /**
     * Decrypts [encrypted] to its own new folder of the cache, under its plain name — for a viewer or a
     * share. Each call gets its own folder: two shares of the same file never write the same path.
     * Purged on lock (VaultService.purgeDecrypted); a failure removes the folder at once.
     */
    suspend fun decryptToCache(encrypted: File): File = withContext(dispatcher) {
        session.open().use { op ->
            val plainName = checkedPlainName(encrypted)
            val slot = File(File(dirs.cache, DECRYPT_DIR), slotName())
            slot.mkdirs()
            var kept = false
            try {
                val out = File(slot, plainName)
                AtomicFiles.write(out, publish = { tmp, target -> publishWhileOpen(op, tmp, target, overwrite = true, plainName) }) {
                    cipher.decryptFile(encrypted, op.key, op.v2Only, it)
                }
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
     *
     * A file too large for one block is checked by a first pass before anything is written: the
     * destination is a folder of the user's, which no lock purges, and a temporary file there must
     * never hold plaintext that a tag later refuses (GPT review of 2026-10-11).
     */
    suspend fun export(encrypted: File, destDir: File, overwrite: Boolean = false): File = withContext(dispatcher) {
        session.open().use { op ->
            val plainName = checkedPlainName(encrypted)
            val out = File(destDir, plainName)
            if (!overwrite && FileTrees.exists(out)) throw NameTakenException(plainName)
            if (!cipher.fitsInOneBlock(encrypted.length())) cipher.verify(encrypted, op.key, op.v2Only)
            AtomicFiles.write(out, publish = { tmp, target -> publishWhileOpen(op, tmp, target, overwrite, plainName) }) {
                cipher.decryptFile(encrypted, op.key, op.v2Only, it)
            }
            out
        }
    }

    /** Deletes a file of the vault — only one, and only while the vault is open. */
    suspend fun delete(encrypted: File) = withContext(dispatcher) {
        session.open().use { op ->
            require(isInVault(encrypted)) { "not a file of the vault" }
            session.whileOpen(op) { encrypted.delete() }
        }
    }

    /** The last step of an operation, as one: still open, no namesake appeared unless [overwrite], in place. */
    private fun publishWhileOpen(op: VaultSession.Op, tmp: File, target: File, overwrite: Boolean, name: String) =
        session.whileOpen(op) {
            if (!overwrite && FileTrees.exists(target)) throw NameTakenException(name)
            AtomicFiles.publish(tmp, target)
        }

    /** The name to decrypt [encrypted] under: a control character a 2.15.x let in becomes `_`, never a refusal. */
    private fun checkedPlainName(encrypted: File): String {
        require(isInVault(encrypted)) { "not a file of the vault" }
        return VaultNames.readableName(encrypted.name)
            ?: throw NameRefusedException(encrypted.name, "no single file name can hold it")
    }

    private fun isInVault(file: File) =
        file.parentFile?.canonicalFile == dirs.vault.canonicalFile && FileTrees.isRegularFile(file) && VaultNames.isVaultFile(file.name)

    /** A fresh folder name of the decrypt cache: random, so two openings never share a path. */
    private fun slotName(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.randomBytes(SLOT_BYTES))

    companion object {
        /** `cache/vault_decrypt/`, the Flutter app's name: its purge covers what the 2.15.x left. */
        const val DECRYPT_DIR = "vault_decrypt"

        /** `FileCaps.vaultBackup` of the 2.15.x: 100 MiB per imported file. */
        const val IMPORT_MAX_BYTES = 100L * 1024 * 1024

        private const val SLOT_BYTES = 9
    }
}
