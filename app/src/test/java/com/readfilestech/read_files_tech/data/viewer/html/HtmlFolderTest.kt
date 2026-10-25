package com.readfilestech.read_files_tech.data.viewer.html

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Inventory D20: the 2.15.3 allowed `img-src`, `style-src` and `font-src` to `file:` with no folder, and
 * `setAllowFileAccess(true)` — a page could show a private file of the app. The loader serves the page's
 * folder and nothing above it, whatever the path the page writes: these are the ways it could try.
 */
class HtmlFolderTest {
    @TempDir
    lateinit var dir: File

    /** The app's sandbox, beside the folder the page is in — as `/data/data/<package>` is for a download. */
    private lateinit var sandbox: File
    private lateinit var folder: HtmlFolder

    @BeforeEach
    fun setUp() {
        sandbox = File(dir, "data/data/$PACKAGE/shared_prefs").apply { mkdirs() }
        File(sandbox, "settings.xml").writeText("<map/>")
        val downloads = File(dir, "storage/Download").apply { mkdirs() }
        File(downloads, "page_files").mkdir()
        File(downloads, "page_files/a.png").writeBytes(byteArrayOf(1))
        File(downloads, "page.html").writeText("<p>")
        folder = requireNotNull(HtmlFolder.of(downloads))
    }

    @Test
    fun `a file of the folder, and of its subfolders, is served`() {
        assertThat(folder.resolve("page.html")?.name).isEqualTo("page.html")
        assertThat(folder.resolve("page_files/a.png")?.name).isEqualTo("a.png")
    }

    @Test
    fun `the app's private files are out of reach by any path the page writes`() {
        for (path in listOf(
            "../../data/data/$PACKAGE/shared_prefs/settings.xml",
            "page_files/../../../data/data/$PACKAGE/shared_prefs/settings.xml",
            // What the loader hands over after decoding `..%2F..%2F` and `%2E%2E/`.
            "../../data/data/$PACKAGE/shared_prefs/settings.xml".replace("/", "/./"),
            "/data/data/$PACKAGE/shared_prefs/settings.xml",
            "${dir.absolutePath}/data/data/$PACKAGE/shared_prefs/settings.xml",
            "..\\..\\data\\data\\$PACKAGE\\shared_prefs\\settings.xml",
            "page_files\\..\\..\\..\\data\\data\\$PACKAGE\\shared_prefs\\settings.xml",
            "page_files/a.png\u0000../../../data",
            // The loader only ever hands over a path of its own https address; were a whole URL to reach here,
            // it would still lead nowhere.
            "file:///data/data/$PACKAGE/shared_prefs/settings.xml",
            "content://$PACKAGE.fileprovider/root/data/data/$PACKAGE/shared_prefs/settings.xml",
        )) {
            assertWithMessage(path).that(folder.resolve(path)).isNull()
        }
    }

    @Test
    fun `names that are no file are refused`() {
        // A `..` or a backslash is refused even where it would stay inside: the path is never cleaned up.
        for (path in listOf(
            "", ".", "..", "page_files", "page_files/", "page_files//a.png", "./page.html", "missing.png",
            "page_files/../page.html", "page_files" + Char(BACKSLASH) + "a.png",
        )) {
            assertWithMessage("'$path'").that(folder.resolve(path)).isNull()
        }
    }

    /** A link of the folder that leads out of it is resolved first, and refused (canonical paths). */
    @Test
    fun `a link that leads out of the folder is refused`() {
        val link = File(dir, "storage/Download/settings.xml").toPath()
        try {
            Files.createSymbolicLink(link, File(sandbox, "settings.xml").toPath())
        } catch (expected: IOException) {
            assumeTrue(false, "no symbolic links here (Windows without the privilege)")
        } catch (expected: UnsupportedOperationException) {
            assumeTrue(false, "no symbolic links here")
        }
        assertThat(folder.resolve("settings.xml")).isNull()
    }

    private companion object {
        const val PACKAGE = "com.readfilestech.read_files_tech"
        const val BACKSLASH = 92
    }
}
