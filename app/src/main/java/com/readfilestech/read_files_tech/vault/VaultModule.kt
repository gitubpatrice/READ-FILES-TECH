package com.readfilestech.read_files_tech.vault

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** The dispatcher the vault's file and crypto work runs on; tests pass their own. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class VaultModule {
    @Binds
    abstract fun clock(impl: SystemVaultClock): VaultClock

    @Binds
    abstract fun memory(impl: AndroidDeviceMemory): DeviceMemory

    @Binds
    abstract fun wakeAlarm(impl: AndroidWakeAlarm): WakeAlarm

    @Binds
    abstract fun argon2(impl: DeviceArgon2Policy): Argon2Policy

    companion object {
        /** The Flutter app's directories (path_provider: documents = `getDir("flutter")`, temporary = `cacheDir`). */
        @Provides
        @Singleton
        fun dirs(@ApplicationContext context: Context) = VaultDirs(
            vault = File(context.getDir("flutter", Context.MODE_PRIVATE), "vault"),
            cache = context.cacheDir,
        )

        @Provides
        @VaultDispatcher
        fun dispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
