package com.readfilestech.read_files_tech.ui.tools.format

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.outputs.create
import com.readfilestech.read_files_tech.data.tools.CodeFormat
import com.readfilestech.read_files_tech.data.tools.FormatAction
import com.readfilestech.read_files_tech.data.tools.Formattable
import com.readfilestech.read_files_tech.data.tools.TextPreview
import com.readfilestech.read_files_tech.data.tools.ToolTexts
import com.readfilestech.read_files_tech.data.viewer.json.JsonRefusal
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.tools.copyableResult
import com.readfilestech.read_files_tech.ui.tools.outputFailure
import com.readfilestech.read_files_tech.ui.tools.readFailure
import com.readfilestech.read_files_tech.ui.tools.resultCopied
import com.readfilestech.read_files_tech.ui.viewer.json.JsonViewerViewModel
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The format tool's screen: what is done, the text typed or the file chosen, the beginning of the result
 * and its length — or why there is none — and the file it was saved to.
 */
data class FormatState(
    val action: FormatAction = FormatAction.JSON_INDENTED,
    val input: String = "",
    val file: FileEntry? = null,
    val working: Boolean = false,
    val preview: String = "",
    val length: Long = 0,
    val failure: UiText? = null,
    val saving: Boolean = false,
    val saved: String? = null,
    val share: String? = null,
)

/**
 * `FormatScreen` of the 2.15.3. A file chosen goes into the text field when it is short enough to be
 * copied, as the 2.15.3 put every file there; a longer one is formatted from its bytes, its name shown in
 * the field's place — a 50 MB text in a field froze the 2.15.3. The result is made again at each change,
 * off the main thread, its beginning kept for the screen; saving writes it whole into `Conversions/`,
 * atomically, and shares it at once if the setting says so (`_saveAndShare`, `format_screen.dart:107-128`).
 */
@HiltViewModel
class FormatViewModel @Inject constructor(
    private val messages: UserMessages,
    private val outputs: OutputStorage,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FormatState())
    val state: StateFlow<FormatState> = mutableState.asStateFlow()

    /** The bytes of the file chosen, when they are formatted rather than typed. */
    private var fileBytes: Pair<ByteArray, Int>? = null
    private var ready: Formattable.Ready? = null
    private var job: Job? = null

    fun input(text: String) {
        fileBytes = null
        mutableState.update { it.copy(input = text, file = null) }
        format()
    }

    fun action(action: FormatAction) {
        mutableState.update { it.copy(action = action) }
        format()
    }

    fun clear() = input("")

    fun choose(path: String) {
        job?.cancel()
        job = viewModelScope.launch {
            val file = File(path)
            mutableState.update { it.copy(working = true, failure = null, saved = null) }
            loadOrFailure { withContext(io) { DirectoryListing.entryOf(file) to ToolTexts.read(file) } }
                .onSuccess { (entry, read) ->
                    val (bytes, size) = read
                    if (size <= FIELD_BYTES) {
                        fileBytes = null
                        mutableState.update { it.copy(input = ToolTexts.decode(bytes, size), file = null) }
                    } else {
                        fileBytes = read
                        mutableState.update { it.copy(input = "", file = entry) }
                    }
                    format()
                }
                .onFailure { error -> mutableState.update { it.copy(working = false, failure = readFailure(file.name, error)) } }
        }
    }

    private fun format() {
        job?.cancel()
        ready = null
        val asked = mutableState.value
        val (bytes, size) = fileBytes ?: asked.input.encodeToByteArray().let { it to it.size }
        if (size == 0) {
            mutableState.update { it.copy(working = false, preview = "", length = 0, failure = null, saved = null) }
            return
        }
        mutableState.update { it.copy(working = true, failure = null, saved = null) }
        job = viewModelScope.launch {
            val made = loadOrFailure {
                withContext(Dispatchers.Default) {
                    when (val prepared = CodeFormat.prepare(asked.action, bytes, size)) {
                        is Formattable.NotJson -> throw NotJsonException(prepared.refusal)
                        is Formattable.Ready -> {
                            val context = currentCoroutineContext()
                            val preview = TextPreview(JsonViewerViewModel.COPY_CHARS)
                            prepared.write(preview) { context.ensureActive() }
                            prepared to preview
                        }
                    }
                }
            }
            made.onSuccess { (prepared, preview) ->
                ready = prepared
                mutableState.update { it.copy(working = false, preview = preview.text, length = preview.length) }
            }.onFailure { error ->
                val failure = (error as? NotJsonException)?.let { refusalText(it.refusal) } ?: uiText(R.string.error_unexpected)
                mutableState.update { it.copy(working = false, preview = "", length = 0, failure = failure) }
            }
        }
    }

    fun copyText(): String? = state.value.let { copyableResult(it.preview, it.length, messages) }

    fun copied() = resultCopied(messages)

    /** The result written whole into `Conversions/result_<stamp>.<ext>`. */
    fun save() {
        val result = ready ?: return
        val action = state.value.action
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val saved = loadOrFailure { withContext(io) { outputs.saveFormatted(result, action) to outputs.autoShare() } }
            saved.onSuccess { (file, share) ->
                mutableState.update { it.copy(saving = false, saved = file.path, share = file.path.takeIf { share }) }
            }.onFailure { error ->
                mutableState.update { it.copy(saving = false) }
                messages.error(outputFailure(RESULT_NAME, error) ?: uiText(R.string.format_save_failed, IoErrors.reasonOf(error)))
            }
        }
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    /** Carries a refusal through the work's result. */
    private class NotJsonException(val refusal: JsonRefusal) : Exception()

    private companion object {
        /** A file this short goes into the text field: no longer than a text the clipboard takes. */
        const val FIELD_BYTES = JsonViewerViewModel.COPY_CHARS
    }
}

/** The 2.15.3's name (`result_<ms>`), the time stamp now the storage's own. */
private const val RESULT_NAME = "result"

/**
 * [result] written whole, in UTF-8, into `Conversions/result_<stamp>.<ext>` ([create]): a save stopped — the
 * screen left — publishes nothing, even once the last piece is written.
 */
internal suspend fun OutputStorage.saveFormatted(result: Formattable.Ready, action: FormatAction): File {
    val context = currentCoroutineContext()
    return create(OutputCategory.CONVERSIONS, RESULT_NAME, action.extension) { stream ->
        val writer = stream.bufferedWriter(Charsets.UTF_8)
        result.write(writer) { context.ensureActive() }
        writer.flush()
    }
}

/** Why a text is not formatted as JSON, as the format tool says it. */
private fun refusalText(refusal: JsonRefusal): UiText = when (refusal) {
    is JsonRefusal.Syntax -> uiText(R.string.format_not_json, refusal.line, refusal.column)
    is JsonRefusal.TooDeep -> uiText(R.string.format_too_deep, refusal.limit)
    is JsonRefusal.TooManyValues -> uiText(R.string.format_too_many_values, refusal.limit)
}
