package com.readfilestech.read_files_tech.data.trash

import android.content.Context
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.files.StorageVisibility
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
    fun trash(@ApplicationContext context: Context, visibility: StorageVisibility) = TrashStore(
        names = SharedStorageNames(),
        fallbackBase = context.getDir("flutter", Context.MODE_PRIVATE),
        // The sandbox under both its names (`/data/data/<pkg>` is a link to `/data/user/0/<pkg>`). Not
        // `Android/data/<pkg>`: the 2.15.x kept its outputs there as a fallback, and a user may have
        // put one of them in the trash.
        privateRoots = listOf(context.dataDir, File("/data/data/${context.packageName}")),
        visibility = visibility,
        volumeBases = ::androidVolumeBases,
    )

    /**
     * `_existingTrashRoots` of the 2.15.x: the primary storage under its two names, then every entry of
     * `/storage` but `self` and `emulated` (removable volumes). A volume that cannot be listed is skipped.
     */
    private fun androidVolumeBases(): List<File> {
        val removable = File("/storage").listFiles().orEmpty().filter { it.name != "self" && it.name != "emulated" }
        return listOf(File("/storage/emulated/0"), File("/sdcard")) + removable
    }
}
