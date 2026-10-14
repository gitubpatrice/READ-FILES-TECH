package com.readfilestech.read_files_tech.data.viewer.pdf

import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * What the `:pdf` process asks PdfBox, on files made by real software (`tools/corpus/pdf/`, see its
 * corpus.json: Edge, pypdf) and on hostile files written here byte by byte (TestPdfs): the outline, the
 * password, and the refusal of a file encrypted for certificates, which is PdfBox's own (PdfUnlock).
 */
class PdfBoxReadingTest {
    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/pdf")

    @TempDir
    lateinit var tmp: File

    private fun source(file: File) = ChannelRandomAccess(RandomAccessFile(file, "r").channel)

    private fun corpusFile(name: String) = File(corpus, name).also { check(it.isFile) { "missing corpus file: $it" } }

    private fun unlock(file: File, password: String): Pair<UnlockOutcome, ByteArray> {
        val out = ByteArrayOutputStream()
        val outcome = PdfUnlock.decrypt(source(file), password, out, MemoryUsageSetting.setupMainMemoryOnly())
        return outcome to out.toByteArray()
    }

    @Test
    fun `Edge's outline is listed in order, two levels, each entry on its chapter's page`() {
        val entries = PdfBoxOutline.read(source(corpusFile("edge-outline.pdf")), pageCount = 3)

        assertThat(entries).containsExactly(
            OutlineEntry("Chapitre 1 — Début", 0, 0),
            OutlineEntry("Section 1.1 — Contexte", 1, 0),
            OutlineEntry("Section 1.2 — Objectifs", 1, 0),
            OutlineEntry("Chapitre 2 — Milieu", 0, 1),
            OutlineEntry("Section 2.1 — Méthode", 1, 1),
            OutlineEntry("Chapitre 3 — Fin", 0, 2),
        ).inOrder()
    }

    @Test
    fun `an entry leading past the pages PdfRenderer counts leads nowhere`() {
        val entries = PdfBoxOutline.read(source(corpusFile("edge-outline.pdf")), pageCount = 2)

        assertThat(entries.last()).isEqualTo(OutlineEntry("Chapitre 3 — Fin", 0, null))
    }

    @Test
    fun `a file without an outline has none`() {
        assertThat(PdfBoxOutline.read(source(corpusFile("edge-fr-3p.pdf")), pageCount = 3)).isEmpty()
    }

    @Test
    fun `pypdf's AES-256 file opens with its password, and the copy is no longer encrypted`() {
        val (outcome, bytes) = unlock(corpusFile("pypdf-aes256.pdf"), PASSWORD)

        assertThat(outcome).isEqualTo(UnlockOutcome.DECRYPTED)
        val copy = File(tmp, "copy.pdf").apply { writeBytes(bytes) }
        // The empty password now opens it, and its text streams are readable: no /Encrypt is left.
        assertThat(unlock(copy, "").first).isEqualTo(UnlockOutcome.DECRYPTED)
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("/Encrypt")
    }

    @Test
    fun `a file locked by an owner password only opens with the empty one, never asking`() {
        assertThat(unlock(corpusFile("pypdf-owner-only.pdf"), "").first).isEqualTo(UnlockOutcome.DECRYPTED)
    }

    @Test
    fun `a wrong password, the empty one included, is asked again`() {
        assertThat(unlock(corpusFile("pypdf-aes256.pdf"), "pas-le-bon").first).isEqualTo(UnlockOutcome.WRONG_PASSWORD)
        assertThat(unlock(corpusFile("pypdf-aes256.pdf"), "").first).isEqualTo(UnlockOutcome.WRONG_PASSWORD)
    }

    @Test
    fun `a file encrypted for certificates is refused as such, in each form its dictionary can take`() {
        for (variant in TestPdfs.PubSec.entries) {
            val file = File(tmp, "${variant.name}.pdf").apply { writeBytes(TestPdfs.pubSec(variant)) }

            assertThat(unlock(file, "").first).isEqualTo(UnlockOutcome.CERTIFICATE)
            assertThat(unlock(file, PASSWORD).first).isEqualTo(UnlockOutcome.CERTIFICATE)
        }
    }

    @Test
    fun `an encryption handler nobody knows is unsupported, not a damaged file`() {
        val file = File(tmp, "other.pdf").apply { writeBytes(TestPdfs.encryptedBy("/FOO.Unknown")) }

        assertThat(unlock(file, "").first).isEqualTo(UnlockOutcome.UNSUPPORTED)
    }

    @Test
    fun `the PubSec refusal touches no JCA provider`() {
        val before = java.security.Security.getProviders().map { "${it.name}@${System.identityHashCode(it)}" }
        val file = File(tmp, "pubsec.pdf").apply { writeBytes(TestPdfs.pubSec(TestPdfs.PubSec.DIRECT)) }

        unlock(file, PASSWORD)

        assertThat(java.security.Security.getProviders().map { "${it.name}@${System.identityHashCode(it)}" }).isEqualTo(before)
    }

    private companion object {
        /** The password `tools/corpus/make_pdf.py` gave pypdf. */
        const val PASSWORD = "Mot2passe-é€"
    }
}
