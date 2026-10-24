package com.readfilestech.read_files_tech.data.legacy

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.extractions.LegacyExtractions
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.outputs.NoOutputLocationException
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** docs D-021, D-022: what the 2.x left, offered once, moved or deleted only when asked. */
class LegacyDataTest {
    @TempDir
    lateinit var tmp: File

    private val prefs = FlutterPreferences(InMemorySharedPreferences())
    private lateinit var appFlutter: File
    private lateinit var root: File
    private lateinit var history: EditorHistory
    private var noFolder = false
    private var failOn: String? = null
    private val workingDuringCopy = mutableListOf<Boolean>()
    private lateinit var legacy: LegacyData

    @BeforeEach
    fun setUp() {
        appFlutter = File(tmp, "app_flutter").apply { mkdirs() }
        root = File(tmp, "Files Tech/Extractions")
        history = EditorHistory(File(appFlutter, "history"), File(tmp, "files/editor_history"))
        val target = { if (noFolder) throw NoOutputLocationException(null) else root }
        val extractions = LegacyExtractions(appFlutter, target) { from, to ->
            workingDuringCopy += legacy.working.value
            if (from.fileName.toString() == failOn) throw IOException("No space left on device")
            Files.newOutputStream(to, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { Files.copy(from, it) }
        }
        legacy = LegacyData(extractions, history, prefs)
    }

    private fun extraction(name: String, vararg files: String): File {
        val dir = File(appFlutter, "${name}_extracted").apply { mkdirs() }
        files.forEach { File(dir, it).writeText(it) }
        return dir
    }

    private fun oldCopy(name: String) = File(appFlutter, "history/$name").apply { parentFile.mkdirs() }.writeText("old")

    @Test
    fun `the notice is claimed once in the app's life, and offered only when old extractions are there`() {
        assertThat(legacy.claimNotice()).isFalse()
        extraction("photos", "a.jpg")
        // Claimed with nothing to offer: never asked again, the settings keep the choice.
        assertThat(legacy.claimNotice()).isFalse()

        prefs.commit { remove(LegacyData.KEY_NOTICE) }
        assertThat(legacy.claimNotice()).isTrue()
        assertThat(legacy.claimNotice()).isFalse()

        // A value that cannot be read counts as claimed, and stays as it is.
        prefs.commit { putString(LegacyData.KEY_NOTICE, "yes") }
        assertThat(legacy.claimNotice()).isFalse()
        assertThat(prefs.contains(LegacyData.KEY_NOTICE)).isTrue()
    }

    @Test
    fun `what is left is counted - files and bytes, the old history's copies`() {
        extraction("a", "one", "three")
        extraction("b", "five5")
        oldCopy("notes.txt_1700000000000.bak")
        oldCopy("not a copy.txt")
        val summary = legacy.summary()
        assertThat(summary.extractions.map { it.archiveName }).containsExactly("a", "b").inOrder()
        assertThat(summary.extractionFiles).isEqualTo(3)
        assertThat(summary.extractionBytes).isEqualTo(13)
        assertThat(summary.history.map { it.file.name }).containsExactly("notes.txt_1700000000000.bak")
        assertThat(summary.historyBytes).isEqualTo(3)
        assertThat(summary.isEmpty).isFalse()
    }

    @Test
    fun `every old extraction is moved, verified, and where they went is said`() = runBlocking<Unit> {
        extraction("a", "a1", "a2")
        extraction("b", "b1")
        val moved = legacy.moveExtractions()
        assertThat(moved).isEqualTo(LegacyData.Moved(moved = 3, left = 0, destination = root, stoppedBy = null))
        assertThat(File(root, "a/a2").readText()).isEqualTo("a2")
        assertThat(File(root, "b/b1").readText()).isEqualTo("b1")
        assertThat(legacy.summary().extractions).isEmpty()
        assertThat(workingDuringCopy).containsExactly(true, true, true)
        assertThat(legacy.working.value).isFalse()
    }

    @Test
    fun `a move that stops - no space left - stops the rest, what is left counted and in place`() = runBlocking<Unit> {
        extraction("a", "a1", "a2")
        val b = extraction("b", "b1")
        failOn = "a2"
        val moved = legacy.moveExtractions()
        assertThat(moved.moved).isEqualTo(1)
        assertThat(moved.left).isEqualTo(2)
        assertThat(moved.stoppedBy).hasMessageThat().contains("No space left")
        assertThat(File(appFlutter, "a_extracted/a2").exists()).isTrue()
        assertThat(File(b, "b1").exists()).isTrue()
        assertThat(File(root, "b").exists()).isFalse()
    }

    @Test
    fun `no folder of shared storage to take them - nothing moved, every file still there`() = runBlocking<Unit> {
        extraction("a", "a1", "a2")
        extraction("b", "b1")
        noFolder = true
        val moved = legacy.moveExtractions()
        assertThat(moved.moved).isEqualTo(0)
        assertThat(moved.left).isEqualTo(3)
        assertThat(moved.destination).isNull()
        assertThat(moved.stoppedBy).isInstanceOf(NoOutputLocationException::class.java)
        assertThat(legacy.summary().extractionFiles).isEqualTo(3)
    }

    @Test
    fun `the old extractions and the old history are deleted, the port's own history stays`() = runBlocking<Unit> {
        extraction("a", "a1")
        oldCopy("notes.txt_1700000000000.bak")
        history.saveBefore("/storage/notes.txt", "v1".toByteArray(), nowMs = 1_800_000_000_000)
        assertThat(legacy.deleteExtractions()).isTrue()
        assertThat(legacy.deleteHistory()).isTrue()
        assertThat(legacy.summary().isEmpty).isTrue()
        assertThat(history.versions("/storage/notes.txt")).hasSize(1)
        assertThat(File(appFlutter, "history").exists()).isFalse()
    }
}
