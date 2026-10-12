package com.readfilestech.read_files_tech.data.images

import com.readfilestech.read_files_tech.data.files.FileCaps

/**
 * The image limits of the 2.15.x (`image_bounds.dart`, `file_caps.dart`): what the app agrees to
 * decode. A header announcing 50 000 × 50 000 pixels would ask for gigabytes at decode time.
 */
object ImageBounds {
    /** Pixels per side (`ImageBounds.maxWidth/maxHeight`): covers sensors well beyond 100 Mpx. */
    const val MAX_SIDE_PX = 12_000

    /** An image file larger than this is not decoded. */
    const val MAX_FILE_BYTES = FileCaps.IMAGE_FILE

    fun fits(width: Int, height: Int): Boolean = width in 1..MAX_SIDE_PX && height in 1..MAX_SIDE_PX
}
