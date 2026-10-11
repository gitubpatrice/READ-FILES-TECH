package com.readfilestech.read_files_tech.data.sharing

import android.content.Context
import android.os.Environment
import com.readfilestech.read_files_tech.vault.VaultService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SharingModule {
    /**
     * What may leave the app: shared storage (every volume, `/storage`) and the app's own folder on it.
     * The 2.15.x also listed its private `files/` and `cache/`; file_paths.xml serves neither, so they
     * could never actually leave — they are not listed. The vault's plaintext cache folders are refused
     * by name, the same list the purge empties (`VaultService.PLAINTEXT_CACHE_DIRS`).
     */
    @Provides
    @Singleton
    fun pathGuard(@ApplicationContext context: Context): PathGuard = PathGuard(
        allowedRoots = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            File("/storage"),
            context.getExternalFilesDir(null),
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() },
        blockedDirs = VaultService.PLAINTEXT_CACHE_DIRS.map { File(context.cacheDir.canonicalFile, it) },
        packageName = context.packageName,
    )
}
