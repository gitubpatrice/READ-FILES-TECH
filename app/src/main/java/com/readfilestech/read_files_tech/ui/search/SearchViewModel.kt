package com.readfilestech.read_files_tech.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.StorageVisibility
import com.readfilestech.read_files_tech.data.search.FileSearch
import com.readfilestech.read_files_tech.data.search.SearchEvent
import com.readfilestech.read_files_tech.data.search.SearchHit
import com.readfilestech.read_files_tech.data.search.SearchQuery
import com.readfilestech.read_files_tech.data.storage.StorageVolumes
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SearchUiState(
    val root: File,
    val searching: Boolean = false,
    val scanned: Int = 0,
    val results: List<SearchHit> = emptyList(),
    /** How the last search ended; null before one, and while one runs. */
    val finished: SearchEvent.Finished? = null,
    /** Without access to all files, a search finds only part of them: the screen says so, with the way to allow it. */
    val accessMissing: Boolean = false,
    /** Stopped by the user before its end: what is listed is partial. No end came — "enter a name" was shown. */
    val stopped: Boolean = false,
)

/** The global search (`global_search_screen.dart`): runs off the main thread, stops when asked or when the screen goes. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    volumes: StorageVolumes,
    private val visibility: StorageVisibility,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(SearchUiState(root = volumes.primary()))
    val uiState: StateFlow<SearchUiState> = state.asStateFlow()
    private var running: Job? = null

    /** Read again at each return to the screen: access may have been granted in Android's settings meanwhile. */
    fun refreshAccess() = state.update { it.copy(accessMissing = !visibility.seesEverything()) }

    /** The folder chosen in the picker; the screen hands it over from the navigation entry. */
    fun setRoot(folder: String) = state.update { it.copy(root = File(folder)) }

    /** Needs a name or a content (2.15.x); a search already running is stopped first. */
    fun start(name: String, content: String) {
        val namePattern = name.trim().ifEmpty { null }
        val contentPattern = content.trim().ifEmpty { null }
        if (namePattern == null && contentPattern == null) {
            messages.info(uiText(R.string.search_needs_criteria))
            return
        }
        running?.cancel()
        state.update { it.copy(searching = true, scanned = 0, results = emptyList(), finished = null, stopped = false) }
        running = viewModelScope.launch {
            try {
                FileSearch.run(SearchQuery(state.value.root, namePattern, contentPattern)).flowOn(io).collect { event ->
                    when (event) {
                        is SearchEvent.Found -> state.update { it.copy(results = it.results + event.hits) }
                        is SearchEvent.Scanned -> state.update { it.copy(scanned = event.files) }
                        is SearchEvent.Finished -> state.update { it.copy(finished = event) }
                    }
                }
            } finally {
                // Only the CURRENT search may say it ended: a cancelled one finishes its blocking read
                // after the new one started, and cleared "searching" under it (Claude review R5).
                if (running === coroutineContext.job) state.update { it.copy(searching = false) }
            }
        }
    }

    /** Stops the search; what was found stays. */
    fun stop() {
        if (running?.isActive != true) return
        running?.cancel()
        state.update { it.copy(stopped = true) }
    }
}
