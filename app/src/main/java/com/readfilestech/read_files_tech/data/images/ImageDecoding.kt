package com.readfilestech.read_files_tech.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.readfilestech.read_files_tech.data.files.CappedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import java.io.File
import java.io.FileInputStream

/** A decoded image, upright and subsampled. */
sealed interface DecodedImage {
    class Still(val bitmap: Bitmap) : DecodedImage

    /** An animated GIF or WebP that Android plays (API 28+): a [Drawable] that is also [Animatable]. */
    class Animated(val drawable: Drawable) : DecodedImage
}

/**
 * The app's one image decoder — the viewer's pages and the explorer's thumbnails. In this order, each
 * step before the next costs anything: the file's size ([FileCaps.IMAGE_FILE]); the dimensions its first
 * bytes announce ([ImageBounds.probe]); the dimensions the platform's decoder reads from the header,
 * checked by the same rule before it allocates a pixel; then a decode subsampled by `sampleFor`
 * ([ImageScale]), EXIF orientation applied. The 2.15.3 viewer handed the file to `Image.file` with no cap.
 *
 * Every refusal is an IOException: [com.readfilestech.read_files_tech.data.files.FileTooLargeException],
 * [ImageTooLargeException], [UnreadableFileException] (empty, damaged, not an image at all).
 */
object ImageDecoding {
    /**
     * The image of [file], animated when it is an animation Android plays, [animate] asks it, and it is
     * [ImageBounds.playable] — the viewer. Its pixels may sit in graphics memory (`ImageDecoder`'s
     * default): a page shows one at a time.
     *
     * @param sampleFor the power of two to subsample by, for the upright width and height.
     */
    fun decode(file: File, animate: Boolean, sampleFor: (width: Int, height: Int) -> Int): DecodedImage {
        val size = checkHeader(file)
        // An animation is decoded at full size whatever the subsampling: past the cap, its first frame only.
        val play = animate && ImageBounds.playable(size)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Platform.decode(file, play, inSoftware = false, sampleFor)
        } else {
            DecodedImage.Still(Legacy.decode(file, sampleFor))
        }
    }

    /**
     * The still image of [file] — an animation's first frame — in software memory: the thumbnails, kept
     * by the hundred in a cache, where a bitmap in graphics memory would hold a file descriptor each.
     */
    fun still(file: File, sampleFor: (width: Int, height: Int) -> Int): Bitmap {
        checkHeader(file)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (Platform.decode(file, animate = false, inSoftware = true, sampleFor) as DecodedImage.Still).bitmap
        } else {
            Legacy.decode(file, sampleFor)
        }
    }

    /** The size [file]'s header announces, checked; null when the probe cannot tell (the decoder's is checked after). */
    private fun checkHeader(file: File): ImageSize? {
        FileCaps.check(file, FileCaps.IMAGE_FILE)
        val header = CappedInputStream(FileInputStream(file), ImageBounds.HEADER_BYTES.toLong()).use { it.readBytes() }
        if (header.isEmpty()) throw UnreadableFileException("empty image")
        return ImageBounds.probe(header)?.also { ImageBounds.check(it.width, it.height) }
    }

    /** API 28+: `ImageDecoder`, which applies the EXIF orientation and plays GIF and WebP animations. */
    @RequiresApi(Build.VERSION_CODES.P)
    private object Platform {
        fun decode(file: File, animate: Boolean, inSoftware: Boolean, sampleFor: (Int, Int) -> Int): DecodedImage {
            val source = ImageDecoder.createSource(file)
            // Called with the header read, before any pixel is allocated: what it throws ends the decode.
            val onHeader = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                val size = info.size
                ImageBounds.check(size.width.toLong(), size.height.toLong())
                decoder.setTargetSampleSize(sampleFor(size.width, size.height))
                if (inSoftware) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // A truncated file shows what it holds, the rest blank — as BitmapFactory did, and does below API 28.
                decoder.setOnPartialImageListener { it.error == ImageDecoder.DecodeException.SOURCE_INCOMPLETE }
            }
            return try {
                if (animate) {
                    when (val drawable = ImageDecoder.decodeDrawable(source, onHeader)) {
                        is BitmapDrawable -> DecodedImage.Still(drawable.bitmap)
                        is Animatable -> DecodedImage.Animated(drawable)
                        else -> throw UnreadableFileException("unexpected drawable ${drawable.javaClass.name}")
                    }
                } else {
                    DecodedImage.Still(ImageDecoder.decodeBitmap(source, onHeader))
                }
            } catch (e: ImageDecoder.DecodeException) {
                throw UnreadableFileException("not decoded (${e.error})", e)
            }
        }
    }

    /** API 26 and 27: `BitmapFactory`, the first frame of an animation, the EXIF orientation applied here. */
    private object Legacy {
        fun decode(file: File, sampleFor: (Int, Int) -> Int): Bitmap {
            val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, header)
            if (header.outWidth <= 0 || header.outHeight <= 0) throw UnreadableFileException("no image header")
            // EXIF in a JPEG, a PNG or a WebP; none in a GIF or a BMP.
            val carriesExif = header.outMimeType?.let(ExifInterface::isSupportedMimeType) == true
            val orientation = if (carriesExif) orientationOf(file) else ExifOrientation.UPRIGHT
            val (width, height) = if (orientation.swapsSides) header.outHeight to header.outWidth else header.outWidth to header.outHeight
            ImageBounds.check(width.toLong(), height.toLong())
            val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(width, height) }
            val stored = BitmapFactory.decodeFile(file.path, options) ?: throw UnreadableFileException("not decoded")
            return upright(stored, orientation)
        }

        // An EXIF parser fed a malformed file may throw more than IOException (an index out of bounds, a
        // number that is not one): the orientation is a comfort, the image shows without it.
        @Suppress("TooGenericExceptionCaught")
        private fun orientationOf(file: File): ExifOrientation = try {
            ExifOrientation.of(ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        } catch (expected: Exception) {
            ExifOrientation.UPRIGHT
        }

        private fun upright(stored: Bitmap, orientation: ExifOrientation): Bitmap {
            if (orientation == ExifOrientation.UPRIGHT) return stored
            // The mirror first, then the rotation: the order ExifOrientation is defined in.
            val matrix = Matrix().apply {
                if (orientation.mirrored) postScale(-1f, 1f)
                postRotate(orientation.degrees.toFloat())
            }
            val turned = Bitmap.createBitmap(stored, 0, 0, stored.width, stored.height, matrix, true)
            if (turned !== stored) stored.recycle()
            return turned
        }
    }
}
