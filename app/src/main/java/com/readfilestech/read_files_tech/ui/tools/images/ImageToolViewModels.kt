package com.readfilestech.read_files_tech.ui.tools.images

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.images.ImageExport
import com.readfilestech.read_files_tech.data.images.ImageMetadata
import com.readfilestech.read_files_tech.data.images.ImageMetadataReader
import com.readfilestech.read_files_tech.data.images.ImageSize
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.data.images.ImageToolRules
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.tools.outputFailure
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** A compressed copy made: where, and the sizes before and after. */
data class CompressResult(val path: String, val before: Long, val after: Long)

data class CompressState(
    val file: FileEntry? = null,
    val quality: Int = ImageToolRules.QUALITY_DEFAULT,
    val maxWidth: Int = ImageToolRules.WIDTH_DEFAULT,
    val working: Boolean = false,
    val result: CompressResult? = null,
    /** The copy to share at once ("share automatically after creation"), once. */
    val share: String? = null,
    val failure: UiText? = null,
)

/**
 * `CompressScreen` of the 2.15.3: a picture re-encoded as a JPEG at the quality and largest width chosen,
 * into `Compressions/` — upright and without its EXIF, where the 2.15.3 copied it, GPS included (D13). The
 * work runs off the main thread; leaving the screen stops it and leaves no copy.
 */
@HiltViewModel
class CompressViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val outputs: OutputStorage,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CompressState())
    val state: StateFlow<CompressState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        handle.get<String>(KEY_PATH)?.let(::choose)
    }

    fun choose(path: String) {
        handle[KEY_PATH] = path
        job?.cancel()
        job = viewModelScope.launch {
            val entry = withContext(io) { DirectoryListing.entryOf(File(path)) }
            mutableState.update { CompressState(file = entry, quality = it.quality, maxWidth = it.maxWidth) }
        }
    }

    fun setQuality(quality: Int) =
        mutableState.update { it.copy(quality = quality.coerceIn(ImageToolRules.QUALITY_MIN, ImageToolRules.QUALITY_MAX)) }

    fun setMaxWidth(width: Int) =
        mutableState.update { it.copy(maxWidth = width.coerceIn(ImageToolRules.WIDTH_MIN, ImageToolRules.WIDTH_MAX)) }

    fun compress() {
        val asked = state.value
        val file = asked.file?.takeUnless { asked.working } ?: return
        job = viewModelScope.launch {
            mutableState.update { it.copy(working = true, result = null, failure = null) }
            val made = loadOrFailure {
                withContext(io) {
                    val work = coroutineContext.job
                    val before = file.file.length()
                    val copy = ImageExport.compress(file.file, asked.quality, asked.maxWidth, outputs) { work.ensureActive() }
                    CompressResult(copy.path, before, copy.length()) to outputs.autoShare()
                }
            }
            mutableState.update { current ->
                made.fold(
                    { (result, share) -> current.copy(working = false, result = result, share = result.path.takeIf { share }) },
                    { current.copy(working = false, failure = outputFailure(file.name, it) ?: uiText(R.string.compress_failed, imageToolReason(it))) },
                )
            }
        }
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    private companion object {
        const val KEY_PATH = "compress_path"
    }
}

data class ExifState(
    val file: FileEntry? = null,
    val reading: Boolean = false,
    val metadata: ImageMetadata? = null,
    val size: ImageSize? = null,
    val working: Boolean = false,
    /** The copy without metadata, kept across a process death as the scanner keeps its PDF. */
    val result: String? = null,
    val share: String? = null,
    val failure: UiText? = null,
)

/**
 * `ExifScreen` of the 2.15.3, opened from the home's grid or on a file by the explorer's menu ([Routes.ARG_PATH]).
 * The metadata are read without decoding the picture, off the main thread (the 2.15.3 decoded it on the UI
 * thread, F12); the copy is written straight into `Sans-EXIF/` — the 2.15.3 went through `cache/`, where a
 * failure or a kill left it (§5.2) — upright (the 2.15.3 lost the orientation, F12), and shared when the
 * setting says so: its "Strip and share" never shared (F12).
 */
@HiltViewModel
class ExifViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val outputs: OutputStorage,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExifState())
    val state: StateFlow<ExifState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        (handle.get<String>(KEY_PATH) ?: handle.get<String>(Routes.ARG_PATH))?.let(::inspect)
    }

    fun choose(path: String) {
        handle.remove<String>(KEY_RESULT)
        inspect(path)
    }

    private fun inspect(path: String) {
        handle[KEY_PATH] = path
        job?.cancel()
        job = viewModelScope.launch {
            val file = File(path)
            val entry = withContext(io) { DirectoryListing.entryOf(file) }
            mutableState.value = ExifState(file = entry, reading = true, result = handle[KEY_RESULT])
            val read = loadOrFailure {
                withContext(io) {
                    val metadata = ImageMetadataReader.read(file)
                    metadata to ImageExport.uprightSize(file, metadata.orientation)
                }
            }
            mutableState.update { current ->
                read.fold(
                    { (metadata, size) -> current.copy(reading = false, metadata = metadata, size = size) },
                    { current.copy(reading = false, failure = uiText(R.string.exif_read_failed, imageToolReason(it))) },
                )
            }
        }
    }

    fun strip() {
        val current = state.value
        val file = current.file?.takeIf { current.metadata != null && !current.working } ?: return
        job = viewModelScope.launch {
            mutableState.update { it.copy(working = true, result = null, failure = null) }
            handle.remove<String>(KEY_RESULT)
            val made = loadOrFailure {
                withContext(io) {
                    val work = coroutineContext.job
                    ImageExport.strip(file.file, outputs) { work.ensureActive() }.path to outputs.autoShare()
                }
            }
            made.onSuccess { (path, _) -> handle[KEY_RESULT] = path }
            mutableState.update { s ->
                made.fold(
                    { (path, share) -> s.copy(working = false, result = path, share = path.takeIf { share }) },
                    { s.copy(working = false, failure = outputFailure(file.name, it) ?: uiText(R.string.exif_failed, imageToolReason(it))) },
                )
            }
        }
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    private companion object {
        const val KEY_PATH = "exif_path"
        const val KEY_RESULT = "exif_result"
    }
}

/**
 * Why an image tool failed, in words — never the exception's own text. What the decoder refused says why (too
 * large, too many pixels, damaged); any other I/O error is the file system's (no space, refused).
 */
internal fun imageToolReason(error: Throwable): UiText = when (error) {
    is FileTooLargeException -> uiText(R.string.scanner_too_large, error.capBytes / MIB)
    is UnreadableFileException, is UnsupportedFormatException, is ImageTooLargeException -> viewerReason(error)
    is IOException -> IoErrors.reasonOf(error)
    // Out of memory, or a decoder's own exception on a hostile file.
    else -> viewerReason(error)
}

private fun viewerReason(error: Throwable): UiText = when (val failure = ViewerErrors.failureOf(error)) {
    is ViewerFailure.Reason -> failure.text
    is ViewerFailure.TooLarge -> uiText(R.string.scanner_too_large, failure.capBytes / MIB)
}

private const val MIB = 1024L * 1024
