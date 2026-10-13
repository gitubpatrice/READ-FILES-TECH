package com.readfilestech.read_files_tech.ui.viewer.markdown

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownFile
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownLinks
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/** The Markdown viewer: the file read and rendered once, off the main thread. */
@HiltViewModel
class MarkdownViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val messages: UserMessages,
    @param:IoDispatcher io: CoroutineDispatcher,
) : ViewModel() {
    val source: ViewerSource? = ViewerSources.from(handle)

    val content = ViewerContent(viewModelScope, io) { MarkdownFile.read(requireNotNull(source) { "no file in the route" }) }
        .also(::addCloseable)

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
}
