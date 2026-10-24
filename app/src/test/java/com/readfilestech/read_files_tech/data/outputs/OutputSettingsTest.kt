package com.readfilestech.read_files_tech.data.outputs

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.valueOrNull
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime

class OutputSettingsTest {
    @TempDir
    lateinit var tmp: File

    private val prefs = FlutterPreferences(InMemorySharedPreferences())
    private lateinit var primary: File
    private lateinit var card: File
    private lateinit var external: File
    private lateinit var private: File

    private fun outputs() = OutputStorage(prefs, { primary }, { external }, private, clock = { NOW })

    private fun settings() = OutputSettings(prefs, outputs()) { listOf(primary, card) }

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 24, 18, 0)
    }

    @BeforeEach
    fun setUp() {
        primary = File(tmp, "emulated0").apply { mkdirs() }
        card = File(tmp, "1A2B-3C4D").apply { mkdirs() }
        external = File(tmp, "android-data-files").apply { mkdirs() }
        private = File(tmp, "app_flutter").apply { mkdirs() }
    }

    @Test
    fun `a folder of a volume is kept, as its canonical path, and the outputs go there`() {
        val documents = File(primary, "Documents/Mes sorties").apply { mkdirs() }
        val choice = settings().choose(File(primary, "Documents/../Documents/Mes sorties"))
        assertThat(choice).isEqualTo(OutputSettings.Choice.Accepted(documents.canonicalFile))
        assertThat(prefs.string(OutputStorage.KEY_BASE_PATH).valueOrNull()).isEqualTo(documents.canonicalPath)
        assertThat(outputs().reserve(OutputCategory.SCANS, "scan", "pdf").parentFile).isEqualTo(File(documents.canonicalFile, "Scans"))

        // An SD card is a volume too, its root as well as any folder in it.
        assertThat(settings().choose(card)).isEqualTo(OutputSettings.Choice.Accepted(card.canonicalFile))
    }

    @Test
    fun `a folder outside the volumes, or one Android keeps for each app, is refused and nothing is written`() {
        val elsewhere = File(tmp, "elsewhere").apply { mkdirs() }
        // A sibling whose name begins as a volume's does: not inside it.
        val lookalike = File(tmp, "emulated0-copy").apply { mkdirs() }
        assertThat(settings().choose(elsewhere)).isEqualTo(OutputSettings.Choice.NotOnStorage)
        assertThat(settings().choose(lookalike)).isEqualTo(OutputSettings.Choice.NotOnStorage)
        assertThat(settings().choose(File(primary, "Android/data/com.example").apply { mkdirs() }))
            .isEqualTo(OutputSettings.Choice.AppFolder)
        // The storage compares names without case: the same folder, however it is written.
        assertThat(settings().choose(File(card, "android/OBB").apply { mkdirs() })).isEqualTo(OutputSettings.Choice.AppFolder)
        assertThat(prefs.contains(OutputStorage.KEY_BASE_PATH)).isFalse()

        // Android/media is shared storage, open to every app.
        assertThat(settings().choose(File(primary, "Android/media/x").apply { mkdirs() }))
            .isInstanceOf(OutputSettings.Choice.Accepted::class.java)
    }

    @Test
    fun `a folder that is not one, or cannot be written to, is refused`() {
        val file = File(primary, "note.txt").apply { writeText("x") }
        assertThat(settings().choose(file)).isEqualTo(OutputSettings.Choice.NotWritable)
        assertThat(settings().choose(File(primary, "absent"))).isEqualTo(OutputSettings.Choice.NotWritable)
        val readOnly = File(primary, "lecture seule").apply { mkdirs() }
        // A read-only folder, where the platform lets the test make one (not as root, nor on Windows).
        if (readOnly.setWritable(false) && !readOnly.canWrite()) {
            assertThat(settings().choose(readOnly)).isEqualTo(OutputSettings.Choice.NotWritable)
        }
        assertThat(prefs.contains(OutputStorage.KEY_BASE_PATH)).isFalse()
    }

    @Test
    fun `back to the default, the preference goes and the default follows the build`() {
        settings().choose(File(primary, "Download").apply { mkdirs() })
        assertThat(settings().reset()).isTrue()
        assertThat(prefs.contains(OutputStorage.KEY_BASE_PATH)).isFalse()
        assertThat(outputs().basePath()).isEqualTo(File(primary, "Files Tech"))
        assertThat(outputs().basePath()).isEqualTo(outputs().defaultBase)
    }

    @Test
    fun `the automatic share is written where the tools read it`() {
        assertThat(settings().setAutoShare(true)).isTrue()
        assertThat(outputs().autoShare()).isTrue()
        assertThat(settings().setAutoShare(false)).isTrue()
        assertThat(outputs().autoShare()).isFalse()
    }

    /** `ensureFolders` of the 2.15.3: every folder a tool writes to, there before the first output. */
    @Test
    fun `the folders are prepared where the outputs go, the signed PDFs' one no longer`() {
        assertThat(settings().prepareFolders()).isEqualTo(File(primary, "Files Tech"))
        assertThat(File(primary, "Files Tech").list()!!.toList())
            .containsExactly("Scans", "Conversions", "Compressions", "Sans-EXIF", "OCR", "Sauvegardes coffre", "Extractions")

        // The chosen folder replaced by a file: the fallback takes them, and is the one given.
        val chosen = File(primary, "Choisi").apply { mkdirs() }
        settings().choose(chosen)
        chosen.deleteRecursively()
        chosen.writeText("x")
        assertThat(settings().prepareFolders()).isEqualTo(File(external, "Files Tech"))

        // Nowhere at all: said as null.
        external = File(tmp, "nothing").apply { writeText("x") }
        private = File(tmp, "nothing either").apply { writeText("x") }
        assertThat(settings().prepareFolders()).isNull()
    }
}
