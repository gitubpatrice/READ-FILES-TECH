package com.readfilestech.read_files_tech.data.extractions

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileMoves
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        legacy = LegacyExtractions(appFlutter, { extractions })
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
        val failing = LegacyExtractions(appFlutter, { extractions }) { from, to ->
            copies++
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING) // into its reservation
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
        assertThat(moved.destination.list()!!.toList()).containsExactly("1.txt") // and its part file
    }

    /**
     * Killed half-way through a large file, the app left it cut short under its real name, nothing to tell it from
     * the others (Claude review, phase 8): the copy goes into a hidden part file, the name stays empty until then.
     */
    @Test
    fun `a file takes its name only once copied and verified - the copy goes into a hidden part file`() {
        extraction("docs", "1.txt" to "one")
        val seen = mutableListOf<Pair<String, Long>>()
        val watching = LegacyExtractions(appFlutter, { extractions }) { from, to ->
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
            seen += to.fileName.toString() to Files.size(to.resolveSibling(from.fileName))
        }
        val moved = watching.move(watching.find().single())
        val (part, reservedSize) = seen.single()
        assertThat(part).startsWith(FileMoves.PART_PREFIX) // the app's hidden part name, as every other move's
        assertThat(reservedSize).isEqualTo(0) // the real name, still empty while the copy is written
        assertThat(File(moved.destination, "1.txt").readText()).isEqualTo("one")
        assertThat(moved.destination.list()!!.toList()).containsExactly("1.txt") // no part left behind
    }

    /** The part file named after the file, a name near the 255 bytes a volume allows stopped every move (3-axis audit, phase 8). */
    @Test
    fun `a file whose name is near the longest a volume allows is moved too`() {
        val long = "n".repeat(240) + ".txt"
        extraction("docs", long to "long")
        val moved = legacy.move(legacy.find().single())
        assertThat(moved.stoppedBy).isNull()
        assertThat(moved.moved).isEqualTo(1)
        assertThat(File(moved.destination, long).readText()).isEqualTo("long")
    }

    /** Counted as moved, a source that could not be removed stayed in both places; the next move copied it again. */
    @Test
    fun `a file that cannot be removed from the private folder is not moved - its copy goes, the move stops`() {
        val dir = extraction("docs", "1.txt" to "one", "2.txt" to "two")
        val stuck = LegacyExtractions(appFlutter, { extractions }, remove = { false })
        val moved = stuck.move(stuck.find().single())
        assertThat(moved.moved).isEqualTo(0)
        assertThat(moved.left).isEqualTo(2)
        assertThat(moved.stoppedBy).hasMessageThat().contains("could not be removed")
        assertThat(File(dir, "1.txt").readText()).isEqualTo("one")
        assertThat(moved.destination.list()!!.toList()).isEmpty()
    }

    @Test
    fun `a copy that differs from its source is not taken for a move`() {
        val dir = extraction("docs", "1.txt" to "one")
        val corrupting = LegacyExtractions(appFlutter, { extractions }) { _, to -> to.toFile().writeText("0ne") }
        val moved = corrupting.move(corrupting.find().single())
        assertThat(moved.moved).isEqualTo(0)
        assertThat(File(dir, "1.txt").readText()).isEqualTo("one")
    }

    @Test
    fun `on a case-insensitive volume a name that lands on a moved file stops the move, and deletes nothing`() {
        // `Notes.txt` then `notes.txt`: one name on FUSE casefold, sdcardfs, exFAT. Simulated by a copy
        // that, moving the first, makes the second's target appear — the moved file under its other case.
        val dir = extraction("docs", "1-Notes.txt" to "first", "2-notes.txt" to "second")
        val folding = LegacyExtractions(appFlutter, { extractions }) { from, to ->
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
            if (from.fileName.toString() == "1-Notes.txt") Files.copy(from, to.resolveSibling("2-notes.txt"))
        }
        val moved = folding.move(folding.find().single())
        assertThat(moved.moved).isEqualTo(1)
        assertThat(moved.stoppedBy).isInstanceOf(FileAlreadyExistsException::class.java)
        assertThat(File(moved.destination, "2-notes.txt").readText()).isEqualTo("first") // the moved file, intact
        assertThat(File(dir, "2-notes.txt").readText()).isEqualTo("second") // not moved, still there
    }

    @Test
    fun `an empty folder of the archive is moved too`() {
        val dir = extraction("photos", "a.jpg" to "aaa")
        File(dir, "vide/aussi vide").mkdirs()
        val moved = legacy.move(legacy.find().single())
        assertThat(File(moved.destination, "vide/aussi vide").isDirectory).isTrue()
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `what the move does not take - a link - keeps its folder`() {
        val dir = extraction("lien", "a.txt" to "a")
        val link = Files.createSymbolicLink(File(dir, "vers ailleurs").toPath(), File(tmp, "ailleurs").toPath())
        val moved = legacy.move(legacy.find().single())
        assertThat(moved.moved).isEqualTo(1)
        assertThat(Files.isSymbolicLink(link)).isTrue() // left, with the folder that holds it
        assertThat(File(dir, "a.txt").exists()).isFalse()
    }

    @Test
    fun `a delete removes the folder and nothing else`() {
        extraction("old", "x.txt" to "x")
        assertThat(legacy.delete(legacy.find().single())).isTrue()
        assertThat(legacy.find()).isEmpty()
        assertThat(File(appFlutter, "vault").exists()).isTrue()
    }
}
