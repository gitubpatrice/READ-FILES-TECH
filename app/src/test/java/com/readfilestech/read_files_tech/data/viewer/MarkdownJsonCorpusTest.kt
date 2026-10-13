package com.readfilestech.read_files_tech.data.viewer

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.data.viewer.json.JsonContent
import com.readfilestech.read_files_tech.data.viewer.json.JsonFile
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownFile
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownRendering
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownStyle
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownTableRow
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Markdown and JSON viewers on files made by real software (`tools/corpus/markdown-json/`, see its
 * `corpus.json`), never by the code that reads them: every one opens, rendered or as a tree.
 */
class MarkdownJsonCorpusTest {
    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/markdown-json")

    private fun source(name: String): StorageFileSource {
        val file = File(corpus, name)
        check(file.isFile) { "missing corpus file: $file" }
        return StorageFileSource(file)
    }

    @Test
    fun `every file of the corpus opens as its manifest expects`() {
        val cases = JSONArray(File(corpus, "corpus.json").readText())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("file")
            assertThat(case.getString("expect")).isEqualTo("ok")
            when (case.getString("viewer")) {
                "MARKDOWN" -> {
                    val rendering = runBlocking { MarkdownFile.read(source(name)) }.rendering
                    assertWithMessage(name).that(rendering).isInstanceOf(MarkdownRendering.Rendered::class.java)
                }
                "JSON" -> {
                    val content = runBlocking { JsonFile.read(source(name)) }
                    assertWithMessage(name).that(content).isInstanceOf(JsonContent.Tree::class.java)
                }
                else -> error("not a viewer of this corpus: $case")
            }
        }
    }

    /** What PowerShell 5.1 writes, in its three encodings: the same values, accents included. */
    @Test
    fun `the three encodings of one JSON show the same values`() {
        val values = listOf("node-utf16le-bom.json", "node-latin1.json", "node-utf8-bom.json").map { name ->
            val document = (runBlocking { JsonFile.read(source(name)) } as JsonContent.Tree).document
            (0 until document.valueCount).map {
                document.keyLiteral(it, 100) to (if (document.isContainer(it)) null else document.literal(it, 100))
            }
        }
        assertThat(values[1]).isEqualTo(values[0])
        assertThat(values[2]).isEqualTo(values[0])
        assertThat(values[0]).contains("\"ville\"" to "\"Orléans\"")
        assertThat(values[0]).contains(null to "\"été\"")
    }

    /** Badges are images in links: their places show, their links lead to the links, nothing is fetched. */
    @Test
    fun `a README's table of badges keeps its links and loads no image`() {
        val content = runBlocking { MarkdownFile.read(source("jackson-core-README.md")) }
        val blocks = (content.rendering as MarkdownRendering.Rendered).blocks
        val badges = blocks.filterIsInstance<MarkdownTableRow>().first { !it.header }.cells[1]
        val image = badges.runs.first { it.style == MarkdownStyle.IMAGE }
        assertThat(image.link).isEqualTo("https://github.com/FasterXML/jackson-core/actions/workflows/main.yml")
        assertThat(blocks.toString()).doesNotContain("badge.svg")
        assertThat(blocks.filterIsInstance<MarkdownText>().first { it.heading > 0 }.text).isNotEmpty()
    }
}
