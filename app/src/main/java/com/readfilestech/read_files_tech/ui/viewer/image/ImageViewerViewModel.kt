package com.readfilestech.read_files_tech.ui.viewer.image

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.images.DecodedImage
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.image.GalleryOrder
import com.readfilestech.read_files_tech.data.viewer.image.ImageGallery
import com.readfilestech.read_files_tech.data.viewer.image.ImagePage
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val source: ViewerSource? = ViewerSources.from(handle)
    private val order = GalleryOrder.fromArgument(handle.get<String>(Routes.ARG_GALLERY))

    /** The images to swipe through: listing the folder, when the explorer opened the image — no pixel yet. */
    val content = ViewerContent(viewModelScope, io) {
        ImageGallery.load(requireNotNull(source) { "no file in the route" }, order, ViewerKind.IMAGE.extensions)
    }.also(::addCloseable)

    /**
     * One page's image, for a page of [width] × [height] pixels, loaded in the page's own [scope]: it is
     * cancelled, and its file released, when the page leaves the screen — the gallery never holds more
     * than the pages composed.
     */
    fun pageContent(scope: CoroutineScope, page: ImagePage, width: Int, height: Int): ViewerContent<DecodedImage> =
        ViewerContent(scope, io) { ImagePages.decode(page.source, width, height, this) }
}
