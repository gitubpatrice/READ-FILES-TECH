package com.readfilestech.read_files_tech.ui.viewer.image

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.images.DecodedImage
import com.readfilestech.read_files_tech.data.images.ImageDecoding
import com.readfilestech.read_files_tech.data.images.ImageScale
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.ui.viewer.ViewerResources

/** What a page of the image viewer shows — the screen's call, and the corpus's (CorpusCheck). */
object ImagePages {
    /**
     * The image of [source] for a page of [width] × [height] pixels: the caps first, then a decode at up to
     * twice the size it is shown at ([ImageScale.fittingSample]) — never the whole of a 64 Mpx photo.
     * The file stays acquired while the page shows ([resources]): an animation reads its frames from it.
     */
    fun decode(source: ViewerSource, width: Int, height: Int, resources: ViewerResources): DecodedImage {
        val file = resources.keep(source.acquireFile(FileCaps.IMAGE_FILE)).file
        return ImageDecoding.decode(file, animate = true) { imageWidth, imageHeight ->
            ImageScale.fittingSample(imageWidth, imageHeight, width, height)
        }
    }
}
