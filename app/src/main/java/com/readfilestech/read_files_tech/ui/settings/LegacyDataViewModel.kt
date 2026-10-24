package com.readfilestech.read_files_tech.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.StorageVisibility
import com.readfilestech.read_files_tech.data.legacy.LegacyData
import com.readfilestech.read_files_tech.data.outputs.NoOutputLocationException
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.ui.common.ApplicationScope
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** A question about the 2.x's data on screen. */
sealed interface LegacyDialog {
    /** Move the old extractions, delete them, or later; [first] when it is the notice of the first launch. */
    data class Extractions(val first: Boolean) : LegacyDialog

    data object DeleteExtractions : LegacyDialog

    data object DeleteHistory : LegacyDialog
}

/**
 * What the 2.x left ([summary], null until read), where a move puts the extractions ([destination]: the outputs'
 * folder, its `Extractions`, no fallback), whether the app may write there ([accessGranted]: when not, the
 * dialog asks for the access instead of moving), and the question on screen.
 */
data class LegacyDataState(
    val summary: LegacyData.Summary? = null,
    val destination: String = "",
    val accessGranted: Boolean = true,
    val dialog: LegacyDialog? = null,
)

/**
 * The 2.x's data (docs D-021, D-022): the notice of the old extractions, once, at launch, and the settings'
 * section. Moves and deletions run on the application's scope: leaving the screen must not stop a move between
 * two files, and how it ended is said wherever the user is by then.
 */
@Suppress("TooManyFunctions") // One function per action of the notice and of the settings' rows
@HiltViewModel
class LegacyDataViewModel @Inject constructor(
    private val legacy: LegacyData,
    private val outputs: OutputStorage,
    private val visibility: StorageVisibility,
    private val messages: UserMessages,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LegacyDataState())
    val state: StateFlow<LegacyDataState> = mutableState.asStateFlow()

    /** A move or a deletion under way, whichever screen started it. */
    val working: StateFlow<Boolean> = legacy.working

    /**
     * One reading at a time, in the order asked: one started during a move, ending after the one its end asked
     * for, put back the files moved since (Claude review, phase 8).
     */
    private val reading = Mutex()

    init {
        // What is left once an operation ends, whichever screen started it.
        viewModelScope.launch { legacy.operationsEnded.drop(1).collect { read() } }
    }

    fun refresh() {
        viewModelScope.launch { read() }
    }

    /** At launch: the notice of the old extractions, once in the app's life, when there are some. */
    fun offerOnce() {
        viewModelScope.launch {
            if (withContext(io) { legacy.claimNotice() }) {
                read()
                mutableState.update { it.copy(dialog = LegacyDialog.Extractions(first = true)) }
            }
        }
    }

    fun askExtractions() = ask(LegacyDialog.Extractions(first = false))

    fun askDeleteExtractions() = ask(LegacyDialog.DeleteExtractions)

    fun askDeleteHistory() = ask(LegacyDialog.DeleteHistory)

    /** "Later", or a question dismissed. */
    fun dismiss() = mutableState.update { it.copy(dialog = null) }

    fun move() {
        dismiss()
        // Said at once: no dialog holds the screen while it runs, the panic included (Claude review, phase 8).
        messages.info(uiText(R.string.legacy_moving))
        scope.launch {
            val outcome = withContext(io) { legacy.moveExtractions() }
            val stoppedBy = outcome.stoppedBy
            val destination = outcome.destination
            when {
                stoppedBy is NoOutputLocationException -> messages.error(
                    uiText(R.string.legacy_extractions_no_folder, mutableState.value.destination),
                )
                stoppedBy != null -> messages.error(
                    uiText(R.string.legacy_extractions_move_stopped, IoErrors.reasonOf(stoppedBy), outcome.moved, outcome.left),
                )
                destination != null -> messages.info(
                    UiText.Plural(R.plurals.legacy_extractions_moved, outcome.moved, listOf(outcome.moved, destination.path)),
                )
                // Nothing was left to move: moved or deleted meanwhile, from another screen.
                else -> Unit
            }
        }
    }

    fun deleteExtractions() = delete(R.string.legacy_extractions_deleted, R.string.legacy_extractions_delete_failed) {
        legacy.deleteExtractions()
    }

    fun deleteHistory() = delete(R.string.legacy_history_deleted, R.string.legacy_history_delete_failed) {
        legacy.deleteHistory()
    }

    private fun ask(dialog: LegacyDialog) {
        if (!working.value) mutableState.update { it.copy(dialog = dialog) }
    }

    private fun delete(done: Int, failed: Int, work: suspend () -> Boolean) {
        dismiss()
        scope.launch {
            if (withContext(io) { work() }) messages.info(uiText(done)) else messages.error(uiText(failed))
        }
    }

    private suspend fun read() = reading.withLock {
        val read = withContext(io) {
            Triple(
                legacy.summary(),
                File(outputs.basePath(), OutputCategory.EXTRACTIONS.folderName).path,
                visibility.seesEverything(),
            )
        }
        mutableState.update { it.copy(summary = read.first, destination = read.second, accessGranted = read.third) }
    }
}
