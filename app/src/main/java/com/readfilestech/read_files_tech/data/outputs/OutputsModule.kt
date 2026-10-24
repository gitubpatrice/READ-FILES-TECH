package com.readfilestech.read_files_tech.data.outputs

import android.content.Context
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.storage.StorageVolumes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OutputsModule {
    /** The folders of the 2.15.x: the user's primary volume, then `getExternalStorageDirectory()` and documents of path_provider. */
    @Provides
    @Singleton
    fun outputs(@ApplicationContext context: Context, prefs: FlutterPreferences, volumes: StorageVolumes) = OutputStorage(
        prefs = prefs,
        primary = volumes::primary,
        appExternal = { context.getExternalFilesDir(null) },
        appPrivate = context.getDir("flutter", Context.MODE_PRIVATE),
        folder = SharedStorageNames().filesTechDir,
    )

    @Provides
    @Singleton
    fun outputSettings(prefs: FlutterPreferences, outputs: OutputStorage, volumes: StorageVolumes) =
        OutputSettings(prefs, outputs) { volumes.list().map { it.dir } }
}
