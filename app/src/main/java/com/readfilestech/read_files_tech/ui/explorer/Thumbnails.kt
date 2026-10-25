package com.readfilestech.read_files_tech.ui.explorer

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.readfilestech.read_files_tech.data.apps.ApkArchives
import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.images.ImageDecoding
import com.readfilestech.read_files_tech.data.images.ImageScale
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The small pictures of the explorer's image rows (`Image.file(cacheWidth: 72)` in the 2.15.x),
 * decoded at the size shown — never the full image — and kept for the folders visited; an APK's own
 * icon too (`ApkIcon` of the 2.15.x), read off the main thread where the 2.15.x parsed it on it (F36).
 */
@Singleton
class Thumbnails @Inject constructor(private val apks: ApkArchives, @param:IoDispatcher private val io: CoroutineDispatcher) {
    // Bounded in BYTES, a sixteenth of the heap: by count, a folder of tall screenshots kept 170 MB
    // pinned and could end the process — with every copy running in it (Claude review R5).
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / HEAP_SHARE).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    fun cached(file: File, modifiedMs: Long): ImageBitmap? = cache.get(key(file, modifiedMs))

    /** The thumbnail, or null when the file is not an image the app agrees to decode. */
    suspend fun load(file: File, modifiedMs: Long, sizePx: Int): ImageBitmap? {
        cached(file, modifiedMs)?.let { return it }
        return withContext(io) {
            decode(file, sizePx)?.also { cache.put(key(file, modifiedMs), it) }
        }
    }

    private suspend fun decode(file: File, sizePx: Int): ImageBitmap? = when (FileNames.extension(file.name)) {
        FileVisuals.APK -> apks.describe(file, sizePx)?.icon?.asImageBitmap()
        else -> decodeImage(file, sizePx)
    }

    /**
     * The viewer's decoder (ImageDecoding): the caps first — a file too large, or announcing too many
     * pixels, is never decoded — and the EXIF orientation applied, so a portrait photo is not on its side.
     * Refused, unreadable, or whatever a hostile file made the decoder throw (the viewers' guard): the row
     * shows the type's icon instead.
     */
    private suspend fun decodeImage(file: File, sizePx: Int): ImageBitmap? = loadOrFailure {
        ImageDecoding.still(file) { width, height -> ImageScale.coveringSample(width, height, sizePx, MAX_ELONGATION) }.asImageBitmap()
    }.getOrNull()

    private fun key(file: File, modifiedMs: Long) = "${file.path}|$modifiedMs"

    private companion object {
        const val HEAP_SHARE = 16

        /** A thumbnail's long side is at most this many times the size shown (a crop of its centre). */
        const val MAX_ELONGATION = 4
    }
}
