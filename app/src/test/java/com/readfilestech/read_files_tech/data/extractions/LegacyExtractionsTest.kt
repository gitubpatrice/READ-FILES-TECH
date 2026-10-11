package com.readfilestech.read_files_tech.data.extractions

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** docs D-022: the old "Extract all" folders, moved only when asked, never over anything, verified. */
class LegacyExtractionsTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var appFlutter: File
    private lateinit var extractions: File
    private lateinit var legacy: LegacyExtractions

    @BeforeEach
    fun setUp() {
        appFlutter = File(tmp, "app_flutter").apply { mkdirs() }
        extractions = File(tmp, "Files Tech/Extractions")
        legacy = LegacyExtractions(appFlutter, extractions)
        File(appFlutter, "vault").mkdirs() // not an extraction
        File(appFlutter, "history").mkdirs()
    }

    private fun extraction(name: String, vararg files: Pair<String, String>): File {
        val dir = File(appFlutter, "${name}_extracted").apply { mkdirs() }
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(content) }
        return dir
    }

    @Test
    fun `only the extraction folders are found, with what they hold`() {
        extraction("photos", "a.jpg" to "aaa", "sub/b.jpg" to "bb")
        File(appFlutter, "_extracted").mkdirs() // no archive name: not one of them
        val found = legacy.find()
        assertThat(found.map { it.archiveName }).containsExactly("photos")
        assertThat(found.single().files).isEqualTo(2)
        assertThat(found.single().bytes).isEqualTo(5)
    }

    @Test
    fun `a move puts every file in Files Tech-Extractions, then removes the private folder`() {
        val dir = extraction("photos", "a.jpg" to "aaa", "sub/b.jpg" to "bb")
        val moved = legacy.move(legacy.find().single())
        assertThat(moved.moved).isEqualTo(2)
        assertThat(moved.left).isEqualTo(0)
        assertThat(moved.stoppedBy).isNull()
        assertThat(File(extractions, "photos/sub/b.jpg").readText()).isEqualTo("bb")
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `a move never goes over an existing folder`() {
        File(extractions, "photos").apply { mkdirs() }.resolve("mine.txt").writeText("mine")
        extraction("photos", "a.jpg" to "aaa")
        val moved = legacy.move(legacy.find().single())
        assertThat(moved.destination.name).isEqualTo("photos (2)")
        assertThat(File(extractions, "photos").list()!!.toList()).containsExactly("mine.txt")
    }

    @Test
    fun `a move that stops - a full disk - leaves the files not yet moved in place, and no partial copy`() {
        val dir = extraction("docs", "1.txt" to "one", "2.txt" to "two", "3.txt" to "three")
        var copies = 0
        val failing = LegacyExtractions(appFlutter, extractions) { from, to ->
            copies++
            Files.copy(from, to)
            if (copies == 2) throw IOException("No space left on device")
        }
        val moved = failing.move(failing.find().single())
        assertThat(moved.moved).isEqualTo(1)
        assertThat(moved.left).isEqualTo(2)
        assertThat(moved.stoppedBy).hasMessageThat().contains("No space")
        assertThat(File(dir, "1.txt").exists()).isFalse() // moved and verified
        assertThat(File(dir, "2.txt").readText()).isEqualTo("two") // still there, original intact
        assertThat(File(dir, "3.txt").readText()).isEqualTo("three")
        assertThat(File(moved.destination, "2.txt").exists()).isFalse() // its partial copy removed
    }

    @Test
    fun `a copy that differs from its source is not taken for a move`() {
        val dir = extraction("docs", "1.txt" to "one")
        val corrupting = LegacyExtractions(appFlutter, extractions) { _, to -> to.toFile().writeText("0ne") }
        val moved = corrupting.move(corrupting.find().single())
        assertThat(moved.moved).isEqualTo(0)
        assertThat(File(dir, "1.txt").readText()).isEqualTo("one")
    }

    @Test
    fun `a delete removes the folder and nothing else`() {
        extraction("old", "x.txt" to "x")
        assertThat(legacy.delete(legacy.find().single())).isTrue()
        assertThat(legacy.find()).isEmpty()
        assertThat(File(appFlutter, "vault").exists()).isTrue()
    }
}
