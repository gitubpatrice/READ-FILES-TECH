package com.readfilestech.read_files_tech.data.convert

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.document.DocumentText
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** DOCX → TXT: the viewer's text, written whole, byte for byte, whatever its rows. */
class DocumentToTextTest {
    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/documents")

    private fun written(text: TextDocument): ByteArray {
        val out = ByteArrayOutputStream()
        DocumentToText.write(text, out)
        return out.toByteArray()
    }

    @Test
    fun `the text is written whole, long lines and many rows included`() = runTest {
        val long = "é".repeat(5000)
        val original = (List(40) { "ligne $it — œ €" } + long + "fin").joinToString("\n")

        assertThat(written(TextDocument.of(original.toByteArray())).toString(Charsets.UTF_8)).isEqualTo(original)
    }

    @Test
    fun `an empty text is an empty file`() = runTest {
        assertThat(written(TextDocument.of(ByteArray(0)))).isEmpty()
    }

    @Test
    fun `Word's report comes out as the viewer shows it, line for line`() = runTest {
        val document = DocumentText.read(StorageFileSource(File(corpus, "word-report.docx")))
        val text = document.document
        val shown = buildString {
            for (row in 0 until text.rowCount) {
                append(text.row(row))
                if (!text.continues(row) && row < text.rowCount - 1) append('\n')
            }
        }

        assertThat(document.hasText).isTrue()
        assertThat(written(text).toString(Charsets.UTF_8).trimEnd('\n')).isEqualTo(shown.trimEnd('\n'))
    }
}
