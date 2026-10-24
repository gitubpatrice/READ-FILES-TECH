package com.readfilestech.read_files_tech.ui.vault

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.testing.HeldDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessage
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.vault.TestVault
import com.readfilestech.read_files_tech.vault.VaultFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The vault's screen over the real data layer (TestVault): what a lock takes off the screen, the questions
 * of an import and a restore, and where its passwords are not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {
    @TempDir
    lateinit var dir: File

    private lateinit var vault: TestVault
    private val messages = UserMessages()
    private val shown = mutableListOf<UserMessage>()
    private val store = ViewModelStore()
    private lateinit var listening: Job

    @BeforeEach
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        vault = TestVault(dir)
        listening = CoroutineScope(main).launch { messages.showEach { shown += it } }
    }

    @AfterEach
    fun tearDown() {
        listening.cancel()
        store.clear()
        Dispatchers.resetMain()
    }

    /** The screen as the app makes it: in a store, whose clearing is the screen left. */
    private fun viewModel(io: CoroutineDispatcher = Dispatchers.Unconfined): VaultViewModel {
        val outputs = OutputStorage(vault.prefs, { File(dir, "storage") }, { null }, File(dir, "app_flutter"))
        val factory = viewModelFactory {
            initializer {
                with(vault) { VaultViewModel(service, files, dirs, cipher, backups, repair, outputs, messages, io) }
            }
        }
        return ViewModelProvider.create(store, factory)[VaultViewModel::class]
    }

    private fun opened(io: CoroutineDispatcher = Dispatchers.Unconfined) = viewModel(io).apply {
        setup(PASSWORD)
        assertThat(state.value.phase).isEqualTo(VaultPhase.OPEN)
    }

    private fun source(name: String, text: String) = File(dir, "picked/$name").apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun VaultViewModel.import(vararg sources: File) {
        pickFor(VaultPick.IMPORT)
        picked(sources.map { it.path }, folder = null)
    }

    private fun VaultViewModel.item(name: String) = state.value.files.single { it.name == name }

    private fun VaultViewModel.plain(name: String) =
        vault.files.copy(File(vault.dirs.vault, item(name).encryptedName), VaultFiles.Copy.VIEWER).readText()

    @Test
    fun `whatever locks the vault takes its files' names off the screen, its question and its share`() {
        val viewModel = opened()
        viewModel.import(source("a.txt", "one"))
        val item = viewModel.item("a.txt")
        viewModel.share(item)
        assertThat(viewModel.state.value.share).isNotNull()
        viewModel.askDelete(item)

        vault.service.lock() // the auto-lock, the panic: not this screen's button
        val state = viewModel.state.value
        assertThat(state.phase).isEqualTo(VaultPhase.LOCKED)
        assertThat(state.files).isEmpty()
        assertThat(state.dialog).isNull()
        assertThat(state.share).isNull()
    }

    /** Leaving the screen is being done with the vault: the 2.15.x left it open, to be reopened without a password (V-H1). */
    @Test
    fun `leaving the screen locks the vault`() {
        opened()
        store.clear()
        assertThat(vault.service.unlocked.value).isFalse()
    }

    /** The unlock installs its key once derived: the screen left before, the vault opened with no screen. */
    @Test
    fun `leaving the screen during an unlock leaves the vault locked`() {
        val viewModel = opened()
        vault.service.lock()
        var left = false
        vault.clock.onWallRead = {
            if (!left) {
                left = true
                store.clear()
            }
        }
        viewModel.unlock(PASSWORD)
        assertThat(left).isTrue()
        assertThat(vault.service.unlocked.value).isFalse()
    }

    /** The list read before the lock lands after it: the names would come back over a locked vault. */
    @Test
    fun `a list read while the vault locks stays off the screen`() {
        val io = HeldDispatcher(held = false)
        val viewModel = opened(io)
        viewModel.import(source("a.txt", "one"))
        assertThat(viewModel.state.value.files).hasSize(1)
        vault.service.lock()
        viewModel.unlock(PASSWORD)
        io.held = true
        try {
            viewModel.refresh()
            vault.service.lock()
        } finally {
            io.held = false
            io.runAll()
        }
        assertThat(viewModel.state.value.files).isEmpty()
    }

    @Test
    fun `a namesake is replaced only when the user says so`() {
        val viewModel = opened()
        val a = source("a.txt", "one")
        viewModel.import(a)
        a.writeText("two")

        viewModel.import(a)
        assertThat(viewModel.state.value.dialog).isEqualTo(VaultDialog.Namesake("a.txt"))
        viewModel.answer(yes = false)
        assertThat(viewModel.plain("a.txt")).isEqualTo("one")
        assertThat(shown.last()).isEqualTo(UserMessage(VaultMessages.imported(0, 1, 0, stoppedByLock = false)!!))

        viewModel.import(a)
        viewModel.answer(yes = true)
        assertThat(viewModel.state.value.dialog).isNull()
        assertThat(viewModel.plain("a.txt")).isEqualTo("two")
    }

    /** The 2.15.3 counted the files after a lock as errors, and its "Replace?" waited over a locked vault. */
    @Test
    fun `a lock while a namesake is asked about keeps the file, and stops the import there`() {
        val viewModel = opened()
        val a = source("a.txt", "one")
        viewModel.import(a)
        a.writeText("two")

        viewModel.import(a, source("b.txt", "b"), source("c.txt", "c"))
        assertThat(viewModel.state.value.dialog).isEqualTo(VaultDialog.Namesake("a.txt"))
        vault.service.lock()
        assertThat(viewModel.state.value.work).isNull()
        assertThat(shown.last()).isEqualTo(UserMessage(VaultMessages.imported(0, 1, 0, stoppedByLock = true)!!, isError = true))

        viewModel.unlock(PASSWORD)
        assertThat(viewModel.state.value.files.map { it.name }).containsExactly("a.txt")
        assertThat(viewModel.plain("a.txt")).isEqualTo("one")
    }

    /** A backup of the vault restored over it unchanged: "nothing restored, all already there", then Replace. */
    private fun VaultViewModel.allAlreadyThere() {
        val a = source("a.txt", "one")
        import(a)
        askBackup()
        assertThat(state.value.dialog).isEqualTo(VaultDialog.BackupPassword)
        backup(BACKUP_PASSWORD)
        val backup = (state.value.dialog as VaultDialog.BackupDone).path
        dismiss()
        a.writeText("two")
        import(a)
        answer(yes = true)

        pickFor(VaultPick.RESTORE)
        picked(listOf(backup), folder = null)
        assertThat(state.value.dialog).isEqualTo(VaultDialog.RestorePassword(backup))
        restore(backup, BACKUP_PASSWORD)
        assertThat(state.value.dialog).isEqualTo(VaultDialog.RestoreAllThere(1))
    }

    @Test
    fun `all already there, Replace restores the same backup over the vault`() {
        val viewModel = opened()
        viewModel.allAlreadyThere()
        viewModel.restoreReplacing()
        assertThat(viewModel.state.value.dialog).isNull()
        assertThat(viewModel.plain("a.txt")).isEqualTo("one")
    }

    /** The backup's password is held between the two questions only: closed, or the vault locked, it is gone. */
    @Test
    fun `the backup's password goes with its question`() {
        val viewModel = opened()
        viewModel.allAlreadyThere()
        viewModel.dismiss()
        viewModel.restoreReplacing()
        assertThat(viewModel.plain("a.txt")).isEqualTo("two")

        viewModel.allAlreadyThereAgain()
        vault.service.lock()
        viewModel.unlock(PASSWORD)
        viewModel.restoreReplacing()
        assertThat(viewModel.plain("a.txt")).isEqualTo("two")
    }

    /** The same backup restored again, up to "all already there"; the vault still holds "two". */
    private fun VaultViewModel.allAlreadyThereAgain() {
        val backup = File(dir, "storage").walk().single { it.extension == "rftvault" }.path
        restore(backup, BACKUP_PASSWORD)
        assertThat(state.value.dialog).isEqualTo(VaultDialog.RestoreAllThere(1))
    }

    @Test
    fun `no password is kept in the state or a message`() {
        val viewModel = opened()
        viewModel.unlock("wrong password")
        vault.service.lock()
        viewModel.unlock("wrong password")
        assertThat(viewModel.state.value.error).isEqualTo(uiText(R.string.vault_wrong_password))
        viewModel.unlock(PASSWORD)
        viewModel.allAlreadyThere()

        val kept = listOf(viewModel.state.value.toString(), shown.toString())
        for (password in listOf(PASSWORD, BACKUP_PASSWORD, "wrong password")) {
            assertThat(kept.filter { password in it }).isEmpty()
        }
    }

    /** Claude review of the phase: the picker's answer after a lock opened "the backup's password" over the unlock form. */
    @Test
    fun `what the picker hands back after a lock is dropped`() {
        val viewModel = opened()
        viewModel.import(source("a.txt", "one"))
        viewModel.pickFor(VaultPick.RESTORE)
        vault.service.lock()
        viewModel.unlock(PASSWORD)
        viewModel.picked(listOf("/storage/emulated/0/Download/coffre.rftvault"), folder = null)
        assertThat(viewModel.state.value.dialog).isNull()

        // Nothing done, so nothing said: an export tried on the locked vault would report its failure.
        val said = shown.size
        viewModel.pickFor(VaultPick.EXPORT, viewModel.item("a.txt"))
        vault.service.lock()
        viewModel.picked(paths = null, folder = File(dir, "out").apply { mkdirs() }.path)
        assertThat(shown).hasSize(said)
    }

    @Test
    fun `a backup chosen under another extension is asked about first`() {
        val viewModel = opened()
        viewModel.pickFor(VaultPick.RESTORE)
        viewModel.picked(listOf("/storage/emulated/0/Download/coffre.zip"), folder = null)
        assertThat(viewModel.state.value.dialog).isEqualTo(VaultDialog.UnexpectedExtension("/storage/emulated/0/Download/coffre.zip"))
    }

    private companion object {
        const val PASSWORD = "essai2026"
        const val BACKUP_PASSWORD = "sauve2026"
    }
}
