package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * D7: the 2.15.3's "Largest" tab ticked into the duplicates' selection without the "not the whole group"
 * rule, and every copy of a group could be deleted for good. One rule here, whatever the tab.
 */
class DuplicateSelectionTest {
    @TempDir
    lateinit var tmp: File

    private fun dup(path: String, size: Long = 10, modified: Long = 1) = DuplicateFile(path, size, modified)

    private val a1 = dup("/a/1")
    private val a2 = dup("/a/2")
    private val a3 = dup("/a/3")
    private val b1 = dup("/b/1", size = 20)
    private val b2 = dup("/b/2", size = 20)
    private val groups = listOf(DuplicateGroup("a", listOf(a1, a2, a3)), DuplicateGroup("b", listOf(b1, b2)))
    private val selection = DuplicateSelection(groups)

    @Test
    fun `the last unticked copy of a group cannot be ticked, from any tab`() {
        assertThat(selection.canToggle("/a/1", emptySet())).isTrue()
        assertThat(selection.canToggle("/a/3", setOf("/a/1", "/a/2"))).isFalse()
        // The "Largest" tab ticks by path too: a copy listed there is the same copy.
        assertThat(selection.canToggle("/b/2", setOf("/b/1"))).isFalse()
        assertThat(selection.canToggle("/b/1", setOf("/b/1"))).isTrue()
    }

    @Test
    fun `a file of no group may always be ticked`() {
        assertThat(selection.canToggle("/large/video.mp4", setOf("/a/1", "/a/2", "/b/1"))).isTrue()
    }

    @Test
    fun `however every copy got ticked, the whole group is kept back`() {
        val plan = selection.trashable(setOf("/a/1", "/a/2", "/a/3", "/b/1", "/large/video.mp4")) { true }
        assertThat(plan.paths).containsExactly("/b/1", "/large/video.mp4")
        assertThat(plan.keptBack).isEqualTo(3)
    }

    @Test
    fun `a copy goes only beside a copy that stays and is unchanged, and only if itself is unchanged`() {
        // The copy that stays was deleted or rewritten since the scan: the ticked one is kept.
        assertThat(selection.trashable(setOf("/b/1")) { it.path != "/b/2" }).isEqualTo(TrashPlan(emptyList(), 1))
        // The ticked copy itself changed since: it is no longer a copy.
        assertThat(selection.trashable(setOf("/b/1")) { it.path != "/b/1" }).isEqualTo(TrashPlan(emptyList(), 1))
        // One of two staying copies changed: the other is enough.
        assertThat(selection.trashable(setOf("/a/1")) { it.path != "/a/2" }).isEqualTo(TrashPlan(listOf("/a/1"), 0))
    }

    /** Claude review R24: a group left with one copy was dissolved, and its last copy freed for the trash. */
    @Test
    fun `once copies went to the trash, the one left of a group stays its last`() {
        val after = selection.without(setOf("/b/1", "/a/1"))
        assertThat(after.canToggle("/b/2", emptySet())).isFalse()
        assertThat(after.trashable(setOf("/b/2")) { true }).isEqualTo(TrashPlan(emptyList(), keptBack = 1))
        assertThat(after.canToggle("/a/2", emptySet())).isTrue()
        assertThat(after.canToggle("/a/3", setOf("/a/2"))).isFalse()
    }

    @Test
    fun `unchanged means the same regular file, size and date`() {
        val file = File(tmp, "x.bin").apply { writeBytes(ByteArray(10)) }
        val listed = DuplicateFile(file.path, 10, file.lastModified())
        assertThat(DuplicateSelection.isUnchanged(listed)).isTrue()
        assertThat(DuplicateSelection.isUnchanged(listed.copy(size = 11))).isFalse()
        assertThat(DuplicateSelection.isUnchanged(listed.copy(modifiedMs = listed.modifiedMs - 5_000))).isFalse()
        file.delete()
        assertThat(DuplicateSelection.isUnchanged(listed)).isFalse()
    }
}
