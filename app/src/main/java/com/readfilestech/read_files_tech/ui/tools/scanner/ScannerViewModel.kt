package com.readfilestech.read_files_tech.ui.tools.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.scanner.JpegInfo
import com.readfilestech.read_files_tech.data.scanner.JpegPage
import com.readfilestech.read_files_tech.data.scanner.JpegPdf
import com.readfilestech.read_files_tech.data.scanner.PageFilter
import com.readfilestech.read_files_tech.data.scanner.PageGeometry
import com.readfilestech.read_files_tech.data.scanner.PageSettings
import com.readfilestech.read_files_tech.data.scanner.PageSource
import com.readfilestech.read_files_tech.data.scanner.Point
import com.readfilestech.read_files_tech.data.scanner.ScanDraft
import com.readfilestech.read_files_tech.data.scanner.ScanFiles
import com.readfilestech.read_files_tech.data.scanner.ScanPage
import com.readfilestech.read_files_tech.data.scanner.ScanPages
import com.readfilestech.read_files_tech.data.scanner.ScanSession
import com.readfilestech.read_files_tech.ui.common.ApplicationScope
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject

/** The crop screen: the frame being adjusted, the picture under it (null while it loads). */
data class PageEditorState(
    val draft: ScanDraft,
    val preview: ImageBitmap? = null,
    val detecting: Boolean = false,
    val notFound: Boolean = false,
)

/** What the crop screen asks; the view model answers. */
interface PageEditorActions {
    /** A corner dragged to [to] (fractions of the photo). */
    fun moveCorner(index: Int, to: Point)

    /** The end of a drag: the frame kept with the scan. */
    fun keepDraft()

    fun detectAgain()

    fun wholeImage()

    fun rotate()

    fun filter(filter: PageFilter)

    /** "Keep this page". */
    fun applyEditor()

    fun cancelEditor()
}

/** The scanner's screen: the pages so far, the crop screen if open, what is being done, the PDF made. */
data class ScannerState(
    val pages: List<ScanPage> = emptyList(),
    val sessionId: String? = null,
    val editor: PageEditorState? = null,
    val progress: UiText? = null,
    val result: String? = null,
    /** The PDF to share at once ("share automatically after creation"), once. */
    val share: String? = null,
)

/**
 * `ScannerScreen` of the 2.15.3, without ML Kit (docs D-031): photos taken by the system's camera app
 * (no CAMERA permission, D-027) or imported, each framed by the detection and adjusted by hand,
 * straightened, filtered, then one PDF of their JPEGs in `Scans/scan_<stamp>.pdf`.
 *
 * The scan lives in its folder (ScanFiles): only its id is in the saved state, so a process killed while
 * the camera app is open — likely on a phone with little memory — loses neither the pages nor the photo.
 * One page is decoded or drawn at a time ([work]): two full-size photos at once would not fit an S9.
 */
