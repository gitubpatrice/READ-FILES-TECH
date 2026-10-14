package com.readfilestech.read_files_tech.data.viewer.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessRead
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.IOException
import java.io.OutputStream

/** What [PdfUnlock.decrypt] made of a password. */
enum class UnlockOutcome {
    /** The file is written, decrypted. */
    DECRYPTED,

    /** Not the file's password (an empty one included): the viewer asks, again. */
    WRONG_PASSWORD,

    /** Encrypted for the certificates of its recipients (`/Adobe.PubSec`): no password opens it. */
    CERTIFICATE,

    /** Encrypted by a handler neither PdfBox nor PdfRenderer knows. */
    UNSUPPORTED,
}

/**
 * In the `:pdf` process only (docs D-029): a password-protected PDF, which PdfRenderer refuses, decrypted
 * by PdfBox into a file PdfRenderer then renders (docs D-005, measured by the prototype: rendered to the pixel).
 *
 * The `/Adobe.PubSec` refusal is PdfBox's OWN, and it is the guard (port plan, review of D-005). Read in the
 * bytecode of PdfBox-Android 2.0.27.0:
 * - the only code of the library that changes the JCA providers is `SecurityProvider.getProvider()`
 *   (`Security.removeProvider("BC")` then `addProvider`), called only by `PublicKeySecurityHandler`'s
 *   `createDERForRecipient` and `computeRecipientInfo` — ENCRYPTING for recipients, which RFT never does;
 * - opening, `COSParser.prepareDecryption` resolves the trailer's `/Encrypt` (an indirect object, the last
 *   trailer of an incremental update, an xref stream's dictionary: PdfBox's own parser), records it, then
 *   builds a `StandardDecryptionMaterial` from the password — a keystore is never given here — and the
 *   PubSec handler's `prepareForDecryption` throws an IOException at its FIRST instructions, on a material
 *   that is not a `PublicKeyDecryptionMaterial`, before touching BouncyCastle or any provider.
 * The handler is chosen by `PDEncryption.getFilter()` — the name decoded (`/Adobe#2EPubSec` included):
 * [FilterRecordingParser] reads that same name to say why, never a second parse of the file.
 */
object PdfUnlock {
    const val STANDARD = "Standard"
    const val PUBLIC_KEY = "Adobe.PubSec"

    /**
     * [source] decrypted with [password] into [out]. [source] is closed. PdfBox's scratch for the decrypted
     * streams follows [memory] (on disk past a few MB: a scanned PDF is mostly image streams).
     *
     * @throws IOException the file is damaged, or not a PDF.
     */
    fun decrypt(source: RandomAccessRead, password: String, out: OutputStream, memory: MemoryUsageSetting): UnlockOutcome {
        val opened = try {
            open(source, password, memory)
        } catch (e: LockedPdfException) {
            return e.outcome
        }
        opened.use {
            it.document.isAllSecurityToBeRemoved = true
            it.document.save(out)
        }
        return UnlockOutcome.DECRYPTED
    }

    /**
     * [source] opened by PdfBox with [password] — decrypted as it is read when it is encrypted, as it is
     * otherwise — for the conversions that read a PDF in the `:pdf` process (PDF → TXT). The caller owns
     * what it gets: closing it closes [source] and the scratch; on a refusal both are closed here.
     *
     * @throws LockedPdfException not the password, encrypted for certificates, or by an unknown handler.
     * @throws IOException the file is damaged, or not a PDF.
     */
    fun open(source: RandomAccessRead, password: String, memory: MemoryUsageSetting): OpenedPdf {
        val scratch = ScratchFile(memory)
        var handed = false
        try {
            val parser = FilterRecordingParser(source, password, scratch)
            val refusal = try {
                parser.parse()
                null
            } catch (expected: InvalidPasswordException) {
                LockedPdfException(UnlockOutcome.WRONG_PASSWORD)
            } catch (e: IOException) {
                when (parser.filter) {
                    null, STANDARD -> e
                    PUBLIC_KEY -> LockedPdfException(UnlockOutcome.CERTIFICATE)
                    else -> LockedPdfException(UnlockOutcome.UNSUPPORTED)
                }
            }
            if (refusal != null) throw refusal
            return OpenedPdf(parser.pdDocument, source, scratch).also { handed = true }
        } finally {
            if (!handed) {
                source.close()
                scratch.close()
            }
        }
    }
}

/** PdfBox did not open the file with the password it was given: [outcome] says why (never DECRYPTED). */
class LockedPdfException(val outcome: UnlockOutcome) : IOException(outcome.name)

/** A document PdfBox opened, with its source and the scratch its streams are read into: closed together. */
class OpenedPdf internal constructor(
    val document: PDDocument,
    private val source: RandomAccessRead,
    private val scratch: ScratchFile,
) : AutoCloseable {
    override fun close() {
        try {
            document.close()
        } finally {
            source.close()
            scratch.close()
        }
    }
}

/**
 * PdfBox's parser, which keeps the name of the encryption handler the file asks for when the opening
 * fails: `PDFParser.parse` closes and forgets its document on any exception, and with it the encryption
 * dictionary `getEncryption` reads — read here, before that, in the same parse.
 */
private class FilterRecordingParser(source: RandomAccessRead, password: String, scratch: ScratchFile) :
    PDFParser(source, password, scratch) {
    var filter: String? = null
        private set

    override fun initialParse() {
        try {
            super.initialParse()
        } catch (e: IOException) {
            filter = try {
                encryption?.filter
            } catch (expected: IOException) {
                null
            }
            throw e
        }
    }
}
