package com.readfilestech.read_files_tech.data.viewer

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.document.DocumentText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * The document viewer on files made by Word and PowerPoint (`tools/corpus/documents/`, see its
 * `corpus.json`), never by the code that reads them. The text Word was asked to type is known: the
 * expected text below is that, read the way the viewer reads a document. One document saved by Word as
 * .docx and as .odt shows the same lines — the two formats' rules differ only in blank lines.
 */
class DocumentCorpusTest {
    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/documents")

    private fun read(name: String): DocumentText {
        val file = File(corpus, name)
        check(file.isFile) { "missing corpus file: $file" }
        return runBlocking { DocumentText.read(StorageFileSource(file)) }
    }

    private fun text(name: String) = read(name).document.let { it.text(0, it.rowCount) }

    /**
     * Word split its runs around every accented letter, wrote the text box twice (drawing and VML
     * fallback), and the header, footer and footnote in parts of their own.
     */
    @Test
    fun `Word's docx shows its body once, in order, and nothing of the other parts`() {
        assertThat(text("word-report.docx")).isEqualTo(REPORT_DOCX)
    }

    /** Word's .odt writes the spaces at the edge of its runs as `<text:s/>`: erased, the 2.15.3 glued words. */
    @Test
    fun `Word's odt shows the same lines as its docx`() {
        val odt = text("word-report.odt")
        assertThat(odt.lines()).containsExactlyElementsIn(REPORT_DOCX.lines().filter(String::isNotBlank)).inOrder()
    }

    @Test
    fun `PowerPoint's odp shows its slides and its speaker notes`() {
        assertThat(text("powerpoint-slides.odp").lines()).containsExactly(
            "Présentation d'été",
            "Équipe de développement — octobre 2026",
            "Points clés",
            "Qualité du code",
            "Sécurité des données",
            "Café à volonté",
            "Notes de l'orateur : parler lentement.",
        ).inOrder()
    }

    /** Word 97-2003, and a .docx Word encrypted with a password: both OLE files. */
    @Test
    fun `Word's doc and its encrypted docx are a format not opened`() {
        assertThrows<UnsupportedFormatException> { read("word-report.doc") }
        assertThrows<UnsupportedFormatException> { read("word-report-password.docx") }
    }

    private companion object {
        val REPORT_DOCX = listOf(
            "Rapport d'été — Année 2026",
            "Été à la française : éàèçùôîïëœ — c'était parfait ! Ça coûte 12 € ; « guillemets ». Suite de la phrase après la note.",
            "Avant\tAprès la tabulation",
            "Après le saut de ligne",
            "Premier élément",
            "Deuxième élément",
            "Troisième élément",
            "Nom",
            "Quantité",
            "Prix",
            "Pommes",
            "3",
            "2,50 €",
            // The paragraphs of the footnote's call and of the picture: two blank lines, collapsed to one.
            "",
            "Fin du document.",
            "Texte dans une zone de texte",
        ).joinToString("\n")
    }
}
