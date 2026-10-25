package com.readfilestech.read_files_tech.ui.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.NameOrder
import com.readfilestech.read_files_tech.data.files.MediaIndex
import com.readfilestech.read_files_tech.data.search.FolderWalk
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessage
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import com.readfilestech.read_files_tech.vault.NameTakenException
import com.readfilestech.read_files_tech.vault.VaultFiles
import com.readfilestech.read_files_tech.vault.VaultLockedException
import com.readfilestech.read_files_tech.vault.VaultService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import javax.inject.Inject

/** A file of the folder, and whether it is to be encrypted. */
data class FolderEntry(val path: String, val relative: String, val size: Long, val selected: Boolean = true)

data class FolderImportState(
    val folder: String,
    val recursive: Boolean = true,
    val deleteOriginals: Boolean = false,
    val scanning: Boolean = true,
    val entries: List<FolderEntry> = emptyList(),
    val capReached: Boolean = false,
    val unreadable: Boolean = false,
    /** The share done, from 0 to 1, while it encrypts; null otherwise. */
    val progress: Float? = null,
    /** "Stop" asked: the file under way is finished, then the screen goes. */
    val stopping: Boolean = false,
    /** How many originals "Delete the originals afterwards" is about to delete: the confirmation asked. */
    val confirmDelete: Int? = null,
    /** Finished, or the vault locked: the screen goes back to the vault's. */
    val done: Boolean = false,
) {
    val selected: List<FolderEntry> get() = entries.filter(FolderEntry::selected)
}

/**
 * The files of a folder, chosen one by one, encrypted into the open vault (`VaultImportFolderScreen` of the
 * 2.15.3): its subfolders or not, links never followed, the system's folders left out, 5 000 files at most;
 * "Delete the originals afterwards" off by default, confirmed before it runs, and applied to an original only
 * once ITS encrypted copy is in the vault under the live key.
 *
 * D1 of the 2.15.2 (docs D-013): a lock during the import encrypted the rest under a zeroed key, then deleted
 * the originals. Here the lock stops the import at once (VaultFiles' epoch, LockedDuringOperationException):
 * the file under way is not written, its original and every one after it stay, and it is said.
 */
// One function per action of the screen: the choice of files, the options, encrypt and stop — its surface.
@Suppress("TooManyFunctions")
@HiltViewModel
class VaultImportFolderViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val vault: VaultService,
    private val files: VaultFiles,
    private val messages: UserMessages,
    private val mediaIndex: MediaIndex,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FolderImportState(folder = handle.get<String>(Routes.ARG_PATH).orEmpty()))
    val state: StateFlow<FolderImportState> = mutableState.asStateFlow()

    private var scanning: Job? = null
    private var running: Job? = null

    /** "Stop" asked: the file being encrypted is finished, the next ones are not started (docs D-036: nothing is lost). */
    @Volatile
    private var stopAsked = false

    init {
        scan()
        viewModelScope.launch {
            // Locked while it shows: back to the vault, which asks for the password. A run stops by itself.
            vault.unlocked.collect { open -> if (!open && running?.isActive != true) mutableState.update { it.copy(done = true) } }
        }
    }

    fun setRecursive(recursive: Boolean) {
        if (running?.isActive == true) return
        mutableState.update { it.copy(recursive = recursive) }
        scan()
    }

    fun setDeleteOriginals(delete: Boolean) {
        if (running?.isActive != true) mutableState.update { it.copy(deleteOriginals = delete) }
    }

    fun toggle(path: String) {
        if (running?.isActive == true) return
        mutableState.update { state ->
            state.copy(entries = state.entries.map { if (it.path == path) it.copy(selected = !it.selected) else it })
        }
    }

    fun selectAll(selected: Boolean) {
        if (running?.isActive == true) return
        mutableState.update { state -> state.copy(entries = state.entries.map { it.copy(selected = selected) }) }
    }

    /**
     * Stops the encryption after the file under way, which is never left half-written: the originals not yet
     * encrypted stay as they are, and importing the folder again goes on, the files already in the vault skipped.
     * The 2.15.3 could not stop it, and the port's screen swallowed the back key without a word.
     */
    fun stop() {
        if (running?.isActive != true) return
        stopAsked = true
        mutableState.update { it.copy(stopping = true) }
    }

    /** "Encrypt": at once, or after the confirmation that the originals will be deleted. */
    fun encrypt() {
        val current = mutableState.value
        if (running?.isActive == true || current.scanning || current.selected.isEmpty()) return
        if (current.deleteOriginals) mutableState.update { it.copy(confirmDelete = current.selected.size) } else run(current)
    }

    fun confirmDelete(yes: Boolean) {
        // A second tap before the dialog is gone: one run only.
        if (mutableState.value.confirmDelete == null) return
        mutableState.update { it.copy(confirmDelete = null) }
        if (yes) run(mutableState.value)
    }

    private fun scan() {
        scanning?.cancel()
        val folder = mutableState.value.folder
        val recursive = mutableState.value.recursive
        mutableState.update { it.copy(scanning = true, entries = emptyList(), capReached = false, unreadable = false) }
        scanning = viewModelScope.launch {
            val found = loadOrFailure { withContext(io) { FolderScan.list(File(folder), recursive) } }
            mutableState.update { state ->
                found.fold(
                    { state.copy(scanning = false, entries = it.entries, capReached = it.capReached) },
                    { state.copy(scanning = false, unreadable = true) },
                )
            }
        }
    }

    private fun run(state: FolderImportState) {
        val chosen = state.selected
        val deleteOriginals = state.deleteOriginals
        if (chosen.isEmpty() || running?.isActive == true) return
        stopAsked = false
        mutableState.update { it.copy(progress = 0f, stopping = false) }
        running = viewModelScope.launch {
            val tally = Tally()
            for ((index, entry) in chosen.withIndex()) {
                // Stopped by the user, or by the vault's lock on the last file: no next file is started.
                if (stopAsked && !tally.stoppedByLock) tally.stopped = true
                if (tally.stopped || tally.stoppedByLock) break
                importOne(File(entry.path), deleteOriginals, tally)
                mutableState.update { it.copy(progress = (index + 1f) / chosen.size) }
            }
            // Up to Android 10 the media index kept a deleted original's name, path and size (MediaIndex).
            withContext(io) { mediaIndex.refresh(tally.deleted) }
            report(tally)
            mutableState.update { it.copy(progress = null, stopping = false, done = true) }
        }
    }

    /** One file into the vault, counted in [tally]; its original deleted, if asked, once its copy is in the vault. */
    private suspend fun importOne(source: File, deleteOriginals: Boolean, tally: Tally) {
        val read = if (deleteOriginals) withContext(io) { FileIdentity.of(source) } else null
        val error = loadOrFailure { files.import(source) }.exceptionOrNull()
        when {
            // Encrypted, in the vault under the live key: only now may its original go.
            error == null -> {
                tally.done++
                if (deleteOriginals) {
                    if (withContext(io) { deleteIfUnchanged(source, read) }) tally.deleted += source.path else tally.notDeleted++
                }
            }
            error is NameTakenException -> tally.kept++
            error is VaultLockedException -> tally.stoppedByLock = true
            else -> tally.failed++
        }
    }

    private fun report(tally: Tally) {
        val summary = VaultMessages.imported(
            done = tally.done,
            kept = tally.kept,
            failed = tally.failed,
            stoppedByLock = false,
            deleted = tally.deleted.size,
            keptLabel = R.plurals.vault_namesakes_kept,
            notDeleted = tally.notDeleted,
        )
        val interrupted = when {
            tally.stoppedByLock -> uiText(R.string.vault_folder_locked)
            tally.stopped -> uiText(R.string.vault_folder_stopped)
            else -> null
        }
        val text: UiText? = when {
            interrupted == null -> summary
            summary == null -> interrupted
            else -> UiText.Joined(summary, interrupted, R.string.joined_dot)
        }
        val isError = tally.failed > 0 || tally.notDeleted > 0 || tally.stoppedByLock
        if (text != null) messages.post(UserMessage(text, isError = isError))
    }

    private class Tally {
        var done = 0
        var kept = 0
        var failed = 0

        /** The originals deleted, by path: the media index is told of them. */
        val deleted = mutableListOf<String>()
        var notDeleted = 0
        var stoppedByLock = false
        var stopped = false
    }
}

