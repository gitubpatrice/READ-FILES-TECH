package com.readfilestech.read_files_tech.ui.tools.convert

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.convert.Conversion
import com.readfilestech.read_files_tech.data.convert.ConversionResult
import com.readfilestech.read_files_tech.data.convert.Converter
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import com.readfilestech.read_files_tech.ui.viewer.pdf.PdfPhase
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

/** The Convert tool's screen: the conversion running and how far, its last output, the password it asks. */
data class ConvertState(
    val working: Conversion? = null,
    /** The part done, from 0 to 1; null while it is not known. */
    val progress: Float? = null,
    val result: String? = null,
    /** The output to share at once ("share automatically after creation"), once. */
    val share: String? = null,
    /** PDF → TXT of a file under a password: the question, and whether the last answer was wrong. */
    val password: PdfPhase.Locked? = null,
)

/**
 * `ConvertScreen` of the 2.15.3: a conversion chosen, its file(s) chosen in the app's picker, the output
 * written in `Conversions/` and shown as the scanner shows its PDF. One at a time, as the 2.15.3's `_busy`
 * guard; it runs off the main thread, with its progress, and stops when asked or when the screen is left —
 * its output then not written at all. The password of a PDF lives in the coroutine that tries it, never in
 * the saved state.
 */
@HiltViewModel
class ConvertViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val converter: Converter,
    private val outputs: OutputStorage,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ConvertState(result = handle[KEY_RESULT]))
    val state: StateFlow<ConvertState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** The PDF waiting for its password. */
    private var locked: List<String>? = null

    /** [conversion] chosen: the screen opens the picker, whose answer comes to [picked]. */
    fun choose(conversion: Conversion) {
        handle[KEY_PENDING] = conversion.name
    }

    fun picked(paths: List<String>) {
        val conversion = handle.get<String>(KEY_PENDING)?.let { name -> Conversion.entries.find { it.name == name } } ?: return
        handle.remove<String>(KEY_PENDING)
        if (paths.isNotEmpty()) start(conversion, paths, password = null)
    }

    /** The password of the PDF waiting for it, tried. */
    fun unlock(password: String) {
        val paths = locked ?: return
        if (mutableState.value.password?.busy != false) return
        mutableState.update { it.copy(password = PdfPhase.Locked(busy = true)) }
        start(Conversion.PDF_TO_TEXT, paths, password)
    }

    /** The question left: the PDF is not converted, the attempt in progress stopped. */
    fun dismissPassword() {
        locked = null
        job?.cancel()
        mutableState.update { it.copy(password = null) }
    }

    /** Stops the conversion running: nothing is written. */
    fun stop() {
        job?.cancel()
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    private fun start(conversion: Conversion, paths: List<String>, password: String?) {
        if (job?.isActive == true) return
        mutableState.update { it.copy(working = conversion, progress = null) }
        job = viewModelScope.launch {
            try {
                val outcome = loadOrFailure {
                    withContext(io) {
                        val result = converter.convert(conversion, paths.map(::File), password) { done ->
                            // Work done: a password asked for was the right one.
                            mutableState.update { it.copy(progress = done, password = null) }
                        }
                        result to outputs.autoShare()
                    }
                }
                outcome.fold(
                    { (result, share) -> done(result, paths, share) },
                    { error ->
                        locked = null
                        mutableState.update { it.copy(password = null) }
                        messages.error(convertFailure(conversion, File(paths.first()).name, error))
                    },
                )
            } finally {
                mutableState.update { it.copy(working = null, progress = null) }
            }
        }
    }

    private fun done(result: ConversionResult, paths: List<String>, share: Boolean) {
        when (result) {
            is ConversionResult.Made -> {
                locked = null
                handle[KEY_RESULT] = result.file.path
                mutableState.update { it.copy(result = result.file.path, share = result.file.path.takeIf { share }, password = null) }
            }
            is ConversionResult.PasswordRequired -> {
                locked = paths
                mutableState.update { it.copy(password = PdfPhase.Locked(wrong = result.wrong)) }
            }
        }
    }

    private companion object {
        const val KEY_PENDING = "convert_pending"
        const val KEY_RESULT = "convert_result"
    }
}
