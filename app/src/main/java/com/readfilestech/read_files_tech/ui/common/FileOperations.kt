package com.readfilestech.read_files_tech.ui.common

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.BatchResult
import com.readfilestech.read_files_tech.data.browse.BulkRename
import com.readfilestech.read_files_tech.data.browse.FileActions
import com.readfilestech.read_files_tech.data.browse.RenameProposal
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.files.MediaIndex
import com.readfilestech.read_files_tech.data.files.filesUnder
import com.readfilestech.read_files_tech.data.trash.TrashEntry
import com.readfilestech.read_files_tech.data.trash.TrashStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The changes the user asks of their files, run to the end whatever screen is on top ([ApplicationScope])
 * and reported through [UserMessages]. Every screen that shows files refreshes on [changes].
 *
 * One at a time (Claude review R5): a restore and an "empty the trash" tapped together, or two moves into
 * one folder, never interleave; an "Undo" waits behind a running copy rather than racing it.
 *
 * Not covered: the process itself being frozen or killed in the background (no foreground service, as
 * the 2.15.x). Then nothing is lost — the trash writes its metadata first, a copy is published only
 * once whole, a source is deleted only after — but the batch stops where it was.
 *
 * Each change has the media index read again what it took away and what it brought ([MediaIndex]: up to
 * Android 10 a trashed or deleted photo stayed in the gallery, path and size readable by any app).
 */
@Singleton
class FileOperations @Inject constructor(
    private val actions: FileActions,
    private val trash: TrashStore,
    private val messages: UserMessages,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val mediaIndex: MediaIndex,
) {
    private val serial = Mutex()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Something was created, renamed, moved or deleted: listings may be out of date. */
    val changes: SharedFlow<Unit> = changed.asSharedFlow()

    fun createFolder(parent: File, name: String) = single(R.string.error_create_folder) {
        actions.createFolder(parent, name)
    }

    fun rename(target: File, newName: String) = single(R.string.error_rename) {
        val leaving = mediaIndex.filesUnder(listOf(target))
        val renamed = actions.rename(target, newName)
        mediaIndex.refresh(leaving + mediaIndex.filesUnder(listOf(renamed)))
    }

    /**
     * The bulk rename's batch, its names checked beforehand ([BulkRename.preview]): renamed one by one by
     * [FileActions.rename], a failure not stopping the others. The message says how many were renamed, and
     * how many were not, naming the first with its reason.
     */
    fun renameAll(proposals: List<RenameProposal>) = launch {
        val renaming = proposals.filter(RenameProposal::changes).map { File(it.entry.path) to it.newName }
        val leaving = mediaIndex.filesUnder(renaming.map { it.first })
        val report = BulkRename.apply(proposals, actions::rename)
        mediaIndex.refresh(leaving + mediaIndex.filesUnder(renaming.map { (file, name) -> File(file.absoluteFile.parentFile, name) }))
        val done = UiText.Plural(R.plurals.bulk_rename_done, report.renamed)
        val text = report.failures.firstOrNull()?.let { (file, error) ->
            val failed = report.failures.size
            val notRenamed = UiText.Plural(R.plurals.bulk_rename_not_renamed, failed, listOf(failed, file.name, IoErrors.reasonOf(error)))
            UiText.Joined(done, notRenamed, R.string.joined_dot)
        } ?: done
        messages.post(UserMessage(text, isError = report.failures.isNotEmpty()))
    }

    /**
     * Soft deletion, with "Undo" on the message: no confirmation, it can be taken back (2.15.x).
     * [onTrashed] gets the paths that did go to the trash (the duplicates screen drops them from its lists).
     */
    fun moveToTrash(targets: List<File>, onTrashed: (List<String>) -> Unit = {}) = launch {
        val entries = mutableListOf<TrashEntry>()
        var failed = 0
        var lastError: IOException? = null
        val leaving = mediaIndex.filesUnder(targets)
        for (target in targets) {
            try {
                entries += trash.moveToTrash(target)
            } catch (e: IOException) {
                failed++
                lastError = e
            }
        }
        mediaIndex.refresh(leaving)
        onTrashed(entries.map(TrashEntry::originalPath))
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
        var lastError: IOException? = null
        val restored = mutableListOf<File>()
        for (entry in entries) {
            try {
                restored += trash.restore(entry)
            } catch (e: IOException) {
                failed++
                lastError = e
            }
        }
        mediaIndex.refresh(mediaIndex.filesUnder(restored))
        when {
            // One item: why, as a failed move to the trash says it (coherence audit, phase 9).
            entries.size == 1 && failed == 1 -> messages.error(uiText(R.string.error_restore, IoErrors.reasonOf(requireNotNull(lastError))))
            failed > 0 -> messages.error(UiText.Plural(R.plurals.restore_failed, failed))
            announce && entries.size == 1 -> messages.info(uiText(R.string.restored_one, restored.single().name))
        }
    }

    /** Deletion for good; the screen confirmed it first (a red dialog, as the 2.15.x). */
    fun deleteForever(targets: List<File>) = launch {
        val leaving = mediaIndex.filesUnder(targets)
        val result = actions.deleteAll(targets)
        mediaIndex.refresh(leaving)
        messages.post(BatchMessages.of(result, R.plurals.deleted_many))
    }

    /** Copy or move to [destDir]: files only, under free names, sources kept whole on failure. */
    fun transfer(sources: List<File>, destDir: File, move: Boolean) = launch {
        val leaving = if (move) mediaIndex.filesUnder(sources.filter(FileTrees::isRegularFile)) else emptyList()
        val result = actions.transfer(sources, destDir, move)
        mediaIndex.refresh(leaving + result.written.map(File::getPath))
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
                serial.withLock { withContext(io) { block() } }
            } finally {
                changed.tryEmit(Unit)
            }
        }
    }
}