/**
 * A file as it was when its copy was read: size, time, and the file system's own key (device and inode)
 * when it has one. Another app replacing or still writing the file changes one of them.
 */
private data class FileIdentity(val size: Long, val modified: FileTime, val key: Any?) {
    companion object {
        fun of(file: File): FileIdentity? = try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            FileIdentity(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        } catch (expected: IOException) {
            null
        }
    }
}

/**
 * Deletes [source] only if it is still the file whose copy went into the vault: one that changed since —
 * a download still being written, a file a sync replaced — would be lost with a copy of what it was.
 */
private fun deleteIfUnchanged(source: File, read: FileIdentity?): Boolean =
    read != null && FileIdentity.of(source) == read && source.delete()

/**
 * The files of a folder for the vault's import, over the app's one walk (FolderWalk): links never followed,
 * hidden files listed, the folders below left out, at most [MAX_ENTRIES], sorted by path. A subfolder it
 * cannot read is passed by, as the 2.15.3 did.
 */
internal object FolderScan {
    const val MAX_ENTRIES = 5_000

    /** The 2.15.3's system folders: thumbnails, caches, a trash. */
    private val SYSTEM_FOLDERS = setOf(".thumbnails", ".cache", ".trash")

    /** [capReached]: a file beyond [MAX_ENTRIES] was left out — the 2.15.3 also said so at exactly 5 000. */
    class Listing(val entries: List<FolderEntry>, val capReached: Boolean)

    /** @throws IOException [folder] itself cannot be listed. */
    suspend fun list(folder: File, recursive: Boolean): Listing {
        val root = folder.toPath()
        Files.newDirectoryStream(root).close()
        val found = mutableListOf<FolderEntry>()
        var capReached = false
        FolderWalk(folder, skipHidden = false, skipFolder = ::leftOut, recursive = recursive).forEachFile { path, attributes ->
            capReached = found.size == MAX_ENTRIES
            if (!capReached) found += FolderEntry(path.toString(), root.relativize(path).toString(), attributes.size())
            !capReached
        }
        return Listing(NameOrder().sortPaths(found, FolderEntry::relative), capReached)
    }

    /**
     * Not for the vault: the apps' data, the system's folders (the 2.15.3's list), and the app's own trash
     * under both its names — its items are the app's copies, which the 2.15.3 listed.
     */
    private fun leftOut(dir: Path): Boolean =
        FolderWalk.isTrash(dir) || FolderWalk.isAppData(dir) || dir.fileName?.toString() in SYSTEM_FOLDERS
}
