package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DuplicateListsTest {
    private fun file(path: String, size: Long = 10) = DuplicateFile(path, size, modifiedMs = 1)

    private val a1 = file("/a1")
    private val a2 = file("/a2")
    private val a3 = file("/a3")
    private val b1 = file("/b1", 20)
    private val b2 = file("/b2", 20)
    private val big = file("/big", 1_000)
    private val report = DuplicateReport(
        groups = listOf(DuplicateGroup("a", listOf(a1, a2, a3)), DuplicateGroup("b", listOf(b1, b2))),
        largest = listOf(big, b1),
        filesListed = 6,
        unreadableFolders = 0,
        unreadableFiles = 0,
        truncated = false,
    )

    @Test
    fun `files gone to the trash leave the lists, a group left with one copy goes`() {
        val shown = DuplicateLists.without(report, setOf("/a1", "/b1"))
        assertThat(shown.groups).containsExactly(DuplicateGroup("a", listOf(a2, a3)))
        assertThat(shown.largest).containsExactly(big)
        assertThat(DuplicateLists.without(report, emptySet())).isSameInstanceAs(report)
    }

    /** The trash's "Undo": back as they were, they are listed again — the 2.15.x needed a new scan. */
    @Test
    fun `files back where they were, as they were, return - one changed meanwhile does not`() {
        val gone = setOf("/a1", "/b1", "/big")
        val back = DuplicateLists.returned(report, gone) { it != big }
        assertThat(back).containsExactly("/a1", "/b1")
        val shown = DuplicateLists.without(report, gone - back)
        assertThat(shown.groups).isEqualTo(report.groups)
        assertThat(shown.largest).containsExactly(b1)
        // A path the scan never listed is never brought in.
        assertThat(DuplicateLists.returned(report, setOf("/elsewhere")) { true }).isEmpty()
    }
}
