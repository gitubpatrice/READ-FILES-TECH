package com.readfilestech.read_files_tech.ui.settings

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.extractions.LegacyExtractions
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.legacy.LegacyData
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessage
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** docs D-021, D-022: the notice of the old extractions, once; the settings' rows; what each ending says. */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyDataViewModelTest {
    @TempDir
    lateinit var tmp: File

    private val prefs = FlutterPreferences(InMemorySharedPreferences())
    private val messages = UserMessages()
    private lateinit var primary: File
    private lateinit var appFlutter: File
    private lateinit var legacy: LegacyData
    private lateinit var outputs: OutputStorage
    private var granted = true

    private fun viewModel() = LegacyDataViewModel(
        legacy,
        outputs,
        { granted },
        messages,
        CoroutineScope(Dispatchers.Unconfined),
        Dispatchers.Unconfined,
    )

    private fun shown(): List<UserMessage> {
        val shown = mutableListOf<UserMessage>()
        CoroutineScope(Dispatchers.Unconfined).launch { messages.showEach { shown += it } }.cancel()
        return shown
    }

    private fun extraction(name: String, vararg files: String) {
        val dir = File(appFlutter, "${name}_extracted").apply { mkdirs() }
        files.forEach { File(dir, it).writeText(it) }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        primary = File(tmp, "emulated0").apply { mkdirs() }
        appFlutter = File(tmp, "app_flutter").apply { mkdirs() }
        outputs = OutputStorage(prefs, { primary }, { null }, appFlutter)
        val extractions = LegacyExtractions(appFlutter, { outputs.folderFor(OutputCategory.EXTRACTIONS, noFallback = true) })
        val history = EditorHistory(File(appFlutter, "history"), File(tmp, "files/editor_history"))
        legacy = LegacyData(extractions, history, prefs)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the notice shows once in the app's life, with what is there and where it would go`() {
        extraction("photos", "a.jpg", "b.jpg")
        val first = viewModel()
        first.offerOnce()
        assertThat(first.state.value.dialog).isEqualTo(LegacyDialog.Extractions(first = true))
        assertThat(first.state.value.summary?.extractionFiles).isEqualTo(2)
        assertThat(first.state.value.destination).isEqualTo(File(primary, "Files Tech/Extractions").path)

        val next = viewModel()
        next.offerOnce()
        assertThat(next.state.value.dialog).isNull()
    }

    @Test
    fun `a move says how many files went where, and the section is left with nothing`() {
        extraction("photos", "a.jpg", "b.jpg")
        val vm = viewModel()
        vm.refresh()
        vm.askExtractions()
        assertThat(vm.state.value.dialog).isEqualTo(LegacyDialog.Extractions(first = false))
        vm.move()
        assertThat(vm.state.value.dialog).isNull()
        val root = File(primary, "Files Tech/Extractions")
        assertThat(shown()).containsExactly(
            UserMessage(uiText(R.string.legacy_moving)),
            UserMessage(UiText.Plural(R.plurals.legacy_extractions_moved, 2, listOf(2, root.path))),
        ).inOrder()
        assertThat(File(root, "photos/b.jpg").readText()).isEqualTo("b.jpg")
        assertThat(vm.state.value.summary?.isEmpty).isTrue()
    }

    /**
     * The outputs' folder refusing them, nothing is moved — not even into the app's own folders, which go with it
     * (reviews, phase 8) — and the message says where it could not write and what to do.
     */
    @Test
    fun `a move with no folder to take them moves nothing, and says where and what to do`() {
        extraction("photos", "a.jpg", "b.jpg")
        primary = File(tmp, "not a folder").apply { writeText("x") }
        val vm = viewModel()
        vm.refresh()
        vm.move()
        val destination = File(primary, "Files Tech/Extractions").path
        assertThat(shown().last()).isEqualTo(UserMessage(uiText(R.string.legacy_extractions_no_folder, destination), isError = true))
        assertThat(legacy.summary().extractionFiles).isEqualTo(2)
    }

    /** Without the access, the notice asks for it instead of moving: Android would let the app write only in its own folders. */
    @Test
    fun `the access to all files is read with what is left, again at each refresh`() {
        extraction("photos", "a.jpg")
        granted = false
        val vm = viewModel()
        vm.refresh()
        assertThat(vm.state.value.accessGranted).isFalse()
        granted = true
        vm.refresh()
        assertThat(vm.state.value.accessGranted).isTrue()
    }

    @Test
    fun `the old history is deleted only once confirmed, and said`() {
        File(appFlutter, "history").mkdirs()
        File(appFlutter, "history/notes.txt_1700000000000.bak").writeText("old")
        val vm = viewModel()
        vm.refresh()
        assertThat(vm.state.value.summary?.history).hasSize(1)
        vm.askDeleteHistory()
        assertThat(vm.state.value.dialog).isEqualTo(LegacyDialog.DeleteHistory)
        vm.dismiss()
        assertThat(legacy.summary().history).hasSize(1)

        vm.askDeleteHistory()
        vm.deleteHistory()
        assertThat(shown()).containsExactly(UserMessage(uiText(R.string.legacy_history_deleted)))
        assertThat(vm.state.value.summary?.isEmpty).isTrue()
    }
}
