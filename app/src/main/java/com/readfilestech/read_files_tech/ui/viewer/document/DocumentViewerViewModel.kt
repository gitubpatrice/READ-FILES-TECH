package com.readfilestech.read_files_tech.ui.viewer.document

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.document.DocumentText
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/** The document viewer's text, read once off the main thread; what the read opens, it closes itself. */
@HiltViewModel
class DocumentViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    @param:IoDispatcher io: CoroutineDispatcher,
) : ViewModel() {
    val source: ViewerSource? = ViewerSources.from(handle)

    val content = ViewerContent(viewModelScope, io) { DocumentText.read(requireNotNull(source) { "no file in the route" }) }
        .also(::addCloseable)
}
