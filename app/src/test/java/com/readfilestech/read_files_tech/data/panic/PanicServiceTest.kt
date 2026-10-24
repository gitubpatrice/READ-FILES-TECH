package com.readfilestech.read_files_tech.data.panic

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.trash.TrashStore
import com.readfilestech.read_files_tech.vault.TestVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What the panic erases, what it leaves, and what its report says when a step is refused. */
class PanicServiceTest {
    @TempDir
    lateinit var root: File

    private lateinit var vault: TestVault
    private lateinit var appFlutter: File
    private lateinit var volume: File
    private lateinit var panic: PanicService

    @BeforeEach
    fun setUp() {
        vault = TestVault(root)
        appFlutter = File(root, "app_flutter")
        volume = File(root, "storage/emulated/0")
        val history = EditorHistory(legacyDir = File(appFlutter, "history"), newHistoryDir = File(root, "files/editor_history"))
        val trash = TrashStore(
            SharedStorageNames(replacesFlutterApp = false),
            fallbackBase = appFlutter,
            privateRoots = listOf(root),
            visibility = { true },
            volumeBases = { listOf(volume) },
            primary = volume,
        )
        panic = PanicService(vault.service, vault.dirs, vault.prefs, history, trash, Dispatchers.Unconfined)
    }

    private fun file(parent: File, path: String) = File(parent, path).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    private fun wipe() = runBlocking { panic.wipeAll() }

    /** A device the app was used on: a vault, settings, plaintext in the cache, history, trash, old extractions. */
    private fun used() {
        runBlocking {
            vault.service.setup(PASSWORD)
            vault.files.import("secret.txt", 1, { "s".byteInputStream() })
        }
        vault.plant("recent_files", listOf("/storage/emulated/0/Download/secret.pdf"))
        vault.raw.plant("not_dart", "x")
        listOf("vault_decrypt/1/secret.txt", "file_picker/IMG_1.jpg", "pdf_unlocked/1/bank.pdf", "scanner/page1.jpg", "texte_extrait_1.txt")
            .forEach { file(vault.dirs.cache, it) }
        file(appFlutter, "history/notes.txt_1700000000000.bak")
        file(root, "files/editor_history/a1b2/1700000000000.txt")
        file(appFlutter, "contrat.zip_extracted/contrat.pdf")
        file(appFlutter, ".RFT_Corbeille.next/items/1/payload/a.txt")
        file(volume, ".RFT_Corbeille.next/items/2/payload/b.txt")
        file(volume, ".RFT_Corbeille.next/meta/unreadable.json")
    }

    @Test
    fun `everything the app keeps is erased, and the report says so`() {
        used()
        val report = wipe()

        assertThat(report).isEqualTo(ALL_ERASED)
        assertThat(report.isComplete).isTrue()
        assertThat(vault.service.unlocked.value).isFalse()
        assertThat(vault.service.isSetUp()).isFalse()
        assertThat(vault.vaultEntries()).isEmpty()
        assertThat(vault.raw.all).isEmpty()
        assertThat(vault.dirs.cache.list()).isEmpty()
        assertThat(File(appFlutter, "history").exists()).isFalse()
        assertThat(File(root, "files/editor_history").exists()).isFalse()
        assertThat(File(appFlutter, "contrat.zip_extracted").exists()).isFalse()
        assertThat(File(appFlutter, ".RFT_Corbeille.next").exists()).isFalse()
        assertThat(File(volume, ".RFT_Corbeille.next").exists()).isFalse()
    }

    /** As in the 2.15.3: the user's files, the outputs and a backup saved on shared storage are the way back. */
    @Test
    fun `the user's files, the outputs and a saved backup are left alone`() {
        used()
        val kept = listOf(
            file(volume, "Download/mine.txt"),
            file(volume, "Files Tech.next/Sauvegardes coffre/coffre_20261024.rftvault"),
            file(appFlutter, "Files Tech.next/Converties/out.pdf"),
            // A `.next` build never touches the real app's trash (docs D-017).
            file(volume, ".RFT_Corbeille/items/3/payload/c.txt"),
        )
        assertThat(wipe().isComplete).isTrue()
        assertThat(kept.filterNot(File::exists)).isEmpty()
    }

    /** The 2.15.3's own rule: a report that reassures wrongly is the worst outcome — and one refusal stops nothing. */
    @Test
    fun `a step refused is said, and the others still run`() {
        used()
        vault.raw.failCommits = true
        val report = wipe()

        assertThat(report.preferences).isFalse()
        assertThat(report.isComplete).isFalse()
        // The reset could not erase the keys: the vault's files are deleted all the same.
        assertThat(report.vault).isTrue()
        assertThat(vault.dirs.vault.list().orEmpty()).isEmpty()
        assertThat(report.copy(preferences = true).isComplete).isTrue()
        assertThat(vault.dirs.cache.list()).isEmpty()
    }

    /** Without the access to every file, a trash folder may be there unseen: not known erased (Claude review). */
    @Test
    fun `without the access to every file, the trash is not said erased`() {
        used()
        val blind = TrashStore(
            SharedStorageNames(replacesFlutterApp = false),
            fallbackBase = appFlutter,
            privateRoots = listOf(root),
            visibility = { false },
            volumeBases = { listOf(volume) },
            primary = volume,
        )
        val history = EditorHistory(File(appFlutter, "history"), File(root, "files/editor_history"))
        val blindPanic = PanicService(vault.service, vault.dirs, vault.prefs, history, blind, Dispatchers.Unconfined)
        val report = runBlocking { blindPanic.wipeAll() }
        assertThat(report.trash).isFalse()
        assertThat(report.copy(trash = true)).isEqualTo(ALL_ERASED)
    }

    @Test
    fun `on a device where the app kept nothing, the panic is complete`() {
        assertThat(wipe().isComplete).isTrue()
    }

    private companion object {
        const val PASSWORD = "essai2026"
        val ALL_ERASED = PanicService.Report(
            vault = true,
            preferences = true,
            cache = true,
            history = true,
            extractions = true,
            trash = true,
        )
    }
}
