package com.readfilestech.read_files_tech.data.convert

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.StringWriter
import kotlin.random.Random

/**
 * The layout of PDF → TXT against the 2.15.3's own (`extractPdfText`, text_extraction_service.dart:367-411),
 * re-implemented here on whole strings as the Dart code reads: what [PdfTextOutput] writes page after page,
 * in pieces, must be that, character for character.
 */
class PdfTextOutputTest {
    /** The 2.15.3: `--- Page N ---`, writeln, the page's text trimmed right, writeln between pages; all trimmed; < 30 useful: none. */
    private fun dart(pages: List<String>): String? {
        val buffer = StringBuilder()
        pages.forEachIndexed { index, text ->
            if (index > 0) buffer.append('\n')
            buffer.append("--- Page ${index + 1} ---\n").append(text.trimEnd())
        }
        val extracted = buffer.toString().trim()
        val useful = extracted.replace(Regex("---\\s*Page\\s+\\d+\\s*---"), "").replace(Regex("\\s"), "").length
        return if (useful < 30) null else extracted
    }

    /** [pages] through [PdfTextOutput], each cut into pieces of random sizes; null when it has no text to give. */
    private fun streamed(pages: List<String>, random: Random = Random(7)): String? {
        val out = StringWriter()
        val output = PdfTextOutput(out)
        pages.forEachIndexed { index, page ->
            output.startPage(index + 1)
            val text = page.trimEnd()
            var from = 0
            while (from < text.length) {
                val to = minOf(text.length, from + 1 + random.nextInt(9))
                output.text(text.substring(from, to))
                from = to
            }
        }
        return out.toString().takeIf { output.hasText }
    }

    private val sentence = "Le cœur de l’élève bat à tout rompre : 12,50 €."

    @Test
    fun `pages with text, as the 2_15_3 wrote them`() {
        val pages = listOf(sentence, "  indented\nsecond line  \n\n", "last")

        assertThat(streamed(pages)).isEqualTo(dart(pages))
        assertThat(streamed(pages)).isEqualTo("--- Page 1 ---\n$sentence\n--- Page 2 ---\n  indented\nsecond line\n--- Page 3 ---\nlast")
    }

    @Test
    fun `empty pages, first, between and last, as the 2_15_3 wrote them`() {
        for (pages in listOf(
            listOf("", sentence),
            listOf(sentence, "", sentence),
            listOf(sentence, ""),
            listOf(sentence, "   \n\t ", ""),
            listOf("", "", sentence, "", ""),
        )) {
            assertThat(streamed(pages)).isEqualTo(dart(pages))
        }
    }

    @Test
    fun `random pages, as the 2_15_3 wrote them`() {
        val random = Random(2026)
        // Whole characters, the emoji's two halves together, as a page's text is.
        val alphabet = listOf("a", "b", " ", "é", "\n", "\t", " ", "œ", "€", "日", "😀")
        repeat(500) {
            val pages = List(1 + random.nextInt(6)) {
                List(random.nextInt(40)) { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
            }
            assertThat(streamed(pages, random)).isEqualTo(dart(pages))
        }
    }

    @Test
    fun `fewer than 30 characters other than white space is a scan, 30 is text`() {
        assertThat(streamed(listOf("x".repeat(29), " \n ".repeat(100)))).isNull()
        assertThat(streamed(listOf("x".repeat(15), "y".repeat(15)))).isNotNull()
    }

    @Test
    fun `the markers never count as text`() {
        val pages = List(50) { "" }

        assertThat(streamed(pages)).isNull()
        assertThat(dart(pages)).isNull()
    }

    @Test
    fun `Kangxi radicals become the ideographs, nothing else changes`() {
        assertThat(PdfTextOutput.normalizeExtracted("⽇本⽂")).isEqualTo("日本文")
        assertThat(PdfTextOutput.normalizeExtracted("nº 3, m², ﬁ, Ⅳ")).isEqualTo("nº 3, m², ﬁ, Ⅳ")
        // A radical in the same text: only it changes.
        assertThat(PdfTextOutput.normalizeExtracted("⽇ nº 3, m², ﬁ, Ⅳ")).isEqualTo("日 nº 3, m², ﬁ, Ⅳ")
    }
}