// TooManyFunctions: one screen's verbs — photos in, the crop screen's eight, the pages', the export —
// each a few lines over the one session; split, they would share its state through callbacks.
@Suppress("TooManyFunctions")
@HiltViewModel
class ScannerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val handle: SavedStateHandle,
    private val outputs: OutputStorage,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel(), PageEditorActions {
    private val files = ScanFiles.under(context.cacheDir)
    private val work = Mutex()
    private val mutableState = MutableStateFlow(ScannerState(result = handle[KEY_RESULT]))
    val state: StateFlow<ScannerState> = mutableState.asStateFlow()
    private var session: ScanSession? = null
    private var editorJob: Job? = null
    private val thumbnails = object : LruCache<String, ImageBitmap>(THUMBNAIL_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    /** The scan the process died in, read back; what adds to the scan waits for it (a photo taken meanwhile). */
    private val restored: Job = viewModelScope.launch { handle.get<String>(KEY_SESSION)?.let { restore(it) } }

    private suspend fun restore(id: String) {
        val restored = withContext(io) { files.load(id) }
        if (restored == null) {
            handle.remove<String>(KEY_SESSION)
            return
        }
        publish(restored)
        restored.draft?.let { openEditor(it, detect = false) }
    }

    /** The scan, created at its first photo: the PDF made before, if any, leaves the screen. */
    private fun current(): ScanSession = session ?: ScanSession(ScanFiles.newId()).also {
        session = it
        handle[KEY_SESSION] = it.id
        handle.remove<String>(KEY_RESULT)
        mutableState.update { s -> s.copy(result = null) }
    }

    private fun publish(s: ScanSession?) {
        session = s
        mutableState.update { it.copy(pages = s?.pages.orEmpty(), sessionId = s?.id) }
    }

    private suspend fun store(s: ScanSession) {
        publish(s)
        withContext(io) { files.save(s) }
    }

    fun pageFile(page: ScanPage): File? = session?.let { files.pageFile(it.id, page) }

    suspend fun thumbnail(page: ScanPage, side: Int): ImageBitmap? {
        val file = pageFile(page) ?: return null
        return thumbnails.get(file.path) ?: loadOrFailure {
            withContext(io) { ScanPages.thumbnail(file, side).asImageBitmap() }
        }.getOrNull()
            ?.also { thumbnails.put(file.path, it) }
    }

    // ---------------------------------------------------------------- photos in

    /** A new file for the camera app, and the URI it may write it through (that URI only); null if none could be made. */
    suspend fun prepareCapture(): Uri? {
        val file = loadOrFailure { withContext(io) { files.captures.newFile() } }
            .onFailure { messages.error(uiText(R.string.scanner_photo_failed, IoErrors.reasonOf(it))) }
            .getOrNull() ?: return null
        handle[KEY_CAPTURE] = file.path
        return uriOf(file)
    }

    private fun uriOf(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** The camera app is back ([taken]: it says it wrote the photo). Its grant ends here. */
    fun captured(taken: Boolean) {
        val file = handle.remove<String>(KEY_CAPTURE)?.let(::File) ?: return
        context.revokeUriPermission(uriOf(file), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        viewModelScope.launch {
            restored.join()
            val id = current().id
            val adopted = loadOrFailure {
                withContext(io) {
                    if (taken && file.length() > 0) {
                        files.captures.adopt(file, id)
                    } else {
                        files.captures.discard(file)
                        null
                    }
                }
            }
            // Cancelled in the camera app: nothing to say (the 2.15.3 showed a red error, F13).
            adopted.getOrNull()?.let {
                openEditor(ScanDraft(null, it, PageSettings(PageGeometry.WHOLE, 0, current().filter)), detect = true)
            }
            adopted.exceptionOrNull()?.let { messages.error(uiText(R.string.scanner_photo_failed, reasonOf(it))) }
        }
    }

    fun noCamera() = messages.error(uiText(R.string.scanner_no_camera))

    /** Images chosen in the picker: each framed by the detection (the whole image if nothing is found) and added. */
    fun import(paths: List<String>) {
        viewModelScope.launch {
            restored.join()
            val room = ScanSession.MAX_PAGES - current().pages.size
            if (paths.size > room) messages.info(FULL)
            val taken = paths.take(room)
            taken.forEachIndexed { i, path ->
                mutableState.update { it.copy(progress = uiText(R.string.scanner_adding, i + 1, taken.size)) }
                val s = current()
                val made = loadOrFailure { work.withLock { withContext(io) { importOne(File(path), s) } } }
                made.getOrNull()?.let { store(current().withPage(it)) }
                made.exceptionOrNull()?.let { messages.error(uiText(R.string.scanner_page_failed, File(path).name, reasonOf(it))) }
            }
            mutableState.update { it.copy(progress = null) }
        }
    }

    private fun CoroutineScope.importOne(file: File, s: ScanSession): ScanPage {
        val found = ScanPages.find(file) { ensureActive() }?.takeIf(PageGeometry::isUsable)
        val page = ScanPage(ScanFiles.newId(), PageSource.External(file.path), PageSettings(found ?: PageGeometry.WHOLE, 0, s.filter), 0)
        ScanPages.render(file, page.settings, files.pageFile(s.id, page)) { ensureActive() }
        return page
    }

    // ---------------------------------------------------------------- the crop screen

    fun edit(page: ScanPage) {
        viewModelScope.launch { openEditor(ScanDraft(page.id, page.source, page.settings), detect = false) }
    }

    private suspend fun openEditor(draft: ScanDraft, detect: Boolean) {
        editorJob?.cancel()
        mutableState.update { it.copy(editor = PageEditorState(draft, detecting = detect)) }
        store(current().copy(draft = draft))
        editorJob = viewModelScope.launch {
            val source = files.sourceFile(current().id, draft.source)
            val analysed = loadOrFailure {
                work.withLock { withContext(io) { ScanPages.analyse(source, detect) { ensureActive() } } }
            }
            analysed.onSuccess { photo ->
                val found = photo.found?.takeIf(PageGeometry::isUsable)
                updateEditor { e ->
                    val settings = if (found != null) e.draft.settings.copy(quad = found) else e.draft.settings
                    val notFound = detect && found == null
                    val preview = photo.preview.asImageBitmap()
                    e.copy(draft = e.draft.copy(settings = settings), preview = preview, detecting = false, notFound = notFound)
                }
            }.onFailure { error ->
                messages.error(uiText(R.string.scanner_photo_failed, reasonOf(error)))
                closeEditor(discardNewPhoto = true)
            }
        }
    }

    private fun updateEditor(change: (PageEditorState) -> PageEditorState) {
        mutableState.update { s -> s.copy(editor = s.editor?.let(change)) }
    }

    private fun updateSettings(change: (PageSettings) -> PageSettings) =
        updateEditor { e -> e.copy(draft = e.draft.copy(settings = change(e.draft.settings)), notFound = false) }

    /** A corner dragged to [to] (fractions of the photo); refused if the frame would no longer be a page. */
    override fun moveCorner(index: Int, to: Point) {
        val settings = state.value.editor?.draft?.settings ?: return
        val quad = settings.quad.toMutableList().also { it[index] = Point(to.x.coerceIn(0.0, 1.0), to.y.coerceIn(0.0, 1.0)) }
        if (PageGeometry.isUsable(quad)) updateSettings { it.copy(quad = quad) }
    }

    /** The frame as it is now, kept with the scan (the end of a drag, a turn, a filter). */
    override fun keepDraft() {
        val draft = state.value.editor?.draft ?: return
        viewModelScope.launch { store(current().copy(draft = draft)) }
    }

    override fun detectAgain() {
        val preview = state.value.editor?.preview ?: return
        editorJob?.cancel()
        updateEditor { it.copy(detecting = true) }
        editorJob = viewModelScope.launch {
            val found = loadOrFailure {
                work.withLock { withContext(Dispatchers.Default) { ScanPages.detect(preview.asAndroidBitmap()) { ensureActive() } } }
            }
                .getOrNull()?.takeIf(PageGeometry::isUsable)
            updateEditor { e ->
                val settings = if (found != null) e.draft.settings.copy(quad = found) else e.draft.settings
                e.copy(draft = e.draft.copy(settings = settings), detecting = false, notFound = found == null)
            }
            keepDraft()
        }
    }

    override fun wholeImage() {
        updateSettings { it.copy(quad = PageGeometry.WHOLE) }
        keepDraft()
    }

    override fun rotate() {
        updateSettings { it.copy(turns = PageGeometry.normalized(it.turns + 1)) }
        keepDraft()
    }

    /** The filter of this page, and of the pages that follow. */
    override fun filter(filter: PageFilter) {
        updateSettings { it.copy(filter = filter) }
        session = current().copy(filter = filter)
        keepDraft()
    }

    /** The page made from the frame: written, then put in the list (in its place when it was there). */
    override fun applyEditor() {
        val editor = state.value.editor?.takeIf { it.preview != null && !it.detecting } ?: return
        if (editor.draft.page == null && current().isFull) {
            messages.info(FULL)
            return
        }
        editorJob?.cancel()
        mutableState.update { it.copy(progress = uiText(R.string.scanner_making_page)) }
        editorJob = viewModelScope.launch {
            val s = current()
            val draft = editor.draft
            val page = s.pages.find {
                it.id == draft.page
            }?.let { it.copy(source = draft.source, settings = draft.settings, revision = it.revision + 1) }
                ?: ScanPage(ScanFiles.newId(), draft.source, draft.settings, 0)
            val made = loadOrFailure {
                work.withLock {
                    withContext(io) {
                        val source = files.sourceFile(s.id, draft.source)
                        ScanPages.render(source, draft.settings, files.pageFile(s.id, page)) { ensureActive() }
                        files.deleteOldRevisions(s.id, page)
                    }
                }
            }
            mutableState.update { it.copy(progress = null) }
            made.onSuccess {
                mutableState.update { it.copy(editor = null) }
                store(current().withPage(page).copy(draft = null))
            }.onFailure { messages.error(uiText(R.string.scanner_page_not_made, reasonOf(it))) }
        }
    }

    override fun cancelEditor() {
        editorJob?.cancel()
        viewModelScope.launch { closeEditor(discardNewPhoto = true) }
    }

    /** Closes the crop screen; a photo just taken for a page never made goes with it. */
    private suspend fun closeEditor(discardNewPhoto: Boolean) {
        val draft = state.value.editor?.draft
        mutableState.update { it.copy(editor = null) }
        val s = session ?: return
        val owned = draft?.source as? PageSource.Owned
        val unused = discardNewPhoto && draft?.page == null && owned != null && s.pages.none { it.source == owned }
        withContext(io) { if (unused) files.sourceFile(s.id, requireNotNull(owned)).delete() }
        store(s.copy(draft = null))
    }

    // ---------------------------------------------------------------- the pages

    fun move(from: Int, to: Int) {
        val s = session ?: return
        viewModelScope.launch { store(s.moved(from, to)) }
    }

    fun delete(page: ScanPage) {
        val s = session ?: return
        viewModelScope.launch {
            store(s.without(page.id))
            withContext(io) { files.deletePageFiles(s.id, page) }
        }
    }

    /** One PDF of the pages' JPEGs, in `Scans/`; the scan's folder is emptied once it is written. */
    fun export() {
        val s = session ?: return
        if (s.pages.isEmpty() || state.value.progress != null) return
        mutableState.update { it.copy(progress = uiText(R.string.scanner_making_pdf)) }
        viewModelScope.launch {
            val made = loadOrFailure { work.withLock { withContext(io) { writePdf(s) to outputs.autoShare() } } }
            made.onSuccess { (pdf, share) ->
                handle.remove<String>(KEY_SESSION)
                handle[KEY_RESULT] = pdf.path
                session = null
                mutableState.value = ScannerState(result = pdf.path, share = pdf.path.takeIf { share })
                withContext(io) { files.deleteSession(s.id) }
            }.onFailure { error ->
                mutableState.update { it.copy(progress = null) }
                messages.error(uiText(R.string.scanner_pdf_failed, IoErrors.reasonOf(error)))
            }
        }
    }

    private fun writePdf(s: ScanSession): File {
        val pages = s.pages.map { page ->
            val file = files.pageFile(s.id, page)
            JpegPage(FileInputStream(file).use(JpegInfo::read), file.length()) { FileInputStream(file) }
        }
        val pdf = outputs.reserve(OutputCategory.SCANS, NAME, EXTENSION)
        var written = false
        try {
            outputs.write(pdf) { JpegPdf.write(it, pages) }
            written = true
        } finally {
            if (!written) outputs.discard(pdf)
        }
        return pdf
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    /** The scanner left: its pages and photos, never exported, go (the PDF made, if any, stays). */
    override fun onCleared() {
        val s = session
        val capture = handle.get<String>(KEY_CAPTURE)
        appScope.launch(io) {
            s?.let { files.deleteSession(it.id) }
            capture?.let { files.captures.discard(File(it)) }
        }
    }

    /** Why a photo or a page failed, in words — never the exception's own text. */
    private fun reasonOf(error: Throwable): UiText = when (val failure = ViewerErrors.failureOf(error)) {
        is ViewerFailure.Reason -> failure.text
        is ViewerFailure.TooLarge -> uiText(R.string.scanner_too_large, failure.capBytes / MIB)
    }

    private companion object {
        val FULL = UiText.Plural(R.plurals.scanner_full, ScanSession.MAX_PAGES)
        const val KEY_SESSION = "scanner_session"
        const val KEY_CAPTURE = "scanner_capture"
        const val KEY_RESULT = "scanner_result"
        const val NAME = "scan"
        const val EXTENSION = "pdf"

        /** 25 thumbnails of 240 × 340 pixels, and room for the ones of pages made again. */
        const val THUMBNAIL_BYTES = 16 * 1024 * 1024
        const val MIB = 1024L * 1024
    }
}
