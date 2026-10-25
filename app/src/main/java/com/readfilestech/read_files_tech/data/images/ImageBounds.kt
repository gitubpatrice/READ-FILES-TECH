package com.readfilestech.read_files_tech.data.images

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import java.io.IOException

/** Width and height in pixels, as an image's header announces them: 32 bits unsigned in a PNG, hence Long. */
data class ImageSize(val width: Long, val height: Long)

/** Why the dimensions of an image are refused, before any of its pixels is decoded. */
enum class ImageRefusal {
    /** A side of zero, or negative: no image has one — the header is damaged. */
    DAMAGED,

    /** A side over [ImageBounds.MAX_SIDE_PX] ("Dimensions image suspectes" of the 2.15.x). */
    SIDE,

    /** Both sides in bounds, but more than [FileCaps.IMAGE_PIXELS] in all ("Image trop lourde à décoder"). */
    PIXELS,
}

/**
 * An image whose header announces more pixels than the app decodes ([ImageRefusal.SIDE] or
 * [ImageRefusal.PIXELS]): refused before a pixel is allocated — a PNG of a few hundred bytes may announce
 * 100 000 × 100 000, which would ask for 40 GB.
 */
class ImageTooLargeException(val width: Long, val height: Long) : IOException("image of $width x $height pixels refused")

/**
 * The image limits of the 2.15.x (`image_bounds.dart`, `file_caps.dart`): what the app agrees to decode,
 * read from the header alone. [probe] is the 2.15.x's `probeDimensions`, ported with the fixes it had and
 * four more (see [HeaderProbe]); the decoder checks the platform's own reading of the header against
 * the same rule ([check]) before it allocates, whatever the probe found.
 */
object ImageBounds {
    /** Pixels per side (`ImageBounds.maxWidth/maxHeight`): covers sensors well beyond 100 Mpx. */
    const val MAX_SIDE_PX = 12_000

    /**
     * The bytes the probe reads: the dimensions are in the first ones. 64 KiB, as the 2.15.3's diagnosis
     * (`image_viewer_screen.dart:236`), covers the segments a JPEG puts before its frame (EXIF and its
     * thumbnail); a frame further on is left to the decoder's header, checked the same way.
     */
    const val HEADER_BYTES = 64 * 1024

    fun refusalOf(width: Long, height: Long): ImageRefusal? = when {
        width <= 0 || height <= 0 -> ImageRefusal.DAMAGED
        width > MAX_SIDE_PX || height > MAX_SIDE_PX -> ImageRefusal.SIDE
        // The side check alone does not do: 12 000 × 12 000 passes both bounds and is 144 Mpx, 576 MB of
        // RGBA, for a PNG of a few hundred KB that the file cap does not see either (GPT review, 2026-08-11).
        width * height > FileCaps.IMAGE_PIXELS -> ImageRefusal.PIXELS
        else -> null
    }

    /**
     * `assertSafeBounds`: the refusal of the dimensions [header] announces; null when they are accepted,
     * or when the format is not recognised or the header too short to tell — the decoder's own reading of
     * the header is checked after, whatever this says.
     */
    fun refusalOf(header: ByteArray): ImageRefusal? = probe(header)?.let { refusalOf(it.width, it.height) }

    /**
     * @throws UnreadableFileException a damaged header (a side of zero or negative).
     * @throws ImageTooLargeException a side over [MAX_SIDE_PX], or more than [FileCaps.IMAGE_PIXELS] in all.
     */
    fun check(width: Long, height: Long) {
        when (refusalOf(width, height)) {
            null -> Unit
            ImageRefusal.DAMAGED -> throw UnreadableFileException("image header announcing $width x $height")
            ImageRefusal.SIDE, ImageRefusal.PIXELS -> throw ImageTooLargeException(width, height)
        }
    }

    /**
     * `probeDimensions`: the width and height the first bytes of a PNG, GIF, BMP, WebP or JPEG announce,
     * without decoding a pixel; null for another format, or for too few bytes to read them.
     */
    fun probe(bytes: ByteArray): ImageSize? = HeaderProbe.of(bytes)

    /** An animation of [size] is played; larger, or of unknown size, its first frame shows ([FileCaps.ANIMATION_PIXELS]). */
    fun playable(size: ImageSize?): Boolean = size != null && size.width * size.height <= FileCaps.ANIMATION_PIXELS
}
