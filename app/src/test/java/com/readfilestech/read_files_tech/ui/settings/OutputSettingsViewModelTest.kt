package com.readfilestech.read_files_tech.ui.settings

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.outputs.OutputSettings
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
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
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class OutputSettingsViewModelTest {
    @TempDir
    lateinit var tmp: File

    private val prefs = FlutterPreferences(InMemorySharedPreferences())
    private val messages = UserMessages()
    private var granted = true
    private lateinit var primary: File
    private lateinit var external: File
    private lateinit var private: File

    private fun viewModel(): OutputSettingsViewModel {
        val outputs = OutputStorage(prefs, { primary }, { external }, private, clock = { NOW })
        val settings = OutputSettings(prefs, outputs) { listOf(primary) }
        return OutputSettingsViewModel(settings, outputs, { granted }, messages, Dispatchers.Unconfined)
    }

    private fun changed(now: File, before: File) =
        UserMessage(uiText(R.string.settings_output_changed, now.path, before.path))

    private fun shown(): List<UserMessage> {
        val shown = mutableListOf<UserMessage>()
        CoroutineScope(Dispatchers.Unconfined).launch { messages.showEach { shown += it } }.cancel()
        return shown
    }

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 24, 18, 0)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        primary = File(tmp, "emulated0").apply { mkdirs() }
        external = File(tmp, "android-data-files").apply { mkdirs() }
        private = File(tmp, "app_flutter").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the default folder is read, its subfolders made, and nothing is said`() {
        val model = viewModel()
        assertThat(model.state.value).isNull()
        model.refresh()
        val default = File(primary, "Files Tech")
        assertThat(model.state.value).isEqualTo(
            OutputFolderState(
                configured = default,
                isDefault = true,
                defaultFolder = default,
                effective = default,
                reachable = true,
                accessGranted = true,
                autoShare = false,
            ),
        )
        assertThat(File(default, "Scans").isDirectory).isTrue()
        assertThat(shown()).isEmpty()
    }

    /** Nothing is moved: the user is told where the files made before are, or would think them lost. */
    @Test
    fun `a new folder says where the files go now, and where those made before stay`() {
        val model = viewModel()
        model.refresh()
        val chosen = File(primary, "Documents").apply { mkdirs() }.canonicalFile
        model.choose(chosen.path)
        assertThat(model.state.value?.configured).isEqualTo(chosen)
        assertThat(model.state.value?.isDefault).isFalse()
        assertThat(shown()).containsExactly(changed(chosen, File(primary, "Files Tech")))

        model.reset()
        assertThat(model.state.value?.isDefault).isTrue()
        assertThat(shown()).containsExactly(changed(File(primary, "Files Tech"), chosen))
    }

    @Test
    fun `a refused folder is said and changes nothing`() {
        val model = viewModel()
        model.refresh()
        val before = model.state.value
        model.choose(File(primary, "Android/data/com.example").apply { mkdirs() }.path)
        assertThat(model.state.value).isEqualTo(before)
        assertThat(shown()).containsExactly(UserMessage(uiText(R.string.settings_output_app_folder), isError = true))
    }

    /** Where the files went when the folder could not take them: the app's own storage cannot be opened in the explorer. */
    @Test
    fun `a fallback is shown, and the private one as out of the explorer's reach`() {
        granted = false
        primary = File(tmp, "nothing").apply { writeText("x") }
        val model = viewModel()
        model.refresh()
        assertThat(model.state.value?.effective).isEqualTo(File(external, "Files Tech"))
        assertThat(model.state.value?.reachable).isTrue()
        assertThat(model.state.value?.accessGranted).isFalse()

        external = File(tmp, "nothing either").apply { writeText("x") }
        val stuck = viewModel()
        stuck.refresh()
        assertThat(stuck.state.value?.effective).isEqualTo(File(private, "Files Tech"))
        assertThat(stuck.state.value?.reachable).isFalse()
    }

    /**
     * Access granted in Android's settings meanwhile: the folder takes the files again, which the screen shows —
     * but the user changed nothing, and is told nothing, neither on the return nor on a switch touched next.
     */
    @Test
    fun `a folder that takes the files again is shown without a word`() {
        val blocked = File(primary, "Files Tech").apply { writeText("x") }
        val model = viewModel()
        model.refresh()
        assertThat(model.state.value?.effective).isEqualTo(File(external, "Files Tech"))
        blocked.delete()
        model.refresh()
        assertThat(model.state.value?.effective).isEqualTo(blocked)

        blocked.deleteRecursively()
        blocked.writeText("x")
        model.refresh()
        blocked.delete()
        model.setAutoShare(true)
        assertThat(model.state.value?.effective).isEqualTo(blocked)
        assertThat(shown()).isEmpty()
    }

    @Test
    fun `the automatic share is turned on and off without a word`() {
        val model = viewModel()
        model.refresh()
        model.setAutoShare(true)
        assertThat(model.state.value?.autoShare).isTrue()
        model.setAutoShare(false)
        assertThat(model.state.value?.autoShare).isFalse()
        assertThat(shown()).isEmpty()
    }
}
