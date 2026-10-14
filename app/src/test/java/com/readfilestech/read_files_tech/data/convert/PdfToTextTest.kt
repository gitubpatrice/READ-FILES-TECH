package com.readfilestech.read_files_tech.data.convert

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.pdf.ChannelRandomAccess
import com.readfilestech.read_files_tech.data.viewer.pdf.LockedPdfException
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfUnlock
import com.readfilestech.read_files_tech.data.viewer.pdf.TestPdfs
import com.readfilestech.read_files_tech.data.viewer.pdf.UnlockOutcome
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * PDF → TXT, what of it the JVM can run: the opening by PdfBox with a password ([PdfUnlock.open], what the
 * `:pdf` process runs), its refusals, and the cutting of a page's text into the pieces a Binder transaction
 * carries. The text itself is PdfBox's `PDFTextStripper`, which measures glyphs with `android.graphics.Path`:
 * it runs on a phone only — `tools/oracles/convert_oracle.py check-device` compares what the phone made of
 * the corpus with pdftotext's text, page by page.
 */
class PdfToTextTest {
    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/pdf")

    @TempDir
    lateinit var tmp: File

    private fun open(file: File, password: String = "") =
        PdfUnlock.open(ChannelRandomAccess(RandomAccessFile(file, "r").channel), password, MemoryUsageSetting.setupMainMemoryOnly())

    /** Why [file] does not open with [password]. */
    private fun refusal(file: File, password: String): UnlockOutcome {
        val error = assertThrows<LockedPdfException> { open(file, password) }
        return error.outcome
    }

    private fun corpusFile(name: String) = File(corpus, name).also { check(it.isFile) { "missing corpus file: $it" } }

    @Test
    fun `a file without a password opens, its pages counted`() {
        for ((name, pages) in listOf("edge-fr-3p.pdf" to 3, "reportlab-cgu-3p.pdf" to 3, "edge-cjk.pdf" to 1)) {
            open(corpusFile(name)).use { assertThat(it.document.numberOfPages).isEqualTo(pages) }
        }
    }

    @Test
    fun `a file under an owner password only opens without asking`() {
        open(corpusFile("pypdf-owner-only.pdf")).use { assertThat(it.document.numberOfPages).isEqualTo(3) }
    }

    @Test
    fun `the password is asked, a wrong one said, the right one opens`() {
        val file = corpusFile("pypdf-aes256.pdf")

        assertThat(refusal(file, "")).isEqualTo(UnlockOutcome.WRONG_PASSWORD)
        assertThat(refusal(file, "mot2passe-é€")).isEqualTo(UnlockOutcome.WRONG_PASSWORD)
        open(file, PASSWORD).use { assertThat(it.document.numberOfPages).isEqualTo(3) }
    }

    @Test
    fun `a file encrypted for certificates is refused as such, before PdfBox decrypts anything`() {
        for (variant in TestPdfs.PubSec.entries) {
            val file = File(tmp, "pubsec-$variant.pdf").apply { writeBytes(TestPdfs.pubSec(variant)) }

            assertThat(refusal(file, "")).isEqualTo(UnlockOutcome.CERTIFICATE)
        }
    }

    @Test
    fun `hostile files are refused as damaged, never with anything but an IOException`() {
        val real = corpusFile("edge-fr-3p.pdf").readBytes()
        val files = mapOf(
            "empty" to ByteArray(0),
            "garbage" to ByteArray(10_000) { (it * 131 + 17).toByte() },
            "header-only" to "%PDF-1.7\n".toByteArray(),
            "png-named-pdf" to byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10),
        )
        for ((name, bytes) in files) {
            val file = File(tmp, "$name.pdf").apply { writeBytes(bytes) }
            assertThrows<IOException>(name) { open(file).close() }
        }
        // Cut in half: PdfBox's repair reads what is left, or refuses it — and closes the file either way.
        val cut = File(tmp, "cut.pdf").apply { writeBytes(real.copyOf(real.size / 2)) }
        try {
            open(cut).close()
        } catch (expected: IOException) {
            // Refused: a damaged file.
        }
        assertThat(cut.delete()).isTrue()
    }

    @Test
    fun `a cut ends before a surrogate pair rather than inside it`() {
        val text = "ab😀cd"

        assertThat(PdfPageTexts.cut(text, 0, 3).text).isEqualTo("ab")
        assertThat(PdfPageTexts.cut(text, 2, 3).text).isEqualTo("😀c")
        assertThat(PdfPageTexts.cut(text, 0, 4).more).isTrue()
        assertThat(PdfPageTexts.cut(text, 4, 4).more).isFalse()
        assertThat(PdfPageTexts.cut(text, 6, 4).text).isEmpty()
        assertThat(PdfPageTexts.cut(text, 6, 4).more).isFalse()
    }

    @Test
    fun `pieces of any size put back together give the page's text`() {
        val page = "Le cœur de l’élève — 😀 日本語 — " + "x".repeat(300) + "😀"
        for (max in listOf(2, 3, 5, 64, 1000)) {
            val pieces = mutableListOf<String>()
            var from = 0
            do {
                val chunk = PdfPageTexts.cut(page, from, max)
                pieces += chunk.text
                from += chunk.text.length
            } while (chunk.more)

            assertThat(pieces.joinToString("")).isEqualTo(page)
            assertThat(pieces.none { it.isNotEmpty() && it.last().isHighSurrogate() }).isTrue()
        }
    }

    private companion object {
        const val PASSWORD = "Mot2passe-é€"
    }
}
