package com.readfilestech.read_files_tech.data.outputs

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.time.LocalDateTime

class OutputStorageTest {
    @TempDir
    lateinit var tmp: File

    private val prefs = FlutterPreferences(InMemorySharedPreferences())
    private val now = LocalDateTime.of(2026, 10, 12, 14, 3, 5)
    private lateinit var primary: File
    private lateinit var external: File
    private lateinit var private: File
    private var externalMounted = true

    private fun storage() = OutputStorage(prefs, { primary }, { external.takeIf { externalMounted } }, private) { now }

    @BeforeEach
    fun setUp() {
        primary = File(tmp, "emulated0").apply { mkdirs() }
        external = File(tmp, "android-data-files").apply { mkdirs() }
        private = File(tmp, "app_flutter").apply { mkdirs() }
    }

    @Test
    fun `names are cleaned as the core cleans them`() {
        assertThat(OutputStorage.sanitize("Mon rapport  final")).isEqualTo("Mon_rapport_final")
        assertThat(OutputStorage.sanitize("  ..a/b:c*?..")).isEqualTo("a_b_c")
        assertThat(OutputStorage.sanitize("tab\there\u0001x")).isEqualTo("tab_here_x")
        assertThat(OutputStorage.sanitize("")).isEqualTo("fichier")
        assertThat(OutputStorage.sanitize("._.")).isEqualTo("fichier")
        // Dart's `\s` takes the no-break space for a space; Java's own `\s` would not.
        assertThat(OutputStorage.sanitize("a\u00A0b\u2003c")).isEqualTo("a_b_c")
        assertThat(OutputStorage.sanitize("x".repeat(61))).hasLength(60)
        // 59 characters then an emoji (two UTF-16 units): cut before it, never inside.
        assertThat(OutputStorage.sanitize("a".repeat(59) + "😀")).isEqualTo("a".repeat(59))
    }

    @Test
    fun `an output goes to its category under the base, its name stamped and never reused`() {
        val first = storage().reserve(OutputCategory.SCANS, "scan", "pdf")
        val second = storage().reserve(OutputCategory.SCANS, "scan", "pdf")
        val base = File(primary, "Files Tech")
        assertThat(first).isEqualTo(File(base, "Scans/scan_20261012_140305.pdf"))
        assertThat(second).isEqualTo(File(base, "Scans/scan_20261012_140305_1.pdf"))
        assertThat(first.length()).isEqualTo(0)
    }

    /** The `.next` build installs next to the real app: it must never write into the real `Files Tech/` (D-017). */
    @Test
    fun `a next build keeps its outputs in its own suffixed folder, fallbacks included`() {
        val folder = SharedStorageNames(replacesFlutterApp = false).filesTechDir
        val next = OutputStorage(prefs, { primary }, { external }, private, folder) { now }
        assertThat(next.reserve(OutputCategory.SCANS, "scan", "pdf").parentFile).isEqualTo(File(primary, "Files Tech.next/Scans"))
        assertThat(next.bases())
            .containsExactly(File(primary, "Files Tech.next"), File(external, "Files Tech.next"), File(private, "Files Tech.next"))
            .inOrder()
    }

    @Test
    fun `the configured base is read verbatim, the 2_15_x's folder names kept`() {
        val chosen = File(tmp, "mine")
        prefs.commit { putString(OutputStorage.KEY_BASE_PATH, chosen.path) }
        assertThat(storage().basePath()).isEqualTo(chosen)
        assertThat(storage().reserve(OutputCategory.VAULT_BACKUPS, "coffre", "rftvault").parentFile)
            .isEqualTo(File(chosen, "Sauvegardes coffre"))
        assertThat(OutputCategory.entries.map { it.folderName })
            .containsExactly("Scans", "Conversions", "Compressions", "Signatures", "Sans-EXIF", "OCR", "Sauvegardes coffre", "Extractions")
            .inOrder()
    }

    /** A base it cannot write to (here a FILE where the folder should be): the app's own folders, in turn. */
    @Test
    fun `an unwritable base falls back to the app's folders, which the picker also lists`() {
        prefs.commit { putString(OutputStorage.KEY_BASE_PATH, File(tmp, "blocked").apply { writeText("x") }.path) }
        assertThat(storage().reserve(OutputCategory.OCR, "texte", "txt").parentFile).isEqualTo(File(external, "Files Tech/OCR"))
        externalMounted = false
        assertThat(storage().reserve(OutputCategory.OCR, "texte", "txt").parentFile).isEqualTo(File(private, "Files Tech/OCR"))
        assertThat(storage().bases()).containsExactly(File(tmp, "blocked"), File(private, "Files Tech")).inOrder()
    }

