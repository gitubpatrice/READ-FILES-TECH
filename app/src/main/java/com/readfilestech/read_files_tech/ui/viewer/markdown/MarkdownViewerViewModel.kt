package com.readfilestech.read_files_tech.ui.viewer.markdown

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.images.ImageDecoding
import com.readfilestech.read_files_tech.data.images.ImageScale
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownContent
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownFile
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownImage
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownImages
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownLinks
import com.readfilestech.read_files_tech.data.viewer.text.CodeLanguage
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import com.readfilestech.read_files_tech.ui.viewer.text.RowColours
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The Markdown viewer: the file read and rendered once, off the main thread. */
@HiltViewModel
class MarkdownViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val source: ViewerSource? = ViewerSources.from(handle)

    val content = ViewerContent(viewModelScope, io) { MarkdownFile.read(requireNotNull(source) { "no file in the route" }) }
        .also(::addCloseable)

    private val rowColours = MutableStateFlow<RowColours?>(null)

    /** The syntax colours of the source's rows, once it is read — the 2.15.3 coloured it as `markdown`. */
    val sourceColours: StateFlow<RowColours?> = rowColours.asStateFlow()

    init {
        viewModelScope.launch {
            val source = content.state.filterIsInstance<ViewerState.Ready<MarkdownContent>>().first().content.source
            rowColours.value = RowColours(source, CodeLanguage.MARKDOWN, viewModelScope, io)
        }
    }

    /** The images decoded, within [FileCaps.MARKDOWN_IMAGE_BYTES]: the one shown least recently goes first. */
    private val images = object : LruCache<String, Bitmap>(FileCaps.MARKDOWN_IMAGE_BYTES.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * [image] decoded for a width of [width] pixels, at most [MAX_ELONGATION] widths tall — by the image
     * viewer's decoder, within its caps — or null when it cannot be: too large, damaged, not an image, or no
     * longer the file the text named (a link put in its place since).
     */
    suspend fun image(image: MarkdownImage, width: Int): Bitmap? {
        val key = "$width:${image.file.path}"
        images.get(key)?.let { return it }
        return loadOrFailure {
            withContext(io) {
                if (MarkdownImages.canonical(image.file) != image.file) throw UnreadableFileException("image moved")
                ImageDecoding.still(image.file) { w, h -> ImageScale.sampleSize(w, h, width, width * MAX_ELONGATION) }
            }
        }.getOrNull()?.also { images.put(key, it) }
    }

    /**
     * A tap on a link to [destination]: opened in another app when it leads to the web or to an e-mail
     * address ([MarkdownLinks]), refused in words otherwise.
     */
    fun open(context: Context, destination: String) {
        val target = MarkdownLinks.opened(destination)
        when {
            target == null -> messages.error(uiText(R.string.markdown_link_refused))
            !start(context, target) -> messages.error(uiText(R.string.markdown_no_app))
        }
    }

    // TooGenericExceptionCaught: as FileLauncher.start — no app (ActivityNotFoundException), or a
    // SecurityException from the one that would answer, is no reason to end this app. No suspending call.
    @Suppress("TooGenericExceptionCaught")
    private fun start(context: Context, target: String): Boolean = try {
        // BROWSABLE: only an activity that declares it may be reached from a link, as from a browser.
        context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (expected: RuntimeException) {
        false
    }

    private companion object {
        /** The tallest image decoded, in widths: a long screenshot is subsampled more, never held whole. */
        const val MAX_ELONGATION = 4
    }
}
