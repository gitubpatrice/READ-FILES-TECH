package com.readfilestech.read_files_tech.ui.common

import android.util.Log
import com.readfilestech.read_files_tech.R
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The scope of work that must finish even when its screen is gone — a copy, a move, a restore: under a
 * screen's `viewModelScope`, leaving the screen would cancel a copy half-way. One failure never cancels
 * the others (SupervisorJob).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** The dispatcher for the user's files (blocking I/O); tests pass their own. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {
    /**
     * An exception nothing caught ends its own task only, and is said: without the handler it would
     * reach the thread and kill the process, the user's other copies with it.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(messages: UserMessages): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            // The class only: a message would carry the user's file names into the system log.
            Log.e("RftOperations", "operation failed: ${error.javaClass.name}")
            messages.error(uiText(R.string.error_unexpected))
        },
    )

    @Provides
    @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO
}
