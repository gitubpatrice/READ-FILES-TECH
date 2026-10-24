package com.readfilestech.read_files_tech.data.extractions

import android.content.Context
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExtractionsModule {
    /**
     * The 2.15.x's "Extract all" folders, in `app_flutter/` (path_provider's documents are `getDir("flutter")`),
     * moved where the ZIP viewer extracts now — on shared storage only (docs D-022).
     */
    @Provides
    @Singleton
    fun legacyExtractions(@ApplicationContext context: Context, outputs: OutputStorage) = LegacyExtractions(
        appFlutter = context.getDir("flutter", Context.MODE_PRIVATE),
        extractionsRoot = { outputs.folderFor(OutputCategory.EXTRACTIONS, noFallback = true) },
    )
}
