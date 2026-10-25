package com.readfilestech.read_files_tech.ui.viewer.json

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.json.JsonContent
import com.readfilestech.read_files_tech.data.viewer.json.JsonDocument
import com.readfilestech.read_files_tech.data.viewer.json.JsonFile
import com.readfilestech.read_files_tech.data.viewer.json.JsonPretty
import com.readfilestech.read_files_tech.data.viewer.json.JsonTree
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A JSON file opened: its content, and the rows of its tree when it is one. */
class JsonOpened(val content: JsonContent, val tree: JsonTree?)

/**
 * The JSON viewer: the file read once off the main thread, the tree's rows built there with the document's
 * value opened (the 2.15.3's `initiallyExpanded`), and the indented lines made the first time they are asked
 * for. The view chosen is kept across a process death.
 */
@HiltViewModel
class JsonViewerViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    sources: ViewerSources,
) : ViewModel() {
    val source: ViewerSource? = sources.from(handle)

    val content = ViewerContent(viewModelScope, io) {
        when (val read = JsonFile.read(requireNotNull(source) { "no file in the route" })) {
            is JsonContent.Tree -> JsonOpened(read, JsonTree(read.document))
            is JsonContent.Text -> JsonOpened(read, null)
        }
    }.also(::addCloseable)

    /** Whether the indented text is shown rather than the tree. */
    val indented: StateFlow<Boolean> = handle.getStateFlow(KEY_INDENTED, false)

    private val mutablePretty = MutableStateFlow<JsonPretty?>(null)
    val pretty: StateFlow<JsonPretty?> = mutablePretty.asStateFlow()
    private var building: Job? = null

    /** Switches between the tree and the indented text, whose lines are made the first time. */
    fun toggleView(document: JsonDocument) {
        val indent = !indented.value
        handle[KEY_INDENTED] = indent
        if (indent) build(document)
    }

    /** The indented lines of [document], made once, off the main thread. */
    fun build(document: JsonDocument) {
        if (mutablePretty.value != null || building != null) return
        building = viewModelScope.launch {
            loadOrFailure { withContext(io) { JsonPretty(document) } }.fold(
                { mutablePretty.value = it },
                { error ->
                    // Back to the tree, which is there, and why.
                    handle[KEY_INDENTED] = false
                    building = null
                    (ViewerErrors.failureOf(error) as? ViewerFailure.Reason)?.let { messages.error(it.text) }
                },
            )
        }
    }

    /** What copying [value] puts in the clipboard, or null — said — when it is too long to. */
    fun copyText(document: JsonDocument, value: Int): String? = document.copyText(value, COPY_CHARS).also {
        if (it == null) messages.error(UiText.Plural(R.plurals.json_too_long_to_copy, COPY_CHARS))
    }

    fun copied() = messages.info(uiText(R.string.json_copied))

    companion object {
        /**
         * The longest value copied, in characters: the clipboard goes through a Binder transaction, whose
         * buffer (1 MB, shared by the process) a longer text could overflow — an exception in the app.
         */
        const val COPY_CHARS = 100_000

        private const val KEY_INDENTED = "indented"
    }
}
