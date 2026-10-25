package com.readfilestech.read_files_tech.ui.explorer

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Choosing several, ONE choice: the picker's recents, then the explorer it opens, which starts from them
 * (seen on the S9: the explorer's "Confirm" only went back, to a picker waiting for a second one).
 */
class PickedFilesTest {
    /** The picker, its recents a then b chosen. */
    private fun picker() = PickedFiles(SavedStateHandle()).apply {
        toggle("/r/a.pdf")
        toggle("/r/b.pdf")
    }

    @Test
    fun `the explorer starts from the picker's choice, counted from the start`() {
        val explorer = PickedFiles(SavedStateHandle())
        explorer.startFrom(picker().paths.value)
        assertThat(explorer.paths.value).containsExactly("/r/a.pdf", "/r/b.pdf").inOrder()
    }

    @Test
    fun `in the explorer, a file the picker chose is un-chosen, and a new one goes after the others`() {
        val explorer = PickedFiles(SavedStateHandle())
        explorer.startFrom(picker().paths.value)
        explorer.toggle("/d/c.pdf")
        explorer.toggle("/r/a.pdf")
        assertThat(explorer.paths.value).containsExactly("/r/b.pdf", "/d/c.pdf").inOrder()
        explorer.toggle("/r/a.pdf")
        assertThat(explorer.paths.value).containsExactly("/r/b.pdf", "/d/c.pdf", "/r/a.pdf").inOrder()
    }

    @Test
    fun `a file brought twice is chosen once`() {
        val explorer = PickedFiles(SavedStateHandle())
        explorer.startFrom(listOf("/r/a.pdf", "/r/b.pdf", "/r/a.pdf"))
        assertThat(explorer.paths.value).containsExactly("/r/a.pdf", "/r/b.pdf").inOrder()
    }

    /** What the system keeps of the view model's saved state over a process death, and gives back. */
    @Test
    fun `the choice survives a process death, in a type a Bundle keeps`() {
        val saved = SavedStateHandle()
        PickedFiles(saved).apply {
            startFrom(listOf("/r/a.pdf"))
            toggle("/d/c.pdf")
        }
        val kept = saved.keys().associateWith { saved.get<Any>(it) }
        assertThat(kept.values.single()).isInstanceOf(ArrayList::class.java)
        val restored = PickedFiles(SavedStateHandle(kept))
        assertThat(restored.paths.value).containsExactly("/r/a.pdf", "/d/c.pdf").inOrder()
    }
}
