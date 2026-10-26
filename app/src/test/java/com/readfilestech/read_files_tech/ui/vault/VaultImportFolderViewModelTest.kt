package com.readfilestech.read_files_tech.ui.vault

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.testing.HeldDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessage
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.vault.TestVault
import com.readfilestech.read_files_tech.vault.VaultFiles
import com.readfilestech.read_files_tech.vault.format.VaultNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The folder's import and "Delete the originals afterwards" — D1 of the 2.15.2 (docs D-013): a lock during
 * the import encrypted the rest under a zeroed key, then deleted their originals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultImportFolderViewModelTest {
    @TempDir
    lateinit var dir: File

    private lateinit var vault: TestVault
    private lateinit var folder: File
    private val messages = UserMessages()
    private val shown = mutableListOf<UserMessage>()
    private val io = HeldDispatcher(held = false)

    /** What the media index was told is gone. */
    private val forgotten = mutableListOf<String>()
    private lateinit var listening: Job

    @BeforeEach
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        vault = TestVault(dir)
        runBlocking { vault.service.setup(PASSWORD) }
        folder = File(dir, "Download/photos").apply { mkdirs() }
        listening = CoroutineScope(main).launch { messages.showEach { shown += it } }
    }

    @AfterEach
    fun tearDown() {
        listening.cancel()
        io.held = false
        io.runAll()
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        VaultImportFolderViewModel(
            SavedStateHandle(mapOf(Routes.ARG_PATH to folder.path)),
            vault.service,
            vault.files,
            messages,
            { forgotten += it },
            io,
        )

    private fun original(name: String, text: String = name) = File(folder, name).apply { writeText(text) }

    /** The vault's files, by name, decrypted — opened for the reading if it is locked. */
    private fun inVault(): Map<String, String> = runBlocking {
        if (!vault.service.unlocked.value) vault.service.unlock(PASSWORD)
        vault.files.list().associate { encrypted ->
            val copy = vault.files.copy(encrypted, VaultFiles.Copy.VIEWER)
            VaultNames.plainName(encrypted.name) to copy.readText()
        }
    }

    private fun deletingOriginals(viewModel: VaultImportFolderViewModel) {
        viewModel.setDeleteOriginals(true)
        viewModel.encrypt()
        assertThat(viewModel.state.value.confirmDelete).isEqualTo(viewModel.state.value.selected.size)
        viewModel.confirmDelete(yes = true)
    }

    @Test
    fun `each original goes once its copy is in the vault`() {
        original("a.txt")
        original("b.txt")
        val viewModel = viewModel()
        assertThat(viewModel.state.value.entries.map { it.relative }).containsExactly("a.txt", "b.txt")
        deletingOriginals(viewModel)
        assertThat(folder.list()).isEmpty()
        assertThat(inVault()).containsExactly("a.txt", "a.txt", "b.txt", "b.txt")
        assertThat(viewModel.state.value.done).isTrue()
        // Measured on the S9 (API 29): without it, the media index kept the deleted photo's name and size.
        assertThat(forgotten).containsExactly(File(folder, "a.txt").path, File(folder, "b.txt").path)
    }

    @Test
    fun `a folder import whose files failed for one cause says it`() {
        val files = listOf(original("a.txt"), original("b.txt"))
        val viewModel = viewModel()
        files.forEach(File::delete)
        viewModel.encrypt()
        val notFound = uiText(R.string.error_not_found)
        val said = VaultMessages.imported(
            0,
            0,
            2,
            stoppedByLock = false,
            keptLabel = R.plurals.vault_namesakes_kept,
            reasons = listOf(notFound, notFound),
        )!!
        assertThat(shown.last()).isEqualTo(UserMessage(said, isError = true))
    }

    @Test
    fun `without the option, the originals stay`() {
        original("a.txt")
        val viewModel = viewModel()
        viewModel.encrypt()
        assertThat(viewModel.state.value.confirmDelete).isNull()
        assertThat(folder.list()).asList().containsExactly("a.txt")
        assertThat(inVault()).containsExactly("a.txt", "a.txt")
    }

    /** A namesake already in the vault is not replaced: its original is the only copy of THIS file. */
    @Test
    fun `a namesake is kept, and its original with it`() {
        runBlocking { vault.files.import("a.txt", 5, { "older".byteInputStream() }) }
        original("a.txt", "newer")
        original("b.txt")
        deletingOriginals(viewModel())
        assertThat(folder.list()).asList().containsExactly("a.txt")
        assertThat(inVault()).containsExactly("a.txt", "older", "b.txt", "b.txt")
    }

    @Test
    fun `a lock stops the import there, and the originals not encrypted stay`() {
        original("a.txt")
        original("b.txt")
        original("c.txt")
        val viewModel = viewModel()
        io.held = true
        deletingOriginals(viewModel)
        io.tasks.removeFirst().run() // a.txt read and encrypted; its original's deletion waits on the IO dispatcher
        vault.service.lock()
        io.held = false
        io.runAll()

        assertThat(folder.list()).asList().containsExactly("b.txt", "c.txt")
        assertThat(inVault()).containsExactly("a.txt", "a.txt")
        val summary = VaultMessages.imported(1, 0, 0, stoppedByLock = false, deleted = 1, keptLabel = R.plurals.vault_namesakes_kept)!!
        val said = UiText.Joined(summary, uiText(R.string.vault_folder_locked), R.string.joined_dot)
        assertThat(shown.last()).isEqualTo(UserMessage(said, isError = true))
        assertThat(viewModel.state.value.done).isTrue()
    }

    /**
     * "Stop", or the back key: the file under way is finished — encrypted, its original deleted as asked — and no
     * other is started; said, with the way to go on. The 2.15.3 could not stop it; the port swallowed the back key.
     */
    @Test
    fun `a stop finishes the file under way, starts no other, and says how to go on`() {
        original("a.txt")
        original("b.txt")
        original("c.txt")
        val viewModel = viewModel()
        io.held = true
        deletingOriginals(viewModel)
        io.tasks.removeFirst().run() // a.txt read and encrypted
        viewModel.stop()
        assertThat(viewModel.state.value.stopping).isTrue()
        io.held = false
        io.runAll()

        assertThat(folder.list()).asList().containsExactly("b.txt", "c.txt")
        assertThat(inVault()).containsExactly("a.txt", "a.txt")
        val summary = VaultMessages.imported(1, 0, 0, stoppedByLock = false, deleted = 1, keptLabel = R.plurals.vault_namesakes_kept)!!
        val said = UiText.Joined(summary, uiText(R.string.vault_folder_stopped), R.string.joined_dot)
        assertThat(shown.last()).isEqualTo(UserMessage(said, isError = false))
        assertThat(viewModel.state.value.done).isTrue()

        // Imported again: the stop asked before is forgotten, every file goes in.
        val again = viewModel()
        again.encrypt()
        io.runAll()
        assertThat(inVault().keys).containsExactly("a.txt", "b.txt", "c.txt")
    }

    /** GPT and Claude reviews: a file still being written, or replaced by a sync, was deleted with a copy of what it was. */
    @Test
    fun `an original changed while its copy was made is left in place, and said`() {
        val a = original("a.txt", "as read")
        val viewModel = viewModel()
        io.held = true
        deletingOriginals(viewModel)
        io.tasks.removeFirst().run() // its identity read, then the copy made
        a.appendText(", then longer")
        io.held = false
        io.runAll()

        assertThat(a.readText()).isEqualTo("as read, then longer")
        assertThat(inVault()).containsExactly("a.txt", "as read")
        assertThat(forgotten).isEmpty()
        // Written out, not computed by VaultMessages: the part that says it must be there.
        val said = UiText.Joined(
            UiText.Plural(R.plurals.vault_imported, 1),
            UiText.Plural(R.plurals.vault_originals_not_deleted, 1),
            R.string.joined_dot,
        )
        assertThat(shown.last()).isEqualTo(UserMessage(said, isError = true))
    }

    @Test
    fun `a second tap on the confirmation runs nothing more`() {
        original("a.txt")
        val viewModel = viewModel()
        deletingOriginals(viewModel)
        viewModel.confirmDelete(yes = true)
        assertThat(shown).hasSize(1)
    }

    @Test
    fun `locked while it shows, the screen goes back to the vault`() {
        original("a.txt")
        val viewModel = viewModel()
        vault.service.lock()
        assertThat(viewModel.state.value.done).isTrue()
        assertThat(folder.list()).asList().containsExactly("a.txt")
    }

    private companion object {
        const val PASSWORD = "essai2026"
    }
}
