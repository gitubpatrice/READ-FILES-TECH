package com.readfilestech.read_files_tech.ui.viewer.html

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.html.HtmlLink
import com.readfilestech.read_files_tech.data.viewer.html.HtmlPage
import com.readfilestech.read_files_tech.data.viewer.html.HtmlResource
import com.readfilestech.read_files_tech.data.viewer.text.TextColor
import com.readfilestech.read_files_tech.data.viewer.text.TextColors
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.Links
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
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

/** The HTML viewer's page, read once off the main thread, its colours, and where the links the user taps lead. */
@HiltViewModel
class HtmlViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val launching: EntryLaunching,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    sources: ViewerSources,
) : ViewModel() {
    val source: ViewerSource? = sources.from(handle)

    /** The reader's route on this same page: its reading mode (`html_viewer_screen.dart:375-384`). */
    val readingRoute: String? =
        handle.get<String>(Routes.ARG_PATH)?.takeIf(String::isNotEmpty)?.let { Routes.viewer(ViewerKind.READER, it) }

    val content = ViewerContent(viewModelScope, io) {
        HtmlAssets(HtmlPage.read(requireNotNull(source) { "no file in the route" }))
    }
        .also(::addCloseable)

    private val found = MutableStateFlow<List<TextColor>>(emptyList())

    /** The colours written in the page's source (the 2.15.3's bar), found once the page shows. */
    val colors: StateFlow<List<TextColor>> = found.asStateFlow()

    init {
        viewModelScope.launch {
            val page = content.state.filterIsInstance<ViewerState.Ready<HtmlAssets>>().first().content.page
            // Without colours rather than without the app, as the text viewer.
            found.value = loadOrFailure { withContext(io) { TextColors.extract(page.source) } }.getOrDefault(emptyList())
        }
    }

    private val leaving = MutableStateFlow<String?>(null)

    /**
     * The web or e-mail address a tapped link leads to, shown before another app opens it (security audit of the
     * 3.0, A-1): a page styles its own links, and one stretched over the whole page made any tap a visit — which
     * told its author the file was opened, when, and from where.
     */
    val pendingWeb: StateFlow<String?> = leaving.asStateFlow()

    fun copied(code: String) = messages.info(uiText(R.string.value_copied, code))

    /**
     * Follows [link], which the user tapped on [page]: a file of the folder opens as the explorer opens it
     * — the route of a viewer of the app is returned, another app is started — and a web address, once the user
     * has seen it ([pendingWeb]), in the app they chose for it. Says so when nothing can open it. [from] is the
     * activity, never kept.
     */
    suspend fun follow(link: HtmlLink, page: HtmlPage, from: Context): String? {
        when (link) {
            is HtmlLink.Sibling -> {
                val entry = withContext(io) {
                    (page.resource(link.path) as? HtmlResource.Sibling)?.file?.let(DirectoryListing::entryOf)
                }
                if (entry == null) {
                    refused()
                } else {
                    when (val target = FileRouter.targetFor(entry)) {
                        is FileTarget.Internal -> return target.route
                        FileTarget.External -> launching.view(from, entry, chooser = false)
                    }
                }
            }
            is HtmlLink.Web -> leaving.value = link.url
            HtmlLink.Refused -> refused()
            HtmlLink.Scroll -> Unit
        }
        return null
    }

    /** The address of [pendingWeb], confirmed: opened in the app the user chose for it. [from] is the activity. */
    fun openWeb(from: Context) {
        val url = leaving.value ?: return
        leaving.value = null
        Links.messageFor(Links.open(from, url))?.let(messages::post)
    }

    fun stayOnPage() {
        leaving.value = null
    }

    private fun refused() = messages.info(uiText(R.string.html_link_refused))
}
