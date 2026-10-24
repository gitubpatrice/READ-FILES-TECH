package com.readfilestech.read_files_tech.data.files

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * The system's index of media files (MediaStore), told of files the app deleted by their path. Up to
 * Android 10 such a deletion did not reach it: a photo moved into the vault kept its name, path and size
 * in the index — read by any app with storage access, shown by the gallery — until a full rescan (measured
 * on the S9, API 29). From Android 11 the file system tells the index itself.
 */
fun interface MediaIndex {
    /** [paths] no longer exist: their entries leave the index. */
    fun forget(paths: List<String>)
}

class AndroidMediaIndex @Inject constructor(@ApplicationContext private val context: Context) : MediaIndex {
    override fun forget(paths: List<String>) {
        // A path scanned that no longer exists is removed from the index; from Android 11 nothing is left to do.
        if (paths.isEmpty() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaIndexModule {
    @Binds
    abstract fun mediaIndex(impl: AndroidMediaIndex): MediaIndex
}
