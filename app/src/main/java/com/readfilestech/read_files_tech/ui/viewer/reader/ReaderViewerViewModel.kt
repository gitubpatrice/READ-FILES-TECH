package com.readfilestech.read_files_tech.ui.viewer.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.reader.EpubBook
import com.readfilestech.read_files_tech.data.viewer.reader.ReaderChapter
import com.readfilestech.read_files_tech.data.viewer.reader.ReaderDocument
import com.readfilestech.read_files_tech.data.viewer.reader.ReaderPage
import com.readfilestech.read_files_tech.data.viewer.reader.openingChapter
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the reader opens at its route: a page of the HTML viewer's extensions in reading mode — the HTML
 * viewer leads it here — and anything else as a book, told by its content (a ZIP that holds one).
 */
object ReaderOpening {
    suspend fun open(source: ViewerSource): ReaderDocument =
        if (source.extension in ViewerKind.HTML.extensions) ReaderPage.read(source) else EpubBook.open(source)
}

/** The reader: the book or page opened once off the main thread, then the chapter shown, read when it is chosen. */
@HiltViewModel
class ReaderViewerViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val source: ViewerSource? = ViewerSources.from(handle)

    val content = ViewerContent(viewModelScope, io) {
        keep(ReaderOpening.open(requireNotNull(source) { "no file in the route" }))
    }
        .also(::addCloseable)

    /** Read before [selected] writes its first value: a book reopened after a process death stays where it was. */
    private val firstOpening = KEY_CHAPTER !in handle

    /** The chapter shown, kept as the route is: across a process death too. */
    val selected: StateFlow<Int> = handle.getStateFlow(KEY_CHAPTER, 0)

    private val shown = MutableStateFlow<ViewerState<ReaderChapter>>(ViewerState.Loading)

    /** The chapter [selected], once read; a chapter that cannot be read says why in its place, the others read on. */
    val chapter: StateFlow<ViewerState<ReaderChapter>> = shown.asStateFlow()

    init {
        viewModelScope.launch {
            val document = content.state.filterIsInstance<ViewerState.Ready<ReaderDocument>>().first().content
            if (document.chapterCount == 0) return@launch
            if (firstOpening) {
                val opening = loadOrFailure { withContext(io) { document.openingChapter() } }.getOrDefault(0)
                // A chapter the user chose meanwhile wins.
                if (selected.value == 0) show(opening)
            }
            // A chapter chosen while the one before is read: that one is dropped, cancelled where it is.
            selected.collectLatest { index ->
                shown.value = ViewerState.Loading
                val chosen = index.coerceIn(0, document.chapterCount - 1)
                shown.value = loadOrFailure { withContext(io) { document.chapter(chosen) } }
                    .fold({ ViewerState.Ready(it) }, { ViewerState.Failed(ViewerErrors.failureOf(it)) })
            }
        }
    }

    fun show(index: Int) {
        handle[KEY_CHAPTER] = index
    }

    private companion object {
        const val KEY_CHAPTER = "reader_chapter"
    }
}
