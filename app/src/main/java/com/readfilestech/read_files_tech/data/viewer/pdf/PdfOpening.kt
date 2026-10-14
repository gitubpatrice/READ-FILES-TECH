package com.readfilestech.read_files_tech.data.viewer.pdf

import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import java.io.File

/**
 * How a PDF viewer opens its file — the screen's load and the hostile corpus's (CorpusCheck), the same
 * calls: a file that ends the `:pdf` process is a refusal there as here, and the main process lives on.
 *
 * Whatever is opened is handed to [keep] at once, to be closed with the viewer: each document, and each
 * decrypted copy (deleted). The password is passed on to the `:pdf` process and kept nowhere.
 */
class PdfOpening(
    private val connection: PdfConnection,
    /** The file as [com.readfilestech.read_files_tech.data.viewer.ViewerSource.acquireFile] gives it. */
    private val file: File,
    private val unlocked: PdfUnlocked,
    private val keep: (AutoCloseable) -> Unit,
) {
    /**
     * The document as it is — or, when PdfRenderer refuses it as encrypted, as PdfBox decrypts it with the
     * empty password: a file locked by an owner password only, in a scheme pdfium does not open. Blocking
     * once the `:pdf` process runs.
     *
     * @throws PdfRefusalException PASSWORD_REQUIRED (then [unlock] with the user's), CERTIFICATE, READER_STOPPED.
     */
    suspend fun start(): PdfDocument {
        connection.ready()
        return try {
            connection.open(file).also(keep)
        } catch (e: PdfRefusalException) {
            if (e.refusal != PdfRefusal.PASSWORD_REQUIRED) throw e
            unlock("") ?: throw e
        }
    }

    /**
     * The document decrypted with [password] into a private copy; null when it is not the password.
     * Blocking (Binder). Share and "Open with…" keep handing the original, encrypted file.
     *
     * @throws PdfRefusalException CERTIFICATE (no password opens it), READER_STOPPED.
     */
    fun unlock(password: String): PdfDocument? {
        val copy = unlocked.create()
        keep(AutoCloseable { unlocked.delete(copy) })
        var opened: PdfDocument? = null
        try {
            if (!connection.unlock(file, password, copy)) return null
            opened = try {
                connection.open(copy)
            } catch (e: PdfRefusalException) {
                // Decrypted, and still refused as encrypted: the copy is not what PdfBox said it wrote.
                if (e.refusal == PdfRefusal.PASSWORD_REQUIRED) throw UnreadableFileException("decrypted copy still encrypted", e)
                throw e
            }
            return opened.also(keep)
        } finally {
            if (opened == null) unlocked.delete(copy)
        }
    }
}
