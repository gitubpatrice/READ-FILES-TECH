package com.readfilestech.read_files_tech.ui.tools.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.storage.StorageVolumes
import com.readfilestech.read_files_tech.data.tools.DuplicateFinder
import com.readfilestech.read_files_tech.data.tools.DuplicateProgress
import com.readfilestech.read_files_tech.data.tools.DuplicateReport
import com.readfilestech.read_files_tech.data.tools.DuplicateSelection
import com.readfilestech.read_files_tech.ui.common.FileOperations
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject

/** The duplicates screen: the folder, the scan and how far, what it found, what is ticked. */
data class DuplicatesState(
    val root: String,
    val progress: DuplicateProgress? = null,
    val report: DuplicateReport? = null,
    val ticked: Set<String> = emptySet(),
    val failure: UiText? = null,
    /** The rule of what may be ticked, on the groups of [report] — those left with one copy included. */
    val selection: DuplicateSelection = DuplicateSelection(emptyList()),
    /**
     * A batch going to the trash: nothing is ticked nor sent meanwhile. Its copies were still listed,
     * unticked, counted as staying: their group's last copy could be ticked and sent after them (Claude
     * review R24).
     */
    val trashing: Boolean = false,
) {
    val scanning: Boolean get() = progress != null
}

/**
 * `DuplicatesScreen` of the 2.15.3: a folder scanned off the main thread, stopped when asked or when the
 * screen is left (the 2.15.3's isolate was killed too); files ticked in either tab — one selection, one
 * rule: never every copy of a group ([DuplicateSelection], D7) —, then moved to the app's TRASH by the
 * explorer's own operation, with "Undo". The 2.15.3 deleted them for good, after a confirmation, then
 * scanned the whole folder again; here the files gone leave the lists.
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    volumes: StorageVolumes,
    private val operations: FileOperations,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DuplicatesState(root = handle[KEY_ROOT] ?: volumes.primary().path))
    val state: StateFlow<DuplicatesState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** The folder chosen in the picker; a scan running goes on with the one it started on. */
    fun setRoot(folder: String) {
        handle[KEY_ROOT] = folder
        mutableState.update { it.copy(root = folder) }
    }

    fun scan() {
        if (job?.isCompleted == false) return
        val root = File(mutableState.value.root)
        mutableState.update {
            it.copy(
                progress = DuplicateProgress.Listing(0),
                report = null,
                ticked = emptySet(),
                failure = null,
                selection = DuplicateSelection(emptyList())
            )
        }
        job = viewModelScope.launch {
            try {
                val outcome = loadOrFailure {
                    withContext(io) { DuplicateFinder.find(root, onProgress = { p -> mutableState.update { it.copy(progress = p) } }) }
                }
                outcome.fold(
                    { report -> mutableState.update { it.copy(report = report, selection = DuplicateSelection(report.groups)) } },
                    { error -> mutableState.update { it.copy(failure = failureOf(error)) } },
                )
            } finally {
                mutableState.update { it.copy(progress = null) }
            }
        }
    }

    /** Stops the scan: nothing is shown of it. */
    fun stop() {
        job?.cancel()
    }

    fun toggle(path: String) = mutableState.update { state ->
        when {
            state.trashing -> state
            path in state.ticked -> state.copy(ticked = state.ticked - path)
            state.selection.canToggle(path, state.ticked) -> state.copy(ticked = state.ticked + path)
            else -> state
        }
    }

    /** The ticked files to the trash — those still as compared, beside a copy that stays and is too. */
    fun trash() {
        val current = mutableState.value
        if (current.ticked.isEmpty() || current.trashing) return
        mutableState.update { it.copy(ticked = emptySet(), trashing = true) }
        viewModelScope.launch {
            val plan = withContext(io) { current.selection.trashable(current.ticked) }
            if (plan.keptBack > 0) messages.error(UiText.Plural(R.plurals.duplicates_kept_back, plan.keptBack))
            if (plan.paths.isEmpty()) {
                mutableState.update { it.copy(trashing = false) }
            } else {
                operations.moveToTrash(plan.paths.map(::File)) { trashed -> dropped(trashed.toSet()) }
            }
        }
    }

    /**
     * The files gone to the trash leave the lists; a group left with one copy is no longer listed, but its
     * copy stays its last: never ticked, in either tab (Claude review R24 — dissolved, it could follow).
     */
    private fun dropped(paths: Set<String>) = mutableState.update { state ->
        val report = state.report ?: return@update state.copy(trashing = false)
        val groups = report.groups.map { group ->
            group.copy(
                files = group.files.filterNot { it.path in paths }
            )
        }.filter { it.files.size >= 2 }
        state.copy(
            report = report.copy(groups = groups, largest = report.largest.filterNot { it.path in paths }),
            ticked = state.ticked - paths,
            selection = state.selection.without(paths),
            trashing = false,
        )
    }

    private fun failureOf(error: Throwable): UiText = when (error) {
        is FileNotFoundException -> uiText(R.string.duplicates_not_found)
        else -> uiText(R.string.duplicates_failed, IoErrors.reasonOf(error))
    }

    private companion object {
        const val KEY_ROOT = "duplicates_root"
    }
}
