package com.readfilestech.read_files_tech.ui.tools.ocr

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.ocr.OcrEngine
import com.readfilestech.read_files_tech.data.ocr.OcrImages
import com.readfilestech.read_files_tech.data.ocr.OcrOutput
import com.readfilestech.read_files_tech.data.ocr.RecognitionFailedException
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.ui.common.ApplicationScope
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.tools.copyableResult
import com.readfilestech.read_files_tech.ui.tools.images.imageToolReason
import com.readfilestech.read_files_tech.ui.tools.outputFailure
import com.readfilestech.read_files_tech.ui.tools.resultCopied
import com.readfilestech.read_files_tech.ui.tools.scanner.CameraCapture
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class OcrState(
    /** The picture read, small; null before one is chosen and while it decodes. */
    val preview: ImageBitmap? = null,
    val working: Boolean = false,
    /** The text found: null before a picture is read, empty when it holds none. */
    val text: String? = null,
    val failure: UiText? = null,
    val saving: Boolean = false,
    /** The .txt saved, kept across a process death as the scanner keeps its PDF. */
    val saved: String? = null,
    /** The .txt to share at once ("share automatically after creation"), once. */
    val share: String? = null,
) {
    /** Nothing chosen yet, nothing saved: the screen introduces the tool. */
    val untouched: Boolean get() = !working && text == null && failure == null && saved == null
}

/** Why a picture gave no text, in words: the decoder's refusal as the image tools say it, or ML Kit's failure. */
internal fun ocrReason(error: Throwable): UiText =
    if (error is RecognitionFailedException) uiText(R.string.ocr_recognition_failed) else imageToolReason(error)

/**
 * `OcrScreen` of the 2.15.3: a picture from the picker or a photo of the system's camera app (the scanner's
 * capture, no CAMERA permission: docs D-027), its latin text recognised on the phone by ML Kit (D-031), then
 * copied, saved as `OCR/ocr_<stamp>.txt` or shared.
 *
 * Departures, each a defect of the 2.15.3: the photo is the app's file, deleted once decoded (its plugin left
 * `cache/image_picker*.jpg` for ever, D16); the picture is decoded within the image viewer's bounds,
 * upright (`InputImage.fromFilePath`, no cap); a failure is said in words (`'Erreur OCR : $e'`,
 * `ocr_screen.dart:52`); leaving the screen drops the recognition under way and closes the recognizer.
 */
@HiltViewModel
class OcrViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    private val handle: SavedStateHandle,
    private val outputs: OutputStorage,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val camera = CameraCapture(context, handle, KEY_CAPTURE)
    private val engine = OcrEngine(context)
    private val mutableState = MutableStateFlow(OcrState(saved = handle[KEY_SAVED]))
    val state: StateFlow<OcrState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        // A picture of the picker read again after a process death; a photo was deleted once decoded.
        handle.get<String>(KEY_PATH)?.let { read(File(it), photo = false, chosen = false) }
    }

    /** A picture chosen in the picker. */
    fun choose(path: String) {
        handle[KEY_PATH] = path
        read(File(path), photo = false, chosen = true)
    }

    /** A new file for the camera app, and the URI it may write it through; null (said) if none could be made. */
    suspend fun prepareCapture(): Uri? = loadOrFailure { camera.prepare(io) }
        .onFailure { messages.error(uiText(R.string.scanner_photo_failed, IoErrors.reasonOf(it))) }
        .getOrNull()

    /** The camera app is back ([taken]: it says it wrote the photo). Cancelled there: nothing to say. */
    fun captured(taken: Boolean) {
        val file = camera.returned() ?: return
        if (taken) {
            handle.remove<String>(KEY_PATH)
            read(file, photo = true, chosen = true)
        } else {
            appScope.launch(io) { camera.captures.discard(file) }
        }
    }

    fun noCamera() = messages.error(uiText(R.string.scanner_no_camera))

    /**
     * One picture at a time: a new one replaces the one being read, whose ML Kit task — which nothing stops —
     * is waited for before the new picture is decoded ([OcrEngine.idle]). A [photo] is deleted once decoded,
     * whatever happens. A picture [chosen] now (not read again after a process death) takes the .txt saved from
     * the previous one off the screen: the 2.15.3 left its row under the new text.
     */
    private fun read(file: File, photo: Boolean, chosen: Boolean) {
        val previous = job
        previous?.cancel()
        if (chosen) handle.remove<String>(KEY_SAVED)
        mutableState.update {
            it.copy(preview = null, working = true, text = null, failure = null, saved = it.saved.takeUnless { chosen })
        }
        job = viewModelScope.launch {
            val read = loadOrFailure {
                val picture = try {
                    // The previous read gone first — its decode, which a cancellation does not stop — then
                    // ML Kit's task: two pictures never meet (Claude review R25: after a process death, the
                    // picker's picture read again and the camera's photo were decoded at once). Inside the
                    // try: a photo is deleted even when cancelled while waiting.
                    previous?.join()
                    engine.idle()
                    withContext(io) { OcrImages.decode(file) }
                } finally {
                    if (photo) withContext(NonCancellable + io) { camera.captures.discard(file) }
                }
                val preview = withContext(io) { OcrImages.preview(picture).asImageBitmap() }
                mutableState.update { it.copy(preview = preview) }
                engine.recognize(picture)
            }
            mutableState.update { current ->
                read.fold(
                    { current.copy(working = false, text = it) },
                    { current.copy(working = false, failure = uiText(R.string.ocr_failed, ocrReason(it))) },
                )
            }
        }
    }

    /** What "Copy" puts in the clipboard: the text, or null (said) when the clipboard cannot take it. */
    fun copyText(): String? = state.value.text?.let { copyableResult(it, it.length.toLong(), messages) }

    fun copied() = resultCopied(messages)

    /** "Save as .txt": `OCR/ocr_<stamp>.txt`, shared at once if the setting says so. */
    fun save() {
        val current = state.value
        val text = current.text?.takeIf { it.isNotEmpty() && !current.saving } ?: return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val saved = loadOrFailure {
                withContext(io) {
                    val work = coroutineContext.job
                    OcrOutput.save(outputs, text) { work.ensureActive() } to outputs.autoShare()
                }
            }
            saved.onSuccess { (file, share) ->
                handle[KEY_SAVED] = file.path
                mutableState.update { it.copy(saving = false, saved = file.path, share = file.path.takeIf { share }) }
            }.onFailure { error ->
                mutableState.update { it.copy(saving = false) }
                messages.error(outputFailure(error) ?: uiText(R.string.ocr_save_failed, IoErrors.reasonOf(error)))
            }
        }
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    /** The screen left: the recognition under way is dropped, the recognizer closed, a photo not yet back deleted. */
    override fun onCleared() {
        engine.close()
        camera.pending()?.let { capture -> appScope.launch(io) { camera.captures.discard(capture) } }
    }

    private companion object {
        const val KEY_PATH = "ocr_path"
        const val KEY_CAPTURE = "ocr_capture"
        const val KEY_SAVED = "ocr_saved"
    }
}
