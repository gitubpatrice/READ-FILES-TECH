package com.readfilestech.read_files_tech.data.viewer.pdf

import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.SecureRandom

/**
 * The decrypted copies of the password-protected PDFs the viewer shows (docs D-005): one per viewer, in
 * `cache/pdf_unlocked/`, a folder NO FileProvider serves (`res/xml/file_paths.xml`) and PathGuard lets
 * nothing of — share and "Open with…" hand the original, encrypted file. Each copy is deleted when its
 * viewer closes; the folder is purged at every start of the main process (StartupCleanup), for a viewer
 * the system killed. PdfBox's scratch files for the decryption, in the `:pdf` process, have a folder of their
 * own ([SCRATCH_DIR]).
 */
class PdfUnlocked(
    /** `cache/pdf_unlocked/`. */
    val folder: File,
) {
    private val random = SecureRandom()

    /** A new, empty file of its own name, for one decryption. */
    fun create(): File {
        val file = File(folder, java.lang.Long.toUnsignedString(random.nextLong(), HEX) + ".pdf")
        try {
            Files.createDirectories(folder.toPath())
            Files.createFile(file.toPath())
        } catch (expected: NoSuchFileException) {
            // Another viewer's [delete] removed the folder in between: once more.
            Files.createDirectories(folder.toPath())
            Files.createFile(file.toPath())
        }
        return file
    }

    /** [copy] deleted, and the folder with it when it was the last: a viewer leaves nothing behind. */
    fun delete(copy: File) {
        copy.delete()
        folder.delete()
    }

    /** Removes every copy left; true when none is. */
    fun purge(): Boolean = FileTrees.delete(folder)

    companion object {
        /** The folder under `cacheDir`; never named in `res/xml/file_paths.xml`. */
        const val CACHE_DIR = "pdf_unlocked"

        /**
         * PdfBox's scratch, under `cacheDir`: the decrypted streams of the PDF being unlocked, a vault file's
         * included — purged with the vault's plaintext (`VaultService.PLAINTEXT_CACHE_DIRS`): at every lock, at
         * the panic, at the start. In [CACHE_DIR] it outlived the lock when `:pdf` died mid-decryption (security
         * audit of the 3.0, B1-01). Never named in `res/xml/file_paths.xml`.
         */
        const val SCRATCH_DIR = "pdf_scratch"
        private const val HEX = 16

        /** The copies of the app whose cache folder is [cache]. */
        fun under(cache: File) = PdfUnlocked(File(cache, CACHE_DIR))
    }
}
