package com.readfilestech.read_files_tech.data.images

import kotlin.math.roundToInt

/** What the image tools write, through the platform's encoder — which writes no EXIF, no XMP of its own. */
enum class ImageFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
}

/**
 * The rules of the two image tools, Compress and Remove EXIF (`compress_screen.dart`, `exif_service.dart` of
 * the 2.15.3) — pure, tested on the JVM; [ImageExport] applies them to pixels.
 */
object ImageToolRules {
    /**
     * What Compress takes: what the platform decodes (HEIC and HEIF from Android 9). The 2.15.3 took any
     * picture its picker offered and failed on HEIC, which its `image` package does not read.
     */
    val COMPRESS_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")

    /**
     * What Remove EXIF takes, and what the explorer offers it on: the formats that carry EXIF. The 2.15.3's
     * explorer offered it on GIF and BMP too, which carry none, and turned them into a JPEG (an animated GIF
     * into its first frame).
     */
    val METADATA_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    const val QUALITY_MIN = 10
    const val QUALITY_MAX = 100
    const val QUALITY_DEFAULT = 80
    const val WIDTH_MIN = 480
    const val WIDTH_MAX = 4096
    const val WIDTH_DEFAULT = 1920

    /** The positions of each slider between its ends: 18 intervals, as the 2.15.3's `divisions: 18`. */
    const val SLIDER_STEPS = 17

    /** The JPEG quality of a copy without EXIF (`stripExif(jpegQuality: 95)` of the 2.15.3). */
    const val STRIP_QUALITY = 95

    /** The name an output starts with: the source's, without its last extension (the 2.15.3's `\.[^.]+$`). */
    fun baseName(fileName: String): String = fileName.replace(LAST_EXTENSION, "")

    /**
     * The format of a copy without EXIF: a PNG stays a PNG, and so does any picture with transparency (a
     * WebP, which the 2.15.3 turned into a JPEG with black where it was transparent); everything else is a
     * JPEG — the platform writes no HEIC, and a lossy WebP rewritten lossless would grow several times.
     */
    fun strippedFormat(extension: String, hasAlpha: Boolean): ImageFormat =
        if (extension.lowercase() == "png" || hasAlpha) ImageFormat.PNG else ImageFormat.JPEG

    /**
     * The size of a compressed copy: [width] brought down to [maxWidth], the height in proportion (never
     * below 1 pixel); a picture already narrower keeps its size — the 2.15.3 never enlarged one either.
     */
    fun fitWidth(width: Int, height: Int, maxWidth: Int): ImageSize =
        if (width <= maxWidth) {
            ImageSize(width.toLong(), height.toLong())
        } else {
            ImageSize(maxWidth.toLong(), maxOf(1, (height.toDouble() * maxWidth / width).roundToInt()).toLong())
        }

    /**
     * How much smaller the copy is, in whole percent; null when it is not smaller — the 2.15.3 showed "-0 %"
     * for a copy LARGER than its source (`clamp(0, 99)`), which hid it.
     */
    fun reductionPercent(before: Long, after: Long): Int? =
        if (before <= 0 || after >= before) null else ((before - after) * PERCENT / before).toInt()

    private const val PERCENT = 100
    private val LAST_EXTENSION = Regex("\\.[^.]+$")
}
