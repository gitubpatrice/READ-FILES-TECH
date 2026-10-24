package com.readfilestech.read_files_tech.data.panic

import com.readfilestech.read_files_tech.data.extractions.LegacyExtractions
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.trash.TrashStore
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.vault.VaultDirs
import com.readfilestech.read_files_tech.vault.VaultService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The panic — `PanicService.wipeAll` of the 2.15.3, in Settings: what the app keeps that tells what the user
 * had, erased at once, each step tried whatever the one before did, and a report of what was NOT erased —
 * "a report that reassures wrongly is the worst outcome of an emergency erasure" (the 2.15.3's own words).
 *
 * Wider than the 2.15.3 where it left plaintext behind: the WHOLE cache, not a list of names — the copies
 * its picker made there (`file_picker/`), and the port's own folders (an unlocked PDF, an archive's entry
 * shared, the scanner's pages); the trash's folders whole, entries it cannot read included; the old
 * "Extract all" folders (docs D-022, D16 of the inventory).
 *
 * Left alone, as in the 2.15.3: the user's files and the outputs on shared storage, a `.rftvault` saved
 * there — the way back, for the user who chose to keep one.
 */
@Singleton
class PanicService @Inject constructor(
    private val vault: VaultService,
    private val dirs: VaultDirs,
    private val prefs: FlutterPreferences,
    private val history: EditorHistory,
    private val trash: TrashStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** `app_flutter/` of the 2.15.x, the vault's parent, where its "Extract all" folders are. */
    private val appFlutter: File get() = requireNotNull(dirs.vault.parentFile)

    /** Each step, true only when what it erases is KNOWN gone. */
    data class Report(
        val vault: Boolean,
        val preferences: Boolean,
        val cache: Boolean,
        val history: Boolean,
        val extractions: Boolean,
        val trash: Boolean,
    ) {
        val isComplete: Boolean get() = vault && preferences && cache && history && extractions && trash
    }

    private val running = MutableStateFlow(false)

    /** A panic under way, whichever screen started it: a second one waits for it. */
    val erasing: StateFlow<Boolean> = running.asStateFlow()

    private val oneAtATime = Mutex()

    suspend fun wipeAll(): Report {
        // At once, before anything is dispatched: the key is wiped now, not once a busy thread is free.
        vault.lock()
        return oneAtATime.withLock {
            running.value = true
            try {
                withContext(io) { erase() }
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun erase() = Report(
        // The reset locks again, then waits for a creation under way, and erases the keys and the files;
        // refused, the files are deleted here all the same, and their keys go with the preferences.
        vault = attempt {
            vault.reset()
            true
        } || attempt { FileTrees.delete(dirs.vault) },
        // Every key, the recents among them, then read again: what counts is that nothing is left.
        preferences = attempt {
            prefs.clearAllExcept(emptySet())
            prefs.clearAllExcept(emptySet()) == 0
        },
        cache = attempt { emptied(dirs.cache) },
        // Both tried, whatever the first did.
        history = attempt { history.deleteLegacy() } and attempt { history.deleteNew() },
        extractions = attempt { extractionsGone() },
        trash = attempt { trash.eraseAll() },
    )

    /** [cache] itself kept, everything in it deleted; true when it is listed, and empty. */
    private fun emptied(cache: File): Boolean {
        cache.listFiles().orEmpty().forEach(FileTrees::delete)
        return cache.list()?.isEmpty() ?: !FileTrees.exists(cache)
    }

    /** True when `app_flutter/` holds no extraction folder — it listed, or it is not there at all. */
    private fun extractionsGone(): Boolean {
        val children = appFlutter.listFiles() ?: return !FileTrees.exists(appFlutter)
        children.filter(LegacyExtractions::isExtractionFolder).forEach(FileTrees::delete)
        return appFlutter.listFiles()?.none(LegacyExtractions::isExtractionFolder) ?: false
    }

    /**
     * One step: what it says, or false when it fails — a failure never keeps the next step from running. A
     * `CancellationException` thrown by blocking code is a failure too; only this coroutine's cancellation
     * stops the panic.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // reported as a step not done: the others still run
    private suspend inline fun attempt(step: () -> Boolean): Boolean = try {
        step()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (e: Exception) {
        false
    }
}
