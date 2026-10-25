package com.readfilestech.read_files_tech.data.viewer.markdown

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream

/**
 * The local images of a Markdown file: shown from the document's own folder and below, as the 2.15.3 showed
 * them (`md_viewer_screen.dart:63-93`); never a URL, a path out of the folder, a link leading out, nor for
 * the vault's text, which has no folder.
 */
class MarkdownImagesTest {
    @TempDir
    lateinit var base: File

    private lateinit var folder: File
    private lateinit var shot: File
    private lateinit var outside: File

    @BeforeEach
    fun files() {
        folder = File(base, "docs").apply { mkdirs() }
        shot = File(folder, "img/my shot.png").apply { parentFile!!.mkdirs() }.apply { writeBytes(byteArrayOf(1)) }
        outside = File(base, "secret.png").apply { writeBytes(byteArrayOf(1)) }
    }

    private fun blocks(markdown: String, images: MarkdownImages): List<MarkdownBlock> {
        val content = runBlocking { MarkdownFile.of(markdown.toByteArray(), images = images) }
        return (content.rendering as MarkdownRendering.Rendered).blocks
    }

    @Test
    fun `an image of the folder or below is shown after its text, a paragraph of images is the images`() {
        val file = shot.canonicalFile
        assertThat(MarkdownImages(folder).resolve("img/my%20shot.png")).isEqualTo(file)
        assertThat(MarkdownImages(folder).resolve("./img/my%20shot.png?raw=true#top")).isEqualTo(file)
        assertThat(blocks("![A shot](img/my%20shot.png)", MarkdownImages(folder))).containsExactly(MarkdownImage(file, "A shot"))
        assertThat(blocks("See [![it](img/my%20shot.png)](https://example.org) here", MarkdownImages(folder))).containsExactly(
            MarkdownText("See  here", emptyList()),
            MarkdownImage(file, "it", "https://example.org"),
        ).inOrder()
        // In a table cell, the icon only.
        val cell = blocks("|a|\n|-|\n|![x](img/my%20shot.png)|", MarkdownImages(folder))
        assertThat(cell.filterIsInstance<MarkdownImage>()).isEmpty()
    }

    @Test
    fun `a URL of any scheme, an absolute path, a way out of the folder, a malformed escape are refused`() {
        val refused = listOf(
            "https://example.org/img/my%20shot.png", "http://x/a.png", "data:image/png;base64,iVBORw0KGgo=",
            "file://${shot.absolutePath}", "file:img/my%20shot.png", "content://media/external/1", "javascript:alert(1)",
            shot.absolutePath, "/img/my%20shot.png", "\\img\\my%20shot.png", "//host/img.png", "C:/img/my%20shot.png",
            "../secret.png", "img/../../secret.png", "%2e%2e/secret.png", "img%2F..%2F..%2Fsecret.png",
            "img/%zz.png", "img/%2", "img/%+1.png", "img/a%00.png", "img", "missing.png", "", ".",
        )
        assertThat(refused[SYNTAX_REFUSALS - 1]).isEqualTo("img/%+1.png")
        for (destination in refused) {
            assertWithMessage(destination).that(MarkdownImages(folder).resolve(destination)).isNull()
        }
        // Refused before the storage is asked: no path at all, whatever the folder holds.
        for (destination in refused.take(SYNTAX_REFUSALS) + listOf("img/%2", "img/a%00.png")) {
            assertWithMessage(destination).that(MarkdownImages.relativePath(destination)).isNull()
        }
    }

    /** Windows refuses the tests a symbolic link: the real path is given here as the system would resolve one. */
    @Test
    fun `a link leading out of the folder is refused by its real path`() {
        val link = File(folder, "img/link.png").apply { writeBytes(byteArrayOf(1)) }
        val realPath = { file: File -> if (file.name == link.name) outside.canonicalFile else MarkdownImages.canonical(file) }
        val images = MarkdownImages(folder, realPath = realPath)
        assertThat(images.resolve("img/link.png")).isNull()
        assertThat(images.resolve("img/my%20shot.png")).isEqualTo(shot.canonicalFile)
    }

    @Test
    fun `the first local images only are looked up, refused destinations do not count`() {
        val images = MarkdownImages(folder, limit = 2)
        repeat(5) { assertThat(images.resolve("https://example.org/a.png")).isNull() }
        assertThat(images.resolve("missing.png")).isNull()
        assertThat(images.resolve("img/my%20shot.png")).isNotNull()
        assertThat(images.resolve("img/my%20shot.png")).isNull()
        // The viewer's cap, through the file's own folder.
        val document = File(folder, "README.md")
        document.writeText("![s](img/my%20shot.png)\n\n".repeat(FileCaps.MARKDOWN_IMAGES + 3))
        val read = runBlocking { MarkdownFile.read(StorageFileSource(document)) }.rendering as MarkdownRendering.Rendered
        assertThat(read.blocks.count { it is MarkdownImage }).isEqualTo(FileCaps.MARKDOWN_IMAGES)
    }

    private companion object {
        /** The refusals above told by the destination alone: up to the malformed escapes included. */
        const val SYNTAX_REFUSALS = 19
    }

    /** The vault's plaintext has no file beside it (D-028): no folder, no image. */
    @Test
    fun `a source with no file shows no image`() {
        val text = "![s](img/my%20shot.png)".toByteArray()
        val vault = object : ViewerSource {
            override val name = "README.md"
            override val shareable: File? = null

            override fun length() = text.size.toLong()

            override fun open(cap: Long): InputStream = text.inputStream()

            override fun acquireFile(cap: Long) = error("not a file")
        }
        val read = runBlocking { MarkdownFile.read(vault) }.rendering as MarkdownRendering.Rendered
        assertThat(read.blocks.filterIsInstance<MarkdownImage>()).isEmpty()
        assertThat(MarkdownImages(null).resolve("img/my%20shot.png")).isNull()
    }
}
