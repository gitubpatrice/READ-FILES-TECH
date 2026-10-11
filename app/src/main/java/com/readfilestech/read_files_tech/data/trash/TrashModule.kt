package com.readfilestech.read_files_tech.data.trash

import android.annotation.SuppressLint
import android.content.Context
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.files.StorageVisibility
import com.readfilestech.read_files_tech.data.storage.StorageVolumes
import com.readfilestech.read_files_tech.data.storage.VolumeRoot
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TrashModule {
    /** The trash where the 2.15.x keeps it: each volume, then `app_flutter/` (path_provider's documents). */
    @Provides
    @Singleton
    fun trash(@ApplicationContext context: Context, visibility: StorageVisibility, volumes: StorageVolumes) = TrashStore(
        names = SharedStorageNames(),
        fallbackBase = context.getDir("flutter", Context.MODE_PRIVATE),
        // The sandbox under both its names (`/data/data/<pkg>` is a link to `/data/user/0/<pkg>`). Not
        // `Android/data/<pkg>`: the 2.15.x kept its outputs there as a fallback, and a user may have
        // put one of them in the trash.
        privateRoots = listOf(context.dataDir, File("/data/data/${context.packageName}")),
        visibility = visibility,
        volumeBases = { volumeBases(volumes) },
    )

    /**
     * The primary storage under its two names, then every mounted volume as StorageManager knows it.
     *
     * `_existingTrashRoots` of the 2.15.x listed `/storage` instead, and so did this module until phase 3:
     * there `/storage` is `drwx--x---` (group `everybody`: traverse, not list), `listFiles()` gives null,
     * and the trash of an SD card was never shown — measured on the API 34 emulator with a virtual card,
     * the entry written, the screen empty.
     */
    @SuppressLint("SdCardPath") // `/sdcard` is the real alias the 2.15.x used; recognised, not built.
    private fun volumeBases(volumes: StorageVolumes): List<File> =
        listOf(File("/storage/emulated/0"), File("/sdcard")) + volumes.list().map(VolumeRoot::dir)
}
