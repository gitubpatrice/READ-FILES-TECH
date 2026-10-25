package com.readfilestech.read_files_tech.ui.common

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.BulkRename
import com.readfilestech.read_files_tech.data.browse.FileActions
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.RenameRule
import com.readfilestech.read_files_tech.data.files.MediaIndex
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
    private val indexed = mutableListOf<String>()
    private val mediaIndex = MediaIndex { indexed += it }

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
        val reader = backgroundScope.launch { messages.showEach { seen += it } }
        runCurrent()
        block(FileOperations(FileActions(), trash, messages, backgroundScope, dispatcher, mediaIndex))
        runCurrent()
        reader.cancel()
        return seen
    }

    /** Claude review R5: a message posted while nothing listens (the app in the background) waits. */
    @Test
    fun `a message posted with no one listening waits for the next reader`() = runTest(dispatcher) {
        val operations = FileOperations(FileActions(), trash, messages, backgroundScope, dispatcher, mediaIndex)
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

    /** The duplicates screen drops from its lists what did go, not what was asked. */
    @Test
    fun `the trash hands back the paths that went`() = runTest(dispatcher) {
        val a = File(work, "a.txt").apply { writeText("a") }
        var went: List<String>? = null
        posted { it.moveToTrash(listOf(a, File(work, "absent"))) { paths -> went = paths } }
        assertThat(went).containsExactly(a.absolutePath)
    }

    @Test
    fun `a bulk rename says how many were renamed, and names the first one that was not`() = runTest(dispatcher) {
        fun entry(name: String) = FileEntry(File(work, name).path, name, isDirectory = false, size = 1, modifiedMs = 0, isSymlink = false)
        File(work, "a.txt").writeText("a")
        File(work, "b.txt").writeText("b")
        val proposals = BulkRename.preview(listOf(entry("a.txt"), entry("b.txt")), RenameRule.Affixes("new-", ""), true, {
            emptySet()
        })
        File(work, "b.txt").delete()
        val said = posted { it.renameAll(proposals) }.single()
        assertThat(said.isError).isTrue()
        assertThat(said.text).isEqualTo(
            UiText.Joined(
                UiText.Plural(R.plurals.bulk_rename_done, 1),
                UiText.Plural(R.plurals.bulk_rename_not_renamed, 1, listOf(1, "b.txt", UiText.Res(R.string.error_not_found))),
                R.string.joined_dot,
            ),
        )
        assertThat(work.list()!!.toList()).containsExactly("new-a.txt")
        assertThat(
            posted {
                it.renameAll(
                    BulkRename.preview(listOf(entry("new-a.txt")), RenameRule.Affixes("", "-2"), true, {
                        emptySet()
                    })
                )
            }.single()
        )
            .isEqualTo(UserMessage(UiText.Plural(R.plurals.bulk_rename_done, 1)))
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

    /**
     * Up to Android 10 the media index kept a trashed or deleted photo — path, size, thumbnail, readable by any app —
     * and missed a copy or a file put back (motif audit, phase 9): each change has it read both ends again.
     */
    @Test
    fun `the media index reads again what the trash took away, and what Undo put back`() = runTest(dispatcher) {
        val photo = File(work, "photo.jpg").apply { writeText("jpg") }
        val album = File(work, "album").apply { mkdirs() }
        val inAlbum = File(album, "b.jpg").apply { writeText("b") }
        val said = posted { it.moveToTrash(listOf(photo, album)) }.single()
        assertThat(indexed).containsExactly(photo.path, inAlbum.path)
        indexed.clear()
        posted { requireNotNull(said.action).run() }
        assertThat(indexed).containsExactly(photo.path, inAlbum.path)
    }

    @Test
    fun `the media index reads again a deleted file, both ends of a move, a copy, a renamed file`() = runTest(dispatcher) {
        val gone = File(work, "gone.jpg").apply { writeText("g") }
        posted { it.deleteForever(listOf(gone)) }
        assertThat(indexed).containsExactly(gone.path)
        indexed.clear()

        val dest = File(work, "dest").apply { mkdirs() }
        val moving = File(work, "moving.jpg").apply { writeText("m") }
        posted { it.transfer(listOf(moving), dest, move = true) }
        assertThat(indexed).containsExactly(moving.path, File(dest, "moving.jpg").path)
        indexed.clear()

        val copied = File(work, "copied.jpg").apply { writeText("c") }
        posted { it.transfer(listOf(copied), dest, move = false) }
        assertThat(indexed).containsExactly(File(dest, "copied.jpg").path)
        indexed.clear()

        posted { it.rename(copied, "renamed.jpg") }
        assertThat(indexed).containsExactly(copied.path, File(work, "renamed.jpg").path)
    }

    @Test
    fun `a bulk rename has the media index read the old names and the new`() = runTest(dispatcher) {
        val a = File(work, "a.jpg").apply { writeText("a") }
        val entry = FileEntry(a.path, a.name, isDirectory = false, size = 1, modifiedMs = 0, isSymlink = false)
        posted { it.renameAll(BulkRename.preview(listOf(entry), RenameRule.Affixes("new-", ""), true, { emptySet() })) }
        assertThat(indexed).containsExactly(a.path, File(work, "new-a.jpg").path)
    }

    /** From Android 11 the file system keeps the index: no tree is walked for it. */
    @Test
    fun `when Android keeps the media index itself, nothing is listed for it`() = runTest(dispatcher) {
        val photo = File(work, "photo.jpg").apply { writeText("jpg") }
        val asked = mutableListOf<String>()
        val selfKept = object : MediaIndex {
            override val needsPaths = false

            override fun refresh(paths: List<String>) {
                asked += paths
            }
        }
        FileOperations(FileActions(), trash, messages, backgroundScope, dispatcher, selfKept).moveToTrash(listOf(photo))
        runCurrent()
        assertThat(photo.exists()).isFalse()
        assertThat(asked).isEmpty()
    }

    @Test
    fun `every operation tells the screens to refresh, even a failed one`() = runTest(dispatcher) {
        var refreshes = 0
        val operations = FileOperations(FileActions(), trash, messages, backgroundScope, dispatcher, mediaIndex)
        backgroundScope.launch { operations.changes.collect { refreshes++ } }
        runCurrent()
        operations.createFolder(work, "new")
        runCurrent()
        operations.createFolder(work, "new")
        runCurrent()
        assertThat(refreshes).isEqualTo(2)
    }
}
