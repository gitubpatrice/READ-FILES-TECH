package com.readfilestech.read_files_tech.data.legacy

import com.readfilestech.read_files_tech.data.extractions.LegacyExtractions
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the 2.x left that the port does not take up by itself (docs D-021, D-022): its "Extract all" folders,
 * private to the app, and its editor history, which the editor shows apart. The user moves or deletes them —
 * from a notice shown once, or from the settings —, one operation at a time whichever screen asked.
 * Blocking: called off the main thread.
 */
@Singleton
class LegacyData @Inject constructor(
    private val extractions: LegacyExtractions,
    private val history: EditorHistory,
    private val prefs: FlutterPreferences,
) {
    /** What is left: the old extraction folders, the old history's copies. */
    data class Summary(val extractions: List<LegacyExtractions.Folder>, val history: List<HistoryCopy>) {
        val extractionFiles: Int get() = extractions.sumOf { it.files }
        val extractionBytes: Long get() = extractions.sumOf { it.bytes }
        val historyBytes: Long get() = history.sumOf { it.size }
        val isEmpty: Boolean get() = extractions.isEmpty() && history.isEmpty()
    }

    /**
     * What moving the old extractions did: files moved and verified, files left in the private folder, where the
     * folders went ([destination], null when none was made) and what stopped it, if anything.
     */
    data class Moved(val moved: Int, val left: Int, val destination: File?, val stoppedBy: IOException?)

    private val oneAtATime = Mutex()
    private val running = MutableStateFlow(false)

    /** A move or a deletion under way, whichever screen started it. */
    val working: StateFlow<Boolean> = running.asStateFlow()

    private val ended = MutableStateFlow(0)

    /**
     * How many moves and deletions ended: each new count, what is left changed. [working] would not say it — a
     * quick one goes from true back to false before a collector on another thread runs, and the state flow
     * conflates the two away.
     */
    val operationsEnded: StateFlow<Int> = ended.asStateFlow()

    fun summary(): Summary = Summary(extractions.find(), history.legacyAll())

    /**
     * Whether to offer, at this launch, what to do with the old extractions: once in the app's life, claimed
     * BEFORE it shows, as the storage request is — "Later" is not asked again by itself, the settings keep the
     * choice. Nothing to offer claims it all the same (the port never makes such a folder); a value that cannot
     * be read, or written, counts as claimed: never a notice at every launch.
     */
    fun claimNotice(): Boolean {
        val claimed = prefs.boolean(KEY_NOTICE) == PrefRead.Absent && prefs.commit { putBoolean(KEY_NOTICE, true) }
        return claimed && extractions.find().isNotEmpty()
    }

    /** Moves every old extraction, one after the other; the first that stops — no space left — stops the rest. */
    suspend fun moveExtractions(): Moved = exclusive {
        var total = Moved(moved = 0, left = 0, destination = null, stoppedBy = null)
        for (folder in extractions.find()) {
            total = if (total.stoppedBy == null) total + moveOne(folder) else total.copy(left = total.left + folder.files)
        }
        total
    }

    /** Deletes every old extraction; true when none is left. */
    suspend fun deleteExtractions(): Boolean = exclusive {
        extractions.find().forEach { orFalse { extractions.delete(it) } }
        extractions.find().isEmpty()
    }

    /** Deletes the old history, the whole `history/` of the 2.15.x — the port's own stays; true when it is gone. */
    suspend fun deleteHistory(): Boolean = exclusive { orFalse(history::deleteLegacy) && history.legacyAll().isEmpty() }

    private fun moveOne(folder: LegacyExtractions.Folder): Moved = try {
        val moved = extractions.move(folder)
        Moved(moved.moved, moved.left, moved.destination.parentFile, moved.stoppedBy)
    } catch (e: IOException) {
        // No folder to move it into: nothing of it moved.
        Moved(moved = 0, left = folder.files, destination = null, stoppedBy = e)
    }

    private operator fun Moved.plus(next: Moved) =
        Moved(moved + next.moved, left + next.left, next.destination ?: destination, next.stoppedBy)

    private suspend fun <T> exclusive(work: () -> T): T = oneAtATime.withLock {
        running.value = true
        try {
            work()
        } finally {
            running.value = false
            ended.update { it + 1 }
        }
    }

    private fun orFalse(delete: () -> Boolean): Boolean = try {
        delete()
    } catch (expected: IOException) {
        false
    }

    companion object {
        /** Whether the notice of the old extractions was claimed (bool), a key of the port's own. */
        const val KEY_NOTICE = "legacy_extractions_offered"
    }
}
