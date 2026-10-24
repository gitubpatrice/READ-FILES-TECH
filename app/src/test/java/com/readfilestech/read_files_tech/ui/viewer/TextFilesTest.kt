package com.readfilestech.read_files_tech.ui.viewer

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.files.TextFiles
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** One set of text files, read by the preview, the editor, both searches and the TXT tools — and each opens in a viewer that shows it. */
class TextFilesTest {
    /** One rule wherever "Edit" is offered: a CSV goes to the CSV editor — the explorer sent it to the code editor. */
    @Test
    fun `a CSV is edited in the CSV editor, another text file in the code editor, anything else in none`() {
        assertThat(FileRouter.editorFor("/s/data.csv", "csv")).isEqualTo(Routes.feature(Feature.CSV_EDITOR, "/s/data.csv"))
        assertThat(FileRouter.editorFor("/s/notes.txt", "txt")).isEqualTo(Routes.feature(Feature.EDITOR, "/s/notes.txt"))
        assertThat(FileRouter.editorFor("/s/data.tsv", "tsv")).isEqualTo(Routes.feature(Feature.EDITOR, "/s/data.tsv"))
        assertThat(FileRouter.editorFor("/s/doc.pdf", "pdf")).isNull()
    }

    private fun entry(name: String) = FileEntry("/d/$name", name, false, 0, 0, isSymlink = false)

    @Test
    fun `every text file opens in a viewer of the app that shows its text`() {
        val showingText = setOf(ViewerKind.TEXT, ViewerKind.CSV, ViewerKind.MARKDOWN, ViewerKind.JSON, ViewerKind.HTML)
        TextFiles.EXTENSIONS.forEach { extension ->
            val viewer = ViewerKind.forExtension(extension)
            assertWithMessage(extension).that(viewer).isIn(showingText)
            assertWithMessage(extension).that(FileRouter.targetFor(entry("a.$extension")))
                .isEqualTo(FileTarget.Internal(Routes.viewer(viewer!!, "/d/a.$extension")))
        }
        // The home's "Open a file" offers them too.
        assertThat(FileRouter.VIEWABLE).containsAtLeastElementsIn(TextFiles.EXTENSIONS)
    }

    @Test
    fun `a tab-separated file opens in the CSV viewer, its columns split on the tabs`() {
        assertThat(ViewerKind.forExtension("tsv")).isEqualTo(ViewerKind.CSV)
        val table = runBlocking { CsvTable.of("name\tqty\nPommes\t3\n".toByteArray()) }
        assertThat(table.separator).isEqualTo('\t')
        assertThat(table.header(0, 2, MAX_CHARS)).containsExactly("name", "qty").inOrder()
        assertThat(table.cells(0, 0, 2, MAX_CHARS)).containsExactly("Pommes", "3").inOrder()
    }

    /** TypeScript to the 2.15.3's content search, an MPEG transport-stream video as often: left to the apps that play it. */
    @Test
    fun `a ts file is not taken for text`() {
        assertThat(TextFiles.EXTENSIONS).doesNotContain("ts")
        assertThat(ViewerKind.forExtension("ts")).isNull()
        assertThat(FileRouter.targetFor(entry("recording.ts"))).isEqualTo(FileTarget.External)
    }

    private companion object {
        const val MAX_CHARS = 100
    }
}
