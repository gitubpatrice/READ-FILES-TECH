package com.readfilestech.read_files_tech.ui.common

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.BatchResult
import com.readfilestech.read_files_tech.data.browse.FileActions
import com.readfilestech.read_files_tech.data.trash.TrashEntry
import com.readfilestech.read_files_tech.data.trash.TrashStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The changes the user asks of their files, run to the end whatever screen is on top ([ApplicationScope])
 * and reported through [UserMessages]. Every screen that shows files refreshes on [changes].
 */
@Singleton
class FileOperations @Inject constructor(
    private val actions: FileActions,
    private val trash: TrashStore,
    private val messages: UserMessages,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Something was created, renamed, moved or deleted: listings may be out of date. */
    val changes: SharedFlow<Unit> = changed.asSharedFlow()

    fun createFolder(parent: File, name: String) = single(R.string.error_create_folder) {
        actions.createFolder(parent, name)
    }

    fun rename(target: File, newName: String) = single(R.string.error_rename) { actions.rename(target, newName) }

    /** Soft deletion, with "Undo" on the message: no confirmation, it can be taken back (2.15.x). */
    fun moveToTrash(targets: List<File>) = launch {
        val entries = mutableListOf<TrashEntry>()
        var failed = 0
        var lastError: IOException? = null
        for (target in targets) {
            try {
                entries += trash.moveToTrash(target)
            } catch (e: IOException) {
                failed++
                lastError = e
            }
        }
        val text = when {
            targets.size == 1 && failed == 0 -> uiText(R.string.trashed_one, targets.single().name)
            targets.size == 1 -> uiText(R.string.error_trash, IoErrors.reasonOf(requireNotNull(lastError)))
            else -> BatchMessages.withFailures(UiText.Plural(R.plurals.trashed_many, entries.size), failed)
        }
        val undo = entries.takeIf { it.isNotEmpty() }?.let { trashed ->
            MessageAction(uiText(R.string.action_undo)) { restore(trashed, announce = false) }
        }
        messages.post(UserMessage(text, isError = entries.isEmpty(), action = undo))
    }

    /**
     * Puts trash entries back where they were. A failure is always said; a success only when
     * [announce]: the trash screen says "Restored: x" (perhaps "x (1)"), an "Undo" just undoes (2.15.x).
     */
    fun restore(entries: List<TrashEntry>, announce: Boolean = true) = launch {
        var failed = 0
        var restored: File? = null
        for (entry in entries) {
            try {
                restored = trash.restore(entry)
            } catch (expected: IOException) {
                failed++
            }
        }
        when {
            failed > 0 -> messages.error(UiText.Plural(R.plurals.restore_failed, failed))
            announce && entries.size == 1 -> messages.info(uiText(R.string.restored_one, requireNotNull(restored).name))
        }
    }

    /** Deletion for good; the screen confirmed it first (a red dialog, as the 2.15.x). */
    fun deleteForever(targets: List<File>) = launch {
        messages.post(BatchMessages.of(actions.deleteAll(targets), R.plurals.deleted_many))
    }

    /** Copy or move to [destDir]: files only, under free names, sources kept whole on failure. */
    fun transfer(sources: List<File>, destDir: File, move: Boolean) = launch {
        val result = actions.transfer(sources, destDir, move)
        messages.post(BatchMessages.of(result, if (move) R.plurals.moved_many else R.plurals.copied_many))
    }

    /** Trash entries deleted for good (the trash screen: one entry, or "empty the trash"). */
    fun deleteFromTrash(entries: List<TrashEntry>) = launch {
        val (deleted, failed) = trash.emptyAll(entries)
        messages.post(BatchMessages.of(BatchResult(deleted, failed), R.plurals.deleted_many))
    }

    /** One item, one action: silent on success (the listing shows it), its reason on failure. */
    private fun single(failure: Int, action: () -> Unit) = launch {
        try {
            action()
        } catch (e: IOException) {
            messages.error(uiText(failure, IoErrors.reasonOf(e)))
        }
    }

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                withContext(io) { block() }
            } finally {
                changed.tryEmit(Unit)
            }
        }
    }
}
