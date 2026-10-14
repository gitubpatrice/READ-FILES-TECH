package com.readfilestech.read_files_tech.data.viewer.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessRead
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
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
        val scratch = ScratchFile(memory)
        try {
            val parser = FilterRecordingParser(source, password, scratch)
            val refused = try {
                parser.parse()
                null
            } catch (expected: InvalidPasswordException) {
                UnlockOutcome.WRONG_PASSWORD
            } catch (e: IOException) {
                when (parser.filter) {
                    null, STANDARD -> throw e
                    PUBLIC_KEY -> UnlockOutcome.CERTIFICATE
                    else -> UnlockOutcome.UNSUPPORTED
                }
            }
            return refused ?: parser.pdDocument.use { document ->
                document.isAllSecurityToBeRemoved = true
                document.save(out)
                UnlockOutcome.DECRYPTED
            }
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
