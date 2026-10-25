package com.readfilestech.read_files_tech.data.history

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HistoryModule {
    /**
     * The editors' history (docs D-021): the 2.15.x's `app_flutter/history/` (path_provider's documents are
     * `getDir("flutter")`), and the port's own beside it, OUT of `app_flutter/` — `files/editor_history/`.
     */
    @Provides
    @Singleton
    fun history(@ApplicationContext context: Context) = EditorHistory(
        legacyDir = File(context.getDir("flutter", Context.MODE_PRIVATE), "history"),
        newHistoryDir = File(context.filesDir, "editor_history"),
    )
}