    /** The vault's backup: its temporary file written in the folder first, the output reserved only at the end. */
    @Test
    fun `a writer that reserves last gets the folder first - the one reserve uses, its fallbacks too`() {
        val folder = storage().folderFor(OutputCategory.VAULT_BACKUPS)
        assertThat(folder).isEqualTo(File(primary, "Files Tech/Sauvegardes coffre"))
        assertThat(storage().reserveAt(folder, "coffre", "rftvault")).isEqualTo(File(folder, "coffre_20261012_140305.rftvault"))
        assertThat(storage().reserveAt(folder, "coffre", "rftvault").name).isEqualTo("coffre_20261012_140305_1.rftvault")
        prefs.commit { putString(OutputStorage.KEY_BASE_PATH, File(tmp, "blocked").apply { writeText("x") }.path) }
        assertThat(storage().folderFor(OutputCategory.VAULT_BACKUPS)).isEqualTo(File(external, "Files Tech/Sauvegardes coffre"))
        externalMounted = false
        private = File(tmp, "nothing").apply { writeText("x") }
        assertThrows<NoOutputLocationException> { storage().folderFor(OutputCategory.VAULT_BACKUPS) }
    }

    /** The old extractions leave `app_flutter/` for shared storage (docs D-022): never for another private folder. */
    @Test
    fun `a folder of shared storage only is the base's, then the app's own, never the private fallback`() {
        assertThat(storage().folderFor(OutputCategory.EXTRACTIONS, sharedOnly = true)).isEqualTo(File(primary, "Files Tech/Extractions"))
        prefs.commit { putString(OutputStorage.KEY_BASE_PATH, File(tmp, "blocked").apply { writeText("x") }.path) }
        assertThat(storage().folderFor(OutputCategory.EXTRACTIONS, sharedOnly = true)).isEqualTo(File(external, "Files Tech/Extractions"))
        externalMounted = false
        assertThat(storage().folderFor(OutputCategory.EXTRACTIONS)).isEqualTo(File(private, "Files Tech/Extractions"))
        assertThrows<NoOutputLocationException> { storage().folderFor(OutputCategory.EXTRACTIONS, sharedOnly = true) }
    }

    @Test
    fun `nowhere to write is said, not hidden`() {
        primary = File(tmp, "nothing").apply { writeText("x") }
        externalMounted = false
        private = File(tmp, "nothing either").apply { writeText("x") }
        assertThrows<NoOutputLocationException> { storage().reserve(OutputCategory.SCANS, "scan", "pdf") }
    }

    @Test
    fun `an output is written whole into its reservation, and only there`() {
        val storage = storage()
        val reservation = storage.reserve(OutputCategory.CONVERSIONS, "doc", "txt")
        storage.write(reservation) { it.write("contenu".toByteArray()) }
        assertThat(reservation.readText()).isEqualTo("contenu")
        assertThat(reservation.parentFile.list()!!.toList()).containsExactly(reservation.name)

        // Filled meanwhile by someone else: left alone, and nothing of ours remains beside it.
        val taken = storage.reserve(OutputCategory.CONVERSIONS, "doc", "txt").apply { writeText("autre") }
        assertThrows<FileAlreadyExistsException> { storage.write(taken) { it.write("nouveau".toByteArray()) } }
        assertThat(taken.readText()).isEqualTo("autre")
        assertThat(taken.parentFile.list()!!.toList()).containsExactly(reservation.name, taken.name)
    }

    @Test
    fun `a reservation given back goes only while empty`() {
        val storage = storage()
        val unused = storage.reserve(OutputCategory.COMPRESSIONS, "img", "jpg")
        storage.discard(unused)
        assertThat(unused.exists()).isFalse()
        val filled = storage.reserve(OutputCategory.COMPRESSIONS, "img", "jpg").apply { writeText("x") }
        storage.discard(filled)
        assertThat(filled.exists()).isTrue()
    }

    /** "Extract all" (docs D-022): a new folder each time — the 2.15.x wrote into the previous one (D17). */
    @Test
    fun `a folder is reserved as a file is, new each time, never in the app's private fallback`() {
        val first = storage().reserveFolder(OutputCategory.EXTRACTIONS, "mon archive")
        val second = storage().reserveFolder(OutputCategory.EXTRACTIONS, "mon archive")
        val base = File(primary, "Files Tech/Extractions")
        assertThat(first).isEqualTo(File(base, "mon_archive_20261012_140305"))
        assertThat(second).isEqualTo(File(base, "mon_archive_20261012_140305_1"))
        assertThat(first.list()!!.toList()).isEmpty()

        primary = File(tmp, "nothing").apply { writeText("x") }
        assertThat(storage().reserveFolder(OutputCategory.EXTRACTIONS, "x").parentFile).isEqualTo(File(external, "Files Tech/Extractions"))
        // Files no other app nor the explorer could reach: refused rather than kept there (D17).
        externalMounted = false
        assertThrows<NoOutputLocationException> { storage().reserveFolder(OutputCategory.EXTRACTIONS, "x") }
        assertThat(File(private, "Files Tech/Extractions").exists()).isFalse()
    }

    @Test
    fun `sharing automatically is off unless the user turned it on`() {
        assertThat(storage().autoShare()).isFalse()
        prefs.commit { putBoolean(OutputStorage.KEY_AUTO_SHARE, true) }
        assertThat(storage().autoShare()).isTrue()
    }
}
