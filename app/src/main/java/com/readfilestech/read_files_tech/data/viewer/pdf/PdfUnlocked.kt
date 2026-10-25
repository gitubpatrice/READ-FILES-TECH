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
 * the system killed. PdfBox's scratch files for the decryption, in the `:pdf` process, are written here too.
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
        private const val HEX = 16

        /** The copies of the app whose cache folder is [cache]. */
        fun under(cache: File) = PdfUnlocked(File(cache, CACHE_DIR))
    }
}
