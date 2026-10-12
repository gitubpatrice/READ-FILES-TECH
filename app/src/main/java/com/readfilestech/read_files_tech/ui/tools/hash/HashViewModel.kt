package com.readfilestech.read_files_tech.ui.tools.hash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.tools.FileHash
import com.readfilestech.read_files_tech.data.tools.FileHashes
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
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
import javax.inject.Inject

/** The hash tool's screen: the file chosen, how far the reading is, its digests or why there are none. */
data class HashState(
    val file: FileEntry? = null,
    val read: Long = 0,
    val hashes: List<FileHash> = emptyList(),
    val computing: Boolean = false,
    val failure: UiText? = null,
)

/**
 * `HashScreen` of the 2.15.3. The file chosen is kept in the saved state, so the digests are computed
 * again after a process death; leaving the screen stops the reading (the 2.15.3's isolate read on, for
 * a result nobody would see).
 */
@HiltViewModel
class HashViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HashState())
    val state: StateFlow<HashState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        handle.get<String>(KEY_PATH)?.let(::choose)
    }

    fun choose(path: String) {
        handle[KEY_PATH] = path
        job?.cancel()
        job = viewModelScope.launch {
            val file = File(path)
            val entry = withContext(io) { DirectoryListing.entryOf(file) }
            mutableState.value = HashState(file = entry, computing = true)
            val result = loadOrFailure {
                withContext(io) {
                    StorageFileSource(file).open(Long.MAX_VALUE).use { input ->
                        FileHashes.of(input) { read -> mutableState.update { it.copy(read = read) } }
                    }
                }
            }
            mutableState.update { current ->
                result.fold(
                    { current.copy(hashes = it, computing = false) },
                    { current.copy(computing = false, failure = uiText(R.string.hash_failed, IoErrors.reasonOf(it))) },
                )
            }
        }
    }

    fun copied(value: String) = messages.info(uiText(R.string.value_copied, value))

    private companion object {
        const val KEY_PATH = "hash_path"
    }
}
