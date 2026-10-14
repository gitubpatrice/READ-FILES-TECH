package com.readfilestech.read_files_tech.ui.tools.editor

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.ui.common.UserMessages
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * The leave dialog of the code editor on the REAL screen's rules — `editor_leave_dialog_test.dart` of
 * the 2.15.3 tested a replica of the dialog —, and what it did not test: its Save left the screen even
 * when the save failed (D3, `code_editor_screen.dart:280-285`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeEditorSessionTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var file: File

    @BeforeEach
    fun setUp() {
        file = File(tmp, "notes.txt").apply { writeText(ORIGINAL) }
    }

    private fun history(newDir: File = File(tmp, "editor_history")) = EditorHistory(File(tmp, "app_flutter/history"), newDir)

    /** The file open, the text changed to [EDITED], the user pressing back: the leave dialog is shown. */
    private suspend fun TestScope.leaving(history: EditorHistory = history()): CodeEditorSession {
        val session = CodeEditorSession(history, UserMessages(), UnconfinedTestDispatcher(testScheduler))
        assertThat(session.open(file)).isEqualTo(ORIGINAL)
        session.textChanged(EDITED)
        session.askToLeave()
        assertThat(session.state.value.leaving).isTrue()
        return session
    }

    @Test
    fun `Discard leaves the screen, the file as it was`() = runTest {
        val session = leaving()
        session.leave(LeaveChoice.DISCARD, EDITED)
        assertThat(session.state.value.left).isTrue()
        assertThat(file.readText()).isEqualTo(ORIGINAL)
    }

    @Test
    fun `Cancel keeps the user on the screen, the dialog closed`() = runTest {
        val session = leaving()
        session.leave(LeaveChoice.CANCEL, EDITED)
        assertThat(session.state.value.left).isFalse()
        assertThat(session.state.value.leaving).isFalse()
        assertThat(session.state.value.modified).isTrue()
        assertThat(file.readText()).isEqualTo(ORIGINAL)
    }

    @Test
    fun `Save leaves the screen once the file is written`() = runTest {
        val session = leaving()
        session.leave(LeaveChoice.SAVE, EDITED)
        assertThat(file.readText()).isEqualTo(EDITED)
        assertThat(session.state.value.left).isTrue()
        assertThat(session.state.value.modified).isFalse()
    }

    @Test
    fun `Discard and Cancel do not do the same thing`() = runTest {
        val discarded = leaving().apply { leave(LeaveChoice.DISCARD, EDITED) }.state.value
        val cancelled = leaving().apply { leave(LeaveChoice.CANCEL, EDITED) }.state.value
        assertThat(discarded.left).isNotEqualTo(cancelled.left)
    }

    @Test
    fun `Save that fails keeps the screen and the dialog, its reason said - D3`() = runTest {
        // The history copy is made first, and fails: nothing is written.
        val session = leaving(history(File(tmp, "not-a-folder").apply { writeText("x") }))
        session.leave(LeaveChoice.SAVE, EDITED)
        val state = session.state.value
        assertThat(state.left).isFalse()
        assertThat(state.leaving).isTrue()
        assertThat(state.saveFailure).isNotNull()
        assertThat(state.modified).isTrue()
        assertThat(file.readText()).isEqualTo(ORIGINAL)
    }

    @Test
    fun `Save of a file changed on disk asks first, and Overwrite then writes and leaves`() = runTest {
        val history = history()
        val session = leaving(history)
        file.writeText(THEIRS)
        session.leave(LeaveChoice.SAVE, EDITED)
        assertThat(session.state.value.changedOnDisk).isTrue()
        assertThat(session.state.value.left).isFalse()
        assertThat(file.readText()).isEqualTo(THEIRS)

        session.overwrite(EDITED)
        assertThat(file.readText()).isEqualTo(EDITED)
        assertThat(session.state.value.left).isTrue()
        assertThat(history.versions(file.absolutePath).single().file.readText()).isEqualTo(THEIRS)
    }

    @Test
    fun `Cancel when the file changed on disk writes nothing and brings the leave dialog back`() = runTest {
        val session = leaving()
        file.writeText(THEIRS)
        session.leave(LeaveChoice.SAVE, EDITED)
        session.keepOnDisk()
        assertThat(session.state.value.changedOnDisk).isFalse()
        assertThat(session.state.value.leaving).isTrue()
        assertThat(file.readText()).isEqualTo(THEIRS)
    }

    @Test
    fun `back with nothing unsaved leaves at once, no dialog`() = runTest {
        val session = CodeEditorSession(history(), UserMessages(), UnconfinedTestDispatcher(testScheduler))
        session.open(file)
        session.textChanged(ORIGINAL)
        session.askToLeave()
        assertThat(session.state.value.leaving).isFalse()
        assertThat(session.state.value.left).isTrue()
    }

    @Test
    fun `restoring a version puts the unsaved text in the history first`() = runTest {
        val history = history()
        val session = CodeEditorSession(history, UserMessages(), UnconfinedTestDispatcher(testScheduler))
        session.open(file)
        session.save(EDITED)
        val older = history.versions(file.absolutePath).single()
        session.textChanged(UNSAVED)
        assertThat(session.restore(older, UNSAVED)).isEqualTo(ORIGINAL)
        assertThat(history.versions(file.absolutePath).map { it.file.readText() }).containsExactly(UNSAVED, ORIGINAL).inOrder()
        assertThat(file.readText()).isEqualTo(EDITED)
    }

    /** The IO dispatcher: runs at once, or, [held], keeps the work until [runAll]. */
    private class HeldDispatcher : CoroutineDispatcher() {
        var held = false
        private val tasks = ArrayDeque<Runnable>()

        override fun isDispatchNeeded(context: CoroutineContext) = held

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks += block
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    /** Claude review R22: what was typed during a restore's reads and writes was lost; a save then ended "saved" wrongly. */
    @Test
    fun `the screen is held while a version is restored`() = runTest {
        val history = history()
        val io = HeldDispatcher()
        val session = CodeEditorSession(history, UserMessages(), io)
        session.open(file)
        session.save(EDITED)
        val older = history.versions(file.absolutePath).single()
        session.textChanged(UNSAVED)
        io.held = true
        val restored = async { session.restore(older, UNSAVED) }
        runCurrent()
        assertThat(session.state.value.saving).isTrue()
        val saved = async { session.save("typed meanwhile\n") }
        repeat(3) {
            runCurrent()
            io.runAll()
        }
        saved.await()
        assertThat(restored.await()).isEqualTo(ORIGINAL)
        assertThat(session.state.value.saving).isFalse()
        assertThat(file.readText()).isEqualTo(EDITED)
    }

    @Test
    fun `a file that is not text does not open, and says why`() = runTest {
        file.writeBytes(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 0, 0))
        val session = CodeEditorSession(history(), UserMessages(), UnconfinedTestDispatcher(testScheduler))
        assertThat(session.open(file)).isNull()
        assertThat(session.state.value.failure).isNotNull()
        assertThat(session.state.value.isOpen).isFalse()
    }

    private companion object {
        const val ORIGINAL = "one\ntwo\n"
        const val EDITED = "one\nTWO\n"
        const val UNSAVED = "one\nnot saved\n"
        const val THEIRS = "another app's\n"
    }
}
