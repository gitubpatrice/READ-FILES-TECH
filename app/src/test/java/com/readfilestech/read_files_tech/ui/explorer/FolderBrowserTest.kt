package com.readfilestech.read_files_tech.ui.explorer

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead
import com.readfilestech.read_files_tech.data.storage.StorageAccess
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Going from folder to folder, the explorer's own history, the delayed name filter, the first request. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderBrowserTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var root: File
    private val dispatcher = StandardTestDispatcher()
    private val state = MutableStateFlow(ExplorerUiState())
    private var granted = true

    @BeforeEach
    fun setUp() {
        root = File(tmp, "root").apply { mkdirs() }
        File(root, "Documents").mkdirs()
        File(root, "Documents/report.pdf").writeText("pdf")
        File(root, "notes.txt").writeText("n")
        File(root, "Notebook.md").writeText("m")
    }

    private fun TestScope.browser() = FolderBrowser(backgroundScope, state, { granted }, { emptyList() }, dispatcher)

    @Test
    fun `back goes up the explorer's own history, then says there is nowhere left`() = runTest(dispatcher) {
        val browser = browser()
        browser.open(root, remember = false)
        runCurrent()
        assertThat(state.value.canGoBack).isFalse()
        browser.open(File(root, "Documents"))
        runCurrent()
        assertThat(state.value.dir).isEqualTo(File(root, "Documents"))
        assertThat(state.value.canGoBack).isTrue()
        assertThat(state.value.visible.map { it.name }).containsExactly("report.pdf")
        assertThat(browser.back()).isTrue()
        runCurrent()
        assertThat(state.value.dir).isEqualTo(root)
        assertThat(state.value.canGoBack).isFalse()
        assertThat(browser.back()).isFalse()
    }

    @Test
    fun `reopening the same folder does not stack it in the history`() = runTest(dispatcher) {
        val browser = browser()
        browser.open(root, remember = false)
        runCurrent()
        browser.open(root)
        runCurrent()
        assertThat(state.value.canGoBack).isFalse()
    }

    @Test
    fun `the name filter applies 150 ms after the last key, not at each key`() = runTest(dispatcher) {
        val browser = browser()
        browser.open(root, remember = false)
        runCurrent()
        browser.setQuery("no")
        advanceTimeBy(100)
        assertThat(state.value.visible).hasSize(3)
        browser.setQuery("not")
        advanceTimeBy(100)
        assertThat(state.value.visible).hasSize(3)
        advanceTimeBy(60)
        assertThat(state.value.visible.map { it.name }).containsExactly("Notebook.md", "notes.txt").inOrder()
    }

    /** Seen on the emulator: a filter typed in one folder followed into the next, which said "Empty folder". */
    @Test
    fun `the name filter belongs to its folder - cleared in another, kept on refresh`() = runTest(dispatcher) {
        val browser = browser()
        browser.open(root, remember = false)
        runCurrent()
        browser.setQuery("not")
        advanceTimeBy(200)
        assertThat(state.value.visible).hasSize(2)
        browser.refresh()
        runCurrent()
        assertThat(state.value.query).isEqualTo("not")
        assertThat(state.value.visible).hasSize(2)
        browser.open(File(root, "Documents"))
        runCurrent()
        assertThat(state.value.query).isEmpty()
        assertThat(state.value.visible.map { it.name }).containsExactly("report.pdf")
        advanceTimeBy(200)
        assertThat(state.value.visible.map { it.name }).containsExactly("report.pdf")
    }

    @Test
    fun `an access granted in the settings refreshes the folder on return`() = runTest(dispatcher) {
        granted = false
        state.value = ExplorerUiState(dir = root, accessBanner = true)
        val browser = browser()
        granted = true
        browser.onResume()
        runCurrent()
        assertThat(state.value.visible.map { it.name }).contains("notes.txt")
        assertThat(state.value.accessBanner).isFalse()
    }

    @Test
    fun `the first request is claimed once, written before, and never on a value it cannot read`() {
        val raw = InMemorySharedPreferences()
        val prefs = FlutterPreferences(raw)
        assertThat(StorageAccess.claimFirstRequest(prefs) { false }).isTrue()
        assertThat(prefs.boolean(StorageAccess.PERMISSIONS_ASKED)).isEqualTo(PrefRead.Present(true))
        assertThat(StorageAccess.claimFirstRequest(prefs) { false }).isFalse()

        // Already granted: written, nothing to ask.
        val granted = FlutterPreferences(InMemorySharedPreferences())
        assertThat(StorageAccess.claimFirstRequest(granted) { true }).isFalse()
        assertThat(granted.boolean(StorageAccess.PERMISSIONS_ASKED)).isEqualTo(PrefRead.Present(true))

        // Dart `getBool(...) == true`: a stored false still asks.
        val no = InMemorySharedPreferences().apply { plant("flutter.permissions_asked", false) }
        assertThat(StorageAccess.claimFirstRequest(FlutterPreferences(no)) { false }).isTrue()

        // Unreadable (a string where a bool is expected): neither trusted nor rewritten (docs D-019).
        val odd = InMemorySharedPreferences().apply { plant("flutter.permissions_asked", "yes") }
        assertThat(StorageAccess.claimFirstRequest(FlutterPreferences(odd)) { false }).isFalse()
        assertThat(odd.all["flutter.permissions_asked"]).isEqualTo("yes")
    }
}
