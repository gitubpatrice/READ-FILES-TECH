package com.readfilestech.read_files_tech.ui.tools.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.search.ContentEvent
import com.readfilestech.read_files_tech.data.search.ContentHit
import com.readfilestech.read_files_tech.data.search.ContentSearch
import com.readfilestech.read_files_tech.data.search.InvalidPatternException
import com.readfilestech.read_files_tech.data.search.PatternProblem
import com.readfilestech.read_files_tech.data.search.SearchPattern
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
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

data class ContentSearchState(
    val root: String? = null,
    val searching: Boolean = false,
    val scanned: Int = 0,
    val hits: List<ContentHit> = emptyList(),
    /** The last search's end, with what it could not read; null before one ended. */
    val finished: ContentEvent.Finished? = null,
)

/**
 * `ContentSearchScreen` of the 2.15.3: a folder, a word or a regular expression, case or not. The search
 * runs off the main thread ([ContentSearch]), its results shown as they come; a new one stops the last, and
 * leaving the screen stops it. A pattern refused is said in words, and the screen is free again — the
 * 2.15.3 stayed blocked after "regex too long" (inventory F4).
 */
@HiltViewModel
class ContentSearchViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ContentSearchState(root = handle[KEY_ROOT]))
    val state: StateFlow<ContentSearchState> = mutableState.asStateFlow()
    private var running: Job? = null

    fun setRoot(folder: String) {
        handle[KEY_ROOT] = folder
        mutableState.update { it.copy(root = folder) }
    }

    fun start(text: String, matchCase: Boolean, regex: Boolean) {
        val root = mutableState.value.root ?: return
        val pattern = patternOf(text, matchCase, regex) ?: return
        running?.cancel()
        mutableState.update { it.copy(searching = true, scanned = 0, hits = emptyList(), finished = null) }
        running = viewModelScope.launch {
            try {
                ContentSearch.run(File(root), pattern).flowOn(io).collect { event ->
                    when (event) {
                        is ContentEvent.Found -> mutableState.update { it.copy(hits = it.hits + event.hits) }
                        is ContentEvent.Scanned -> mutableState.update { it.copy(scanned = event.files) }
                        is ContentEvent.Finished -> mutableState.update { it.copy(finished = event) }
                    }
                }
            } finally {
                // Only the CURRENT search says it ended (the global search's Claude review R5).
                if (running === coroutineContext.job) mutableState.update { it.copy(searching = false) }
            }
        }
    }

    /** What [text] asks for; null, and said, when it is empty or refused. */
    private fun patternOf(text: String, matchCase: Boolean, regex: Boolean): SearchPattern? = when {
        text.isEmpty() -> null.also { messages.info(uiText(R.string.content_search_needs_text)) }
        else -> try {
            if (regex) SearchPattern.regex(text, matchCase) else SearchPattern.literal(text, matchCase)
        } catch (e: InvalidPatternException) {
            null.also { messages.error(patternProblem(e.problem)) }
        }
    }

    /** Stops the search; what was found stays. */
    fun stop() {
        running?.cancel()
    }

    private companion object {
        const val KEY_ROOT = "content_search_root"
    }
}

/** Why a pattern the user typed is refused, in words: the content search's and the bulk rename's. */
fun patternProblem(problem: PatternProblem): UiText = when (problem) {
    PatternProblem.TOO_LONG -> UiText.Plural(R.plurals.pattern_too_long, FileCaps.PATTERN_CHARS)
    PatternProblem.INVALID -> uiText(R.string.pattern_invalid)
    PatternProblem.TOO_MANY_REPEATS -> UiText.Plural(R.plurals.pattern_too_many_repeats, FileCaps.PATTERN_REPEATS)
    PatternProblem.BAD_REPLACEMENT -> uiText(R.string.pattern_bad_replacement)
}
