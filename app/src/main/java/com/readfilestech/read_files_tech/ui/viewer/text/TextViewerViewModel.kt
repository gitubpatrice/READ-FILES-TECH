package com.readfilestech.read_files_tech.ui.viewer.text

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.text.TextColor
import com.readfilestech.read_files_tech.data.viewer.text.TextColors
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
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

@HiltViewModel
class TextViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val source: ViewerSource? = ViewerSources.from(handle)

    val content = ViewerContent(viewModelScope, io) { TextDocument.read(requireNotNull(source) { "no file in the route" }) }
        .also(::addCloseable)

    private val found = MutableStateFlow<List<TextColor>>(emptyList())

    /** The colours of the text, found once it shows: a long text is on screen before they are all read. */
    val colors: StateFlow<List<TextColor>> = found.asStateFlow()

    init {
        viewModelScope.launch {
            val document = content.state.filterIsInstance<ViewerState.Ready<TextDocument>>().first().content
            found.value = withContext(io) { TextColors.extract(document) }
        }
    }

    fun copied(code: String) = messages.info(uiText(R.string.color_copied, code))
}
