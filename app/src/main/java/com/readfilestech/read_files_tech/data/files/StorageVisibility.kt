package com.readfilestech.read_files_tech.data.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

/**
 * Whether this app sees EVERY file of shared storage: all-files access from Android 11, the read
 * permission before. Without it the files of other apps are HIDDEN — not gone — and `Files.exists`
 * says false for them as for a deleted one (Claude review R3).
 */
fun interface StorageVisibility {
    fun seesEverything(): Boolean
}

/**
 * [file] is KNOWN to be gone — the positive finding docs D-019 asks before any inherited data is cleaned
 * up: everything visible, its folder there (not an SD card taken out), and the file itself confirmed
 * absent (`Files.notExists`, which, unlike `!exists`, is false when it cannot tell).
 */
fun StorageVisibility.isGone(file: File): Boolean {
    val folder = file.absoluteFile.parentFile ?: return false
    return seesEverything() &&
        Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS) &&
        Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}

class AndroidStorageVisibility @Inject constructor(@ApplicationContext private val context: Context) : StorageVisibility {
    override fun seesEverything(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageVisibilityModule {
    @Binds
    abstract fun visibility(impl: AndroidStorageVisibility): StorageVisibility
}
