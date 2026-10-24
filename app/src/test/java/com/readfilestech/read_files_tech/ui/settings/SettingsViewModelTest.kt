package com.readfilestech.read_files_tech.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.panic.PanicService
import com.readfilestech.read_files_tech.data.trash.TrashStore
import com.readfilestech.read_files_tech.testing.HeldDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessage
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.vault.TestVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @TempDir
    lateinit var root: File

    /** The main thread and the application's scope, both held: the test decides when their work runs. */
    private val held = HeldDispatcher()
    private val messages = UserMessages()
    private val shown = mutableListOf<UserMessage>()
    private val store = ViewModelStore()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(held)
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        held.runAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `what was not erased is said, in words`() {
        val all = PanicService.Report(vault = true, preferences = true, cache = true, history = true, extractions = true, trash = true)
        assertThat(notErased(all)).isNull()
        assertThat(notErased(all.copy(vault = false, trash = false)))
            .isEqualTo(UiText.Joined(uiText(R.string.panic_part_vault), uiText(R.string.panic_part_trash), R.string.joined_comma))
    }

    /** Leaving the screen is the very reflex of a user in danger: the erasure must not stop half-way. */
    @Test
    fun `the erasure goes on when the screen is left, and says how it ended`() {
        val vault = TestVault(root)
        runBlocking { vault.service.setup("essai2026") }
        val appFlutter = File(root, "app_flutter")
        val trash = TrashStore(
            SharedStorageNames(replacesFlutterApp = false),
            fallbackBase = appFlutter,
            privateRoots = listOf(root),
            visibility = { true },
            volumeBases = { emptyList() },
            primary = root,
        )
        val history = EditorHistory(File(appFlutter, "history"), File(root, "files/editor_history"))
        val panic = PanicService(vault.service, vault.dirs, vault.prefs, history, trash, Dispatchers.Unconfined)
        val factory = viewModelFactory {
            initializer { SettingsViewModel(panic, messages, CoroutineScope(SupervisorJob() + held)) }
        }
        val viewModel = ViewModelProvider.create(store, factory)[SettingsViewModel::class]

        viewModel.askPanic()
        assertThat(viewModel.state.value.confirmPanic).isTrue()
        viewModel.panic()
        assertThat(viewModel.state.value).isEqualTo(SettingsState(confirmPanic = false, erasing = true))
        store.clear()
        held.runAll()

        assertThat(vault.service.isSetUp()).isFalse()
        assertThat(viewModel.state.value.erasing).isFalse()
        CoroutineScope(Dispatchers.Unconfined).launch { messages.showEach { shown += it } }.cancel()
        assertThat(shown).containsExactly(UserMessage(uiText(R.string.panic_done)))
    }
}
