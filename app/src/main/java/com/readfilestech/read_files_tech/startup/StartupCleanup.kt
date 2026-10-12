package com.readfilestech.read_files_tech.startup

import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.valueOrNull
import com.readfilestech.read_files_tech.data.viewer.zip.ZipShares
import com.readfilestech.read_files_tech.vault.VaultBackups
import com.readfilestech.read_files_tech.vault.VaultDirs
import com.readfilestech.read_files_tech.vault.VaultService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app clears when it starts (port plan, phase 2, finding 5).
 *
 * ONCE, at the port's first start: the whole cache folder the Flutter app left. It holds plain copies —
 * vault files decrypted for a share the update interrupted, file_picker and image_picker copies with
 * their EXIF, `_no_exif` and `_signe` temporaries, ZIP entries shared under their own names (INVENTAIRE
 * §5.2) — and the 2.15.x never purged most of them. A list of patterns would miss the ZIP entries; and at
 * the port's first start nothing in that folder is the port's. The flag is set only when the folder was
 * listed AND found empty: a listing that fails is not an empty cache, and the next start tries again.
 *
 * At EVERY start, as the 2.15.x did, and whatever happened above: the decrypted copies
 * (VaultService.purgeDecrypted) and the staging folders of an interrupted restore
 * (VaultBackups.discardLeftovers); and the entries of archives shared by the port (ZipShares), which
 * the 2.15.x never purged (D16) — kept until then, another app may still be reading the last one.
 *
 * SYNCHRONOUS, before any component runs (RftApplication.onCreate): run in the background, it could
 * delete a file the port had just put in the cache, or miss one written meanwhile (GPT review R3,
 * 2026-10-11). Deleting is unlinking: a cache of a few hundred files takes milliseconds.
 */
@Singleton
class StartupCleanup @Inject constructor(
    private val prefs: FlutterPreferences,
    private val vault: VaultService,
    private val backups: VaultBackups,
    private val dirs: VaultDirs,
) {
    private var done = false

    /** What failed during this process's cleanup: each step runs whatever the others did (Claude review R3). */
    var failures: List<Exception> = emptyList()
        private set

    /**
     * Runs the cleanup once per process; later calls return at once. Never throws: a cleanup that fails
     * must not keep the app from starting — the failure is kept in [failures], and the next start tries again.
     */
    @Synchronized
    fun runOnce() {
        if (done) return
        done = true
        val failed = mutableListOf<Exception>()
        step(failed) { if (prefs.boolean(FLUTTER_CACHE_PURGED).valueOrNull() != true) purgeFlutterCache() }
        step(failed) { runBlocking { vault.purgeDecrypted() } }
        step(failed) { runBlocking { backups.discardLeftovers() } }
        step(failed) { ZipShares.under(dirs.cache).purge() }
        failures = failed
    }

    @Suppress("TooGenericExceptionCaught") // see runOnce: kept, never thrown at the app's start
    private inline fun step(failed: MutableList<Exception>, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            failed += e
        }
    }

    private fun purgeFlutterCache() {
        val children = dirs.cache.listFiles()
        if (children == null) {
            // ABSENT, nothing was left there. Present but not listed: unknown — the next start tries again.
            if (Files.notExists(dirs.cache.toPath(), LinkOption.NOFOLLOW_LINKS)) markPurged()
            return
        }
        children.forEach { FileTrees.delete(it) }
        val left = dirs.cache.list()
        if (left != null && left.isEmpty()) markPurged()
    }

    private fun markPurged() = prefs.commit { putBoolean(FLUTTER_CACHE_PURGED, true) }

    companion object {
        /** The port's own key, in the one preferences file (docs D-018); a panic erases it, which is harmless. */
        const val FLUTTER_CACHE_PURGED = "port_flutter_cache_purged_v1"
    }
}
