package com.readfilestech.read_files_tech.ui.tools.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.convert.SelectionTooLargeException
import com.readfilestech.read_files_tech.data.outputs.NoOutputLocationException
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.tools.ZipCreator
import com.readfilestech.read_files_tech.data.tools.ZipSourceException
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
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
import javax.inject.Inject

/** The ZIP tool's screen: the files chosen, the archive being made and how far, the last one made. */
data class ZipState(
    val files: List<FileEntry> = emptyList(),
    /** The part done, from 0 to 1, while the archive is made; null otherwise. */
    val progress: Float? = null,
    val result: String? = null,
    val resultCount: Int = 0,
    /** The archive to share at once ("share automatically after creation"), once. */
    val share: String? = null,
) {
    val totalBytes: Long get() = files.sumOf(FileEntry::size)
}

/**
 * `ZipCreatorScreen` of the 2.15.3: files added from the app's picker — each once —, one removed, the archive
 * made off the main thread with its progress, into `Conversions/` ([ZipCreator]), stopped when asked or when
 * the screen is left: then nothing is written. The files chosen are kept across a process death.
 */
@HiltViewModel
class ZipCreatorViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val creator: ZipCreator,
    private val outputs: OutputStorage,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ZipState(result = handle[KEY_RESULT], resultCount = handle[KEY_RESULT_COUNT] ?: 0))
    val state: StateFlow<ZipState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        // The files chosen, read again after a process death: the card of the last archive, if any, stays.
        handle.get<ArrayList<String>>(KEY_FILES)?.let { addFiles(it, chosen = false) }
    }

    /**
     * The files the picker handed back, after those already chosen; one already there is not added twice. Files
     * chosen take the last archive's card off the screen, as in the TXT and CSV tools and the OCR.
     */
    fun add(paths: List<String>) = addFiles(paths, chosen = true)

    private fun addFiles(paths: List<String>, chosen: Boolean) {
        if (chosen && paths.isNotEmpty()) clearResult()
        viewModelScope.launch {
            val added = withContext(io) { paths.distinct().map { DirectoryListing.entryOf(File(it)) } }
            mutableState.update { state ->
                val known = state.files.mapTo(HashSet(), FileEntry::path)
                state.copy(files = state.files + added.filter { it.path !in known })
            }
            remember()
        }
    }

    fun remove(path: String) {
        mutableState.update { state -> state.copy(files = state.files.filterNot { it.path == path }) }
        remember()
    }

    fun create() {
        val files = mutableState.value.files
        if (files.isEmpty() || job?.isCompleted == false) return
        // A new run: the last archive's card is not this run's, whatever it comes to.
        clearResult()
        mutableState.update { it.copy(progress = 0f) }
        job = viewModelScope.launch {
            try {
                val outcome = loadOrFailure {
                    withContext(io) {
                        creator.create(files.map(FileEntry::file)) { done ->
                            mutableState.update {
                                it.copy(progress = done)
                            }
                        } to outputs.autoShare()
                    }
                }
                outcome.fold(
                    { (archive, share) ->
                        handle[KEY_RESULT] = archive.path
                        handle[KEY_RESULT_COUNT] = files.size
                        handle[KEY_FILES] = null
                        val shared = archive.path.takeIf { share }
                        mutableState.update {
                            it.copy(files = emptyList(), result = archive.path, resultCount = files.size, share = shared)
                        }
                    },
                    { error -> messages.error(zipFailure(error)) },
                )
            } finally {
                mutableState.update { it.copy(progress = null) }
            }
        }
    }

    /** Stops the archive being made: nothing is written. */
    fun stop() {
        job?.cancel()
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    private fun remember() {
        handle[KEY_FILES] = ArrayList(mutableState.value.files.map(FileEntry::path))
    }

    private fun clearResult() {
        handle.remove<String>(KEY_RESULT)
        handle.remove<Int>(KEY_RESULT_COUNT)
        mutableState.update { it.copy(result = null, resultCount = 0, share = null) }
    }

    private companion object {
        const val KEY_FILES = "zip_files"
        const val KEY_RESULT = "zip_result"
        const val KEY_RESULT_COUNT = "zip_result_count"
        const val MIB = 1024L * 1024
    }

    /** Why no archive was made, in words — the 2.15.3 showed "Erreur : " and the exception. */
    private fun zipFailure(error: Throwable): UiText = when (error) {
        is SelectionTooLargeException -> uiText(R.string.zip_too_large, error.capBytes / MIB, uiText(R.string.unit_megabytes))
        is ZipSourceException -> uiText(R.string.tool_read_failed, error.name, IoErrors.reasonOf(error.cause ?: error))
        is NoOutputLocationException -> uiText(R.string.convert_no_output)
        else -> uiText(R.string.zip_failed, IoErrors.reasonOf(error))
    }
}
