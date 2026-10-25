package com.readfilestech.read_files_tech.data.files

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject

/**
 * The system's index of media files (MediaStore), told of the files the app changed by their path. Up to
 * Android 10 such a change did not reach it: a photo moved into the vault or to the trash, deleted, moved or
 * renamed kept its name, path and size in the index — read by any app with storage access, shown by the
 * gallery — until a full rescan (measured on the S9, API 29), and a copy or a restored file stayed out of it
 * (motif audit, phase 9: only the vault's folder import told it). From Android 11 the file system tells the
 * index itself.
 */
fun interface MediaIndex {
    /** The files at [paths] left or arrived: each is read again — gone, it leaves the index; there, it enters it. */
    fun refresh(paths: List<String>)

    /** Whether [refresh] has anything to do; if not, the files a change touches need not even be listed. */
    val needsPaths: Boolean get() = true
}

/** The paths of the files under [roots], for [MediaIndex.refresh]; none listed when nothing needs them. */
fun MediaIndex.filesUnder(roots: List<File>): List<String> =
    if (needsPaths) roots.flatMap(FileTrees::regularFiles).map(File::getPath) else emptyList()

class AndroidMediaIndex @Inject constructor(@ApplicationContext private val context: Context) : MediaIndex {
    // From Android 11 the file system keeps the index itself.
    override val needsPaths: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    override fun refresh(paths: List<String>) {
        // A path scanned is read again: one that no longer exists leaves the index, one that does enters it.
        if (paths.isEmpty() || !needsPaths) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaIndexModule {
    @Binds
    abstract fun mediaIndex(impl: AndroidMediaIndex): MediaIndex
}
