package com.readfilestech.read_files_tech.ui.tools.csveditor

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.MIB
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import com.readfilestech.read_files_tech.testing.HeldDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
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
import java.io.IOException

/** Claude reviews R20, R21: loads that overlap, a file opened while another is written, restores in the way. */
@OptIn(ExperimentalCoroutinesApi::class)
class CsvEditorViewModelTest {
    @TempDir
    lateinit var dir: File

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

    /** As in the code editor (review R22's question): the cells typed were gone once a version replaced them. */
    @Test
    fun `cells not saved go to the history before a version replaces them`() {
        val a = file("a.csv", "a\n1\n")
        val viewModel = viewModel()
        val copy = savedOnce(viewModel, a)
        viewModel.change { it.set(1, 0, "3") }
        viewModel.restore(copy)
        assertThat(viewModel.state.value.saving).isTrue()
        assertThat(viewModel.change { it.set(1, 0, "4") }).isNull()
        io.runAll()
        val state = viewModel.state.value
        assertThat(state.saving).isFalse()
        assertThat(state.edits!!.cells(1, 0, 1, 10)).containsExactly("1")
        viewModel.showHistory()
        io.runAll()
        assertThat(viewModel.state.value.versions!!.map { it.file.readText() }).containsExactly("a\n3\n", "a\n1\n").inOrder()
        assertThat(a.readText()).isEqualTo("a\n2\n")
    }

    @Test
    fun `when the cells not saved cannot be kept, nothing is restored`() {
        var full = false
        val history = EditorHistory(File(dir, "legacy"), File(dir, "history")) { copy, bytes ->
            if (full) throw IOException("No space left on device") else AtomicFiles.writeBytes(copy, bytes)
        }
        val viewModel = CsvEditorViewModel(SavedStateHandle(), history, UserMessages(), io)
        val a = file("a.csv", "a\n1\n")
        val copy = savedOnce(viewModel, a)
        viewModel.change { it.set(1, 0, "3") }
        full = true
        viewModel.restore(copy)
        io.runAll()
        val state = viewModel.state.value
        assertThat(state.saving).isFalse()
        assertThat(state.modified).isTrue()
        assertThat(state.edits!!.cells(1, 0, 1, 10)).containsExactly("3")
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

    /** A CSV over the history's cap: a header, then rows of 1 KiB. */
    private fun overCap(name: String): File = File(dir, name).apply {
        val row = "x".repeat(ROW - 1) + "\n"
        bufferedWriter().use { out ->
            out.write("a\n")
            // Over it still once a few rows are edited down to a word.
            repeat((FileCaps.EDITOR_HISTORY_COPY / ROW).toInt() + MARGIN_ROWS) { out.write(row) }
        }
        assertThat(length()).isGreaterThan(FileCaps.EDITOR_HISTORY_COPY)
    }

    private fun firstRow(file: File): String = file.useLines { it.drop(1).first() }

    private fun CsvEditorViewModel.openAndChange(file: File, cell: String) {
        open(file.path)
        io.runAll()
        change { it.set(1, 0, cell) }
    }

    /** It saved without keeping the version it replaced, without a word. */
    @Test
    fun `the first save of a file over the history's cap asks, and only the first`() {
        val big = overCap("big.csv")
        val viewModel = viewModel()
        viewModel.openAndChange(big, "one")
        viewModel.save()
        assertThat(viewModel.state.value.askOverCap).isEqualTo((FileCaps.EDITOR_HISTORY_COPY / MIB).toInt())
        assertThat(io.tasks).isEmpty()
        viewModel.answerOverCap(saveAnyway = true)
        io.runAll()
        assertThat(viewModel.state.value.askOverCap).isNull()
        assertThat(firstRow(big)).isEqualTo("one")
        viewModel.change { it.set(1, 0, "two") }
        viewModel.save()
        assertThat(viewModel.state.value.askOverCap).isNull()
        io.runAll()
        assertThat(firstRow(big)).isEqualTo("two")
        viewModel.showHistory()
        io.runAll()
        assertThat(viewModel.state.value.versions).isEmpty()
    }

    @Test
    fun `cancel writes nothing, and the next save asks again`() {
        val big = overCap("big.csv")
        val viewModel = viewModel()
        viewModel.openAndChange(big, "one")
        viewModel.save()
        viewModel.answerOverCap(saveAnyway = false)
        assertThat(viewModel.state.value.askOverCap).isNull()
        assertThat(io.tasks).isEmpty()
        assertThat(firstRow(big)).startsWith("xxx")
        assertThat(viewModel.state.value.modified).isTrue()
        viewModel.save()
        assertThat(viewModel.state.value.askOverCap).isNotNull()
    }

    @Test
    fun `the leave dialog's save asks too, and leaves once written`() {
        val big = overCap("big.csv")
        val viewModel = viewModel()
        viewModel.openAndChange(big, "one")
        viewModel.save(thenLeave = true)
        assertThat(viewModel.state.value.left).isFalse()
        viewModel.answerOverCap(saveAnyway = true)
        io.runAll()
        assertThat(firstRow(big)).isEqualTo("one")
        assertThat(viewModel.state.value.left).isTrue()
    }

    /** The answer holds for the rest of the file's session, over a process death; another file opened, it is asked again. */
    @Test
    fun `the answer is kept over a process death, and forgotten for another file`() {
        val big = overCap("big.csv")
        val small = file("small.csv", "a\n1\n")
        val handle = SavedStateHandle()
        CsvEditorViewModel(handle, EditorHistory(File(dir, "legacy"), File(dir, "history")), UserMessages(), io).apply {
            openAndChange(big, "one")
            save()
            answerOverCap(saveAnyway = true)
            io.runAll()
        }
        val kept = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        val restored = CsvEditorViewModel(kept, EditorHistory(File(dir, "legacy"), File(dir, "history")), UserMessages(), io)
        io.runAll()
        restored.change { it.set(1, 0, "two") }
        restored.save()
        assertThat(restored.state.value.askOverCap).isNull()
        io.runAll()
        assertThat(firstRow(big)).isEqualTo("two")
        restored.open(small.path)
        io.runAll()
        restored.openAndChange(big, "three")
        restored.save()
        assertThat(restored.state.value.askOverCap).isNotNull()
    }

    private companion object {
        const val ROW = 1024
        const val MARGIN_ROWS = 8
    }
}
