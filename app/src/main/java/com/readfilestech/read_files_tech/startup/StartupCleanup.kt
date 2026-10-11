package com.readfilestech.read_files_tech.startup

import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.valueOrNull
import com.readfilestech.read_files_tech.vault.VaultBackups
import com.readfilestech.read_files_tech.vault.VaultDirs
import com.readfilestech.read_files_tech.vault.VaultDispatcher
import com.readfilestech.read_files_tech.vault.VaultService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app clears when it starts (port plan, phase 2, finding 5).
 *
 * ONCE, at the port's first start: the whole cache folder the Flutter app left. It holds plain copies —
 * vault files decrypted for a share the update interrupted, file_picker and image_picker copies with
 * their EXIF, `_no_exif` and `_signe` temporaries, ZIP entries shared under their own names (INVENTAIRE
 * §5.2) — and the 2.15.x never purged most of them. A list of patterns would miss the ZIP entries; and at
 * the port's first start nothing in that folder is the port's. If anything resists, the flag is not set
 * and the next start tries again.
 *
 * At EVERY start, as the 2.15.x did: the decrypted copies (VaultService.purgeDecrypted) and the staging
 * folders of an interrupted restore (VaultBackups.discardLeftovers).
 */
@Singleton
class StartupCleanup @Inject constructor(
    private val prefs: FlutterPreferences,
    private val vault: VaultService,
    private val backups: VaultBackups,
    private val dirs: VaultDirs,
    @VaultDispatcher dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val run: Deferred<Unit> by lazy { scope.async { cleanUp() } }

    /** Starts the cleanup once per process (RftApplication); later calls change nothing. */
    fun start() {
        run.start()
    }

    /** Waits for it: whatever lists or reads the cache comes after. Throws what the cleanup threw. */
    suspend fun await() = run.await()

    private suspend fun cleanUp() {
        if (prefs.boolean(FLUTTER_CACHE_PURGED).valueOrNull() != true) {
            dirs.cache.listFiles().orEmpty().forEach { FileTrees.delete(it) }
            if (dirs.cache.list().isNullOrEmpty()) prefs.commit { putBoolean(FLUTTER_CACHE_PURGED, true) }
        }
        vault.purgeDecrypted()
        backups.discardLeftovers()
    }

    companion object {
        /** The port's own key, in the one preferences file (docs D-018); a panic erases it, which is harmless. */
        const val FLUTTER_CACHE_PURGED = "port_flutter_cache_purged_v1"
    }
}
