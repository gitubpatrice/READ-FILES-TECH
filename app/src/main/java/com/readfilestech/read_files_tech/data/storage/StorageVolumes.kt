package com.readfilestech.read_files_tech.data.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A mounted volume the explorer can open: the phone's storage, an SD card, a USB key. */
data class VolumeRoot(val dir: File, val label: String, val isPrimary: Boolean)

/**
 * The mounted volumes, from `StorageManager` (plan, point 17). The 2.15.x opened
 * `/storage/emulated/0` and nothing else: an SD card was reachable only by climbing the breadcrumb up
 * to `/storage`.
 */
@Singleton
class StorageVolumes @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(StorageManager::class.java)

    /** The primary volume first, then the others; a volume whose folder cannot be found is skipped. */
    fun list(): List<VolumeRoot> = roots()
        .distinctBy { it.path }
        .mapNotNull { dir ->
            val volume = runCatching { manager.getStorageVolume(dir) }.getOrNull() ?: return@mapNotNull null
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            VolumeRoot(dir, volume.getDescription(context), volume.isPrimary)
        }
        .sortedByDescending { it.isPrimary }

    /** The primary volume (`/storage/emulated/0` for the owner), where the explorer starts. */
    fun primary(): File = Environment.getExternalStorageDirectory()

    /**
     * Every volume's folder. `StorageVolume.getDirectory` exists from Android 11 only; below, each
     * volume is found through the app's own folder on it (`<volume>/Android/data/<pkg>/files`), the
     * documented way, which Android creates on demand.
     */
    private fun roots(): List<File> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.storageVolumes.mapNotNull { it.directory }
    } else {
        context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
            val path = appDir.path
            val marker = path.indexOf(APP_DATA)
            if (marker > 0) File(path.substring(0, marker)) else null
        }
    }

    private companion object {
        const val APP_DATA = "/Android/data/"
    }
}
