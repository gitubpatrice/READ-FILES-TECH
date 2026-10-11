package com.readfilestech.read_files_tech.ui.common

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileActions
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.trash.TrashStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The explorer's changes as the user sees them: what is said, what "Undo" puts back. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationsTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var work: File
    private lateinit var trash: TrashStore
    private val dispatcher = StandardTestDispatcher()
    private val messages = UserMessages()

    @BeforeEach
    fun setUp() {
        work = File(tmp, "work").apply { mkdirs() }
        val fallback = File(tmp, "app_flutter").apply { mkdirs() }
        trash = TrashStore(
            names = SharedStorageNames(replacesFlutterApp = true),
            fallbackBase = fallback,
            privateRoots = listOf(File(tmp, "private")),
            visibility = { true },
            volumeBases = { emptyList() },
            primary = File("/storage/emulated/0"),
        )
    }

    /** Runs [block] on fresh operations and returns every message they posted. */
    private fun TestScope.posted(block: (FileOperations) -> Unit): List<UserMessage> {
        val seen = mutableListOf<UserMessage>()
        // A queue serves one reader: this one stops before the next call starts its own.
        val reader = backgroundScope.launch { messages.messages.collect { seen += it } }
        runCurrent()
        block(FileOperations(FileActions(), trash, messages, backgroundScope, dispatcher))
        runCurrent()
        reader.cancel()
        return seen
    }

    /** Claude review R5: a message posted while nothing listens (the app in the background) waits. */
    @Test
    fun `a message posted with no one listening waits for the next reader`() = runTest(dispatcher) {
        val operations = FileOperations(FileActions(), trash, messages, backgroundScope, dispatcher)
        operations.createFolder(work, "a/b")
        runCurrent()
        assertThat(posted { }).hasSize(1)
    }

    @Test
    fun `a file put in the trash is said by name, with an Undo that puts it back`() = runTest(dispatcher) {
        val note = File(work, "note.txt").apply { writeText("hello") }
        val said = posted { it.moveToTrash(listOf(note)) }.single()
        assertThat(note.exists()).isFalse()
        assertThat(said.isError).isFalse()
        assertThat(said.text).isEqualTo(UiText.Res(R.string.trashed_one, listOf("note.txt")))
        val undo = requireNotNull(said.action)
        assertThat(undo.label).isEqualTo(UiText.Res(R.string.action_undo))
        val restored = posted { undo.run() }
        assertThat(note.readText()).isEqualTo("hello")
        assertThat(restored).isEmpty() // a successful Undo says nothing more
    }

    @Test
    fun `nothing moved to the trash, no Undo, and the reason is given`() = runTest(dispatcher) {
        val said = posted { it.moveToTrash(listOf(File(work, "absent.txt"))) }.single()
        assertThat(said.isError).isTrue()
        assertThat(said.action).isNull()
        assertThat((said.text as UiText.Res).id).isEqualTo(R.string.error_trash)
    }

    @Test
    fun `a batch counts what went, what failed, and offers Undo for what went`() = runTest(dispatcher) {
        val a = File(work, "a.txt").apply { writeText("a") }
        val b = File(work, "b.txt").apply { writeText("b") }
        val said = posted { it.moveToTrash(listOf(a, b, File(work, "absent"))) }.single()
        assertThat(said.text).isEqualTo(
            UiText.Joined(UiText.Plural(R.plurals.trashed_many, 2), UiText.Plural(R.plurals.errors_count, 1), R.string.joined_dot),
        )
        posted { requireNotNull(said.action).run() }
        assertThat(a.readText()).isEqualTo("a")
        assertThat(b.readText()).isEqualTo("b")
    }

    @Test
    fun `a failed rename gives its reason, a successful one is silent`() = runTest(dispatcher) {
        val a = File(work, "a.txt").apply { writeText("a") }
        File(work, "b.txt").writeText("b")
        val failed = posted { it.rename(a, "b.txt") }.single()
        assertThat(failed.isError).isTrue()
        assertThat(failed.text).isEqualTo(UiText.Res(R.string.error_rename, listOf(UiText.Res(R.string.error_already_exists))))
        assertThat(posted { it.rename(a, "c.txt") }).isEmpty()
        assertThat(File(work, "c.txt").readText()).isEqualTo("a")
    }

    @Test
    fun `every operation tells the screens to refresh, even a failed one`() = runTest(dispatcher) {
        var refreshes = 0
        val operations = FileOperations(FileActions(), trash, messages, backgroundScope, dispatcher)
        backgroundScope.launch { operations.changes.collect { refreshes++ } }
        runCurrent()
        operations.createFolder(work, "new")
        runCurrent()
        operations.createFolder(work, "new")
        runCurrent()
        assertThat(refreshes).isEqualTo(2)
    }
}
