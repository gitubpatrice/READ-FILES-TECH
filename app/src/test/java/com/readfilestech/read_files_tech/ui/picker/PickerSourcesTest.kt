package com.readfilestech.read_files_tech.ui.picker

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.recents.RecentFiles
import com.readfilestech.read_files_tech.data.storage.VolumeRoot
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PickerSourcesTest {
    @TempDir
    lateinit var tmp: File

    private val prefs = FlutterPreferences(InMemorySharedPreferences())
    private val recents = RecentFiles(prefs) { true }
    private lateinit var primary: File
    private lateinit var external: File
    private var sdCard: VolumeRoot? = null

    private fun sources() = PickerSources(
        recents,
        OutputStorage(prefs, { primary }, { external }, File(tmp, "app_flutter")),
        { primary },
        { listOfNotNull(sdCard) },
    )

    private fun file(dir: File, name: String, modifiedMs: Long) = File(dir, name).apply {
        parentFile.mkdirs()
        writeText(name)
        setLastModified(modifiedMs)
    }

    @BeforeEach
    fun setUp() {
        primary = File(tmp, "emulated0").apply { mkdirs() }
        external = File(tmp, "android-data-files")
    }

    @Test
    fun `recents and outputs together, the most recent first, less the removed and the hidden`() {
        val opened = file(primary, "Download/opened.pdf", 1_000)
        recents.addOrUpdate(opened.path)
        val scan = file(primary, "Files Tech/Scans/scan.pdf", 2_000)
        // An output that fell back to the app's own folder is found too (inventory D46).
        val fallback = file(external, "Files Tech/OCR/texte.txt", 3_000)
        file(primary, "Files Tech/Scans/.hidden.pdf", 4_000)
        val removed = file(primary, "Files Tech/Conversions/removed.txt", 5_000)
        sources().remove(removed.path)

        val all = sources().recents(extensions = null).map { it.name }
        // The opened file is dated by its opening — now — hence first.
        assertThat(all).containsExactly("opened.pdf", "texte.txt", "scan.pdf").inOrder()
        assertThat(
            sources().recents(extensions = setOf("pdf")).map {
                it.path
            }
        ).containsExactly(opened.path, scan.path).inOrder()
        assertThat(fallback.exists()).isTrue()
    }

    /** Inventory D10, D45: removing one entry saved the picker's filtered list over the recents. */
    @Test
    fun `removing a file leaves the other recents alone, favourites of other types included`() {
        val pdf = file(primary, "a.pdf", 1_000)
        val text = file(primary, "b.txt", 1_000)
        recents.addOrUpdate(text.path)
        recents.toggleFavorite(text.path)
        recents.addOrUpdate(pdf.path)
        sources().recents(extensions = setOf("pdf"))
        sources().remove(pdf.path)
        assertThat(recents.load().map { it.path to it.isFavorite }).containsExactly(text.path to true)
    }

    @Test
    fun `shortcuts are the folders this phone has, files tech where the outputs go`() {
        listOf("Download", "Documents", "DCIM/Screenshots").forEach { File(primary, it).mkdirs() }
        val kinds = sources().shortcuts(extensions = null, folderMode = false)
        assertThat(kinds.map { it.kind }).containsExactly(
            ShortcutKind.DOWNLOADS,
            ShortcutKind.DOCUMENTS,
            ShortcutKind.SCREENSHOTS,
            ShortcutKind.FILES_TECH,
            ShortcutKind.APKS,
        ).inOrder()
        // Samsung's folder when it is the one there; the Pixel's otherwise (inventory F30).
        assertThat(kinds.single { it.kind == ShortcutKind.SCREENSHOTS }.dir).isEqualTo(File(primary, "DCIM/Screenshots"))
        prefs.commit { putString(OutputStorage.KEY_BASE_PATH, File(tmp, "mine").path) }
        assertThat(
            sources().shortcuts(null, false).single {
                it.kind == ShortcutKind.FILES_TECH
            }.dir
        ).isEqualTo(File(tmp, "mine"))
    }

    /** Inventory D11: the APKs tile replaced the caller's filter, so a PDF picker could return an APK. */
    @Test
    fun `the apks shortcut only where an apk is wanted, and never for a folder`() {
        fun kinds(extensions: Set<String>?, folderMode: Boolean) = sources().shortcuts(extensions, folderMode).map { it.kind }
        assertThat(kinds(setOf("pdf"), folderMode = false)).doesNotContain(ShortcutKind.APKS)
        assertThat(kinds(setOf("apk", "pdf"), folderMode = false)).contains(ShortcutKind.APKS)
        assertThat(kinds(null, folderMode = true)).doesNotContain(ShortcutKind.APKS)
        // A filter of images AND documents hides no folder (inventory F32).
        File(primary, "Pictures").mkdirs()
        assertThat(kinds(setOf("jpg", "pdf"), folderMode = false)).contains(ShortcutKind.PICTURES)
    }

    @Test
    fun `another volume is a shortcut, and the other folders are listed apart`() {
        val card = File(tmp, "1A2B-3C4D").apply { mkdirs() }
        sdCard = VolumeRoot(card, "SD card", isPrimary = false)
        listOf("Download", "Zeta", "alpha", ".secret", "Android").forEach { File(primary, it).mkdirs() }
        File(primary, "file.txt").writeText("x")
        val shortcuts = sources().shortcuts(null, false)
        assertThat(shortcuts.last()).isEqualTo(PickerShortcut(ShortcutKind.VOLUME, card, label = "SD card"))
        assertThat(sources().folders(shortcuts).map { it.name }).containsExactly("alpha", "Zeta").inOrder()
    }
}
