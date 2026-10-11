package com.readfilestech.read_files_tech.ui.explorer

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.readfilestech.read_files_tech.data.images.ImageBounds
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The small pictures of the explorer's image rows (`Image.file(cacheWidth: 72)` in the 2.15.x),
 * decoded at the size shown — never the full image — and kept for the folders visited.
 */
@Singleton
class Thumbnails @Inject constructor(@param:IoDispatcher private val io: CoroutineDispatcher) {
    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    fun cached(file: File, modifiedMs: Long): ImageBitmap? = cache.get(key(file, modifiedMs))

    /** The thumbnail, or null when the file is not an image the app agrees to decode. */
    suspend fun load(file: File, modifiedMs: Long, sizePx: Int): ImageBitmap? {
        cached(file, modifiedMs)?.let { return it }
        return withContext(io) {
            decode(file, sizePx)?.also { cache.put(key(file, modifiedMs), it) }
        }
    }

    /** The header first: a file too large, or announcing too many pixels, is never decoded. */
    private fun decode(file: File, sizePx: Int): ImageBitmap? {
        if (file.length() !in 1..ImageBounds.MAX_FILE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.takeIf { ImageBounds.fits(it.outWidth, it.outHeight) }?.let { header ->
            var sample = 1
            while (header.outWidth / (sample * 2) >= sizePx && header.outHeight / (sample * 2) >= sizePx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }

    private fun key(file: File, modifiedMs: Long) = "${file.path}|$modifiedMs"

    private companion object {
        const val CACHE_ENTRIES = 128
    }
}
