package com.readfilestech.read_files_tech.ui.tools.csveditor

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import com.readfilestech.read_files_tech.ui.common.UserMessages
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Claude reviews R20, R21: loads that overlap, a file opened while another is written, restores in the way. */
@OptIn(ExperimentalCoroutinesApi::class)
class CsvEditorViewModelTest {
    @TempDir
    lateinit var dir: File

    /** The IO dispatcher: its work waits, and the test runs it in the order it chooses. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks += block
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private val io = HeldDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        CsvEditorViewModel(SavedStateHandle(), EditorHistory(File(dir, "legacy"), File(dir, "history")), UserMessages(), io)

    private fun file(name: String, text: String) = File(dir, name).apply { writeText(text) }

    /** A was shown under B's name, B's stamp beside it: a save wrote A's rows over B. */
    @Test
    fun `a file opened while another loads is the one shown, whatever finishes last`() {
        val a = file("a.csv", "a\n1\n")
        val b = file("b.csv", "b\n2\n")
        val viewModel = viewModel()
        viewModel.open(a.path)
        viewModel.open(b.path)
        io.tasks.removeLast().run()
        io.tasks.removeFirst().run()
        val state = viewModel.state.value
        assertThat(state.name).isEqualTo("b.csv")
        assertThat(state.edits!!.cells(0, 0, 1, 10)).containsExactly("b")
    }

    @Test
    fun `no file is opened while one is being written`() {
        val a = file("a.csv", "a\n1\n")
        val b = file("b.csv", "b\n2\n")
        val viewModel = viewModel()
        viewModel.open(a.path)
        io.runAll()
        viewModel.change { it.set(1, 0, "9") }
        viewModel.save()
        viewModel.open(b.path)
        assertThat(viewModel.state.value.name).isEqualTo("a.csv")
        io.runAll()
        assertThat(a.readText()).isEqualTo("a\n9\n")
        assertThat(viewModel.state.value.edits!!.cells(1, 0, 1, 10)).containsExactly("9")
    }

    /** [file] opened, its cell changed to "2" and saved: the one copy of its history, the file before ("1"). */
    private fun savedOnce(viewModel: CsvEditorViewModel, file: File): HistoryCopy {
        viewModel.open(file.path)
        io.runAll()
        viewModel.change { it.set(1, 0, "2") }
        viewModel.save()
        io.runAll()
        viewModel.showHistory()
        io.runAll()
        return viewModel.state.value.versions!!.single()
    }

    /** Claude review R21: a restore read during a save landed under it — announced, then gone at the re-read. */
    @Test
    fun `a restore still being read when a save starts does not land`() {
        val a = file("a.csv", "a\n1\n")
        val viewModel = viewModel()
        val copy = savedOnce(viewModel, a)
        viewModel.restore(copy)
        viewModel.change { it.set(1, 0, "3") }
        viewModel.save()
        io.tasks.removeFirst().run() // the restore's read
        assertThat(viewModel.state.value.edits!!.cells(1, 0, 1, 10)).containsExactly("3")
        io.runAll()
        assertThat(a.readText()).isEqualTo("a\n3\n")
        assertThat(viewModel.state.value.edits!!.cells(1, 0, 1, 10)).containsExactly("3")
    }

    /** Claude review R21: a restore during an open dropped the open's result — loading for ever, no stamp to save. */
    @Test
    fun `no version is restored while a file opens`() {
        val a = file("a.csv", "a\n1\n")
        val b = file("b.csv", "b\n2\n")
        val viewModel = viewModel()
        val copy = savedOnce(viewModel, a)
        viewModel.open(b.path)
        viewModel.restore(copy)
        io.runAll()
        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.edits!!.cells(0, 0, 1, 10)).containsExactly("b")
    }
}
