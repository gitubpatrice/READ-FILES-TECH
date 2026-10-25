package com.readfilestech.read_files_tech.ui.explorer

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.StateFlow

/**
 * The files chosen when choosing several ([PickMode.FILES]), in the order chosen: ONE choice, which travels.
 * The picker's recents start it; the explorer the picker opens to browse starts from it ([startFrom]) — its
 * files checked when shown, counted in "Confirm (n)" from the start — and keeps it from folder to folder; its
 * "Confirm" ends the choice with the whole of it, handed past the picker to whoever asked (`AppRoot`). Each
 * screen holds its own copy, in its view model's saved state: Back from the explorer drops the explorer's and
 * finds the picker's as it was before browsing (leaving is not choosing), and a process death while choosing
 * loses neither.
 */
class PickedFiles(private val savedState: SavedStateHandle) {
    val paths: StateFlow<List<String>> = savedState.getStateFlow(KEY, ArrayList())

    /** A file chosen, or un-chosen: a new one goes after the others. */
    fun toggle(path: String) {
        val now = paths.value
        set(if (path in now) now - path else now + path)
    }

    /** The choice this screen starts from (the picker's, in the explorer it opened), once each. */
    fun startFrom(chosen: List<String>) = set(chosen.distinct())

    private fun set(chosen: List<String>) {
        savedState[KEY] = ArrayList(chosen)
    }

    private companion object {
        const val KEY = "picked"
    }
}
