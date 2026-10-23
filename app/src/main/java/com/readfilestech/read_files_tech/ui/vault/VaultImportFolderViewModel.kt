package com.readfilestech.read_files_tech.ui.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
@HiltViewModel
class VaultImportFolderViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val vault: VaultService,
    private val files: VaultFiles,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FolderImportState(folder = handle.get<String>(Routes.ARG_PATH).orEmpty()))
    val state: StateFlow<FolderImportState> = mutableState.asStateFlow()

    private var scanning: Job? = null
    private var running: Job? = null

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

    /** "Encrypt": at once, or after the confirmation that the originals will be deleted. */
    fun encrypt() {
        val current = mutableState.value
        if (running?.isActive == true || current.scanning || current.selected.isEmpty()) return
        if (current.deleteOriginals) mutableState.update { it.copy(confirmDelete = current.selected.size) } else run(current)
    }

    fun confirmDelete(yes: Boolean) {
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
        if (chosen.isEmpty()) return
        mutableState.update { it.copy(progress = 0f) }
        running = viewModelScope.launch {
            val tally = Tally()
            for ((index, entry) in chosen.withIndex()) {
                val source = File(entry.path)
                val outcome = loadOrFailure { files.import(source) }
                val error = outcome.exceptionOrNull()
                when {
                    // Encrypted, in the vault under the live key: only now may its original go.
                    error == null -> {
                        tally.done++
                        if (deleteOriginals && withContext(io) { source.delete() }) tally.deleted++
                    }
                    error is NameTakenException -> tally.kept++
                    error is VaultLockedException -> {
                        tally.stoppedByLock = true
                        break
                    }
                    else -> tally.failed++
                }
                mutableState.update { it.copy(progress = (index + 1f) / chosen.size) }
            }
            report(tally)
            mutableState.update { it.copy(progress = null, done = true) }
        }
    }

    private fun report(tally: Tally) {
        val summary = VaultMessages.imported(
            done = tally.done,
            kept = tally.kept,
            failed = tally.failed,
            stoppedByLock = false,
            deleted = tally.deleted,
            keptLabel = R.plurals.vault_namesakes_kept,
        )
        val locked = uiText(R.string.vault_folder_locked)
        val text: UiText? = when {
            !tally.stoppedByLock -> summary
            summary == null -> locked
            else -> UiText.Joined(summary, locked, R.string.joined_dot)
        }
        if (text != null) messages.post(UserMessage(text, isError = tally.failed > 0 || tally.stoppedByLock))
    }

    private class Tally {
        var done = 0
        var kept = 0
        var failed = 0
        var deleted = 0
        var stoppedByLock = false
    }
}

/**
 * The files of a folder for the vault's import: links never followed, the system's folders and the app's
 * trash left out, at most [MAX_ENTRIES], sorted by path. A folder it cannot read is skipped, not fatal.
 */
internal object FolderScan {
    const val MAX_ENTRIES = 5_000

    /**
     * Not for the vault: other apps' data, thumbnails, caches, the system's trash (the 2.15.3's list) — and
     * the app's own trash, whose items and metadata are its own files.
     */
    private val EXCLUDED = listOf("/Android/data/", "/Android/obb/", "/.thumbnails/", "/.cache/", "/.trash/", "/.RFT_Corbeille/")

    class Listing(val entries: List<FolderEntry>, val capReached: Boolean)

    /** @throws IOException [folder] itself cannot be read. */
    suspend fun list(folder: File, recursive: Boolean): Listing {
        val root = folder.toPath()
        if (!Files.isDirectory(root)) throw IOException("not a folder")
        val collector = Collector(root, currentCoroutineContext().job)
        Files.walkFileTree(root, emptySet(), if (recursive) Int.MAX_VALUE else 1, collector)
        return Listing(collector.found.sortedBy(FolderEntry::path), collector.capReached)
    }

    /** The walk: links are not followed (no FOLLOW_LINKS), a folder it cannot enter is passed by. */
    private class Collector(private val root: Path, private val job: Job) : SimpleFileVisitor<Path>() {
        val found = mutableListOf<FolderEntry>()
        var capReached = false

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            job.ensureActive()
            return if (dir != root && excluded(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile && !excluded(file)) {
                found += FolderEntry(file.toString(), root.relativize(file).toString(), attrs.size())
                capReached = found.size >= MAX_ENTRIES
            }
            return if (capReached) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
    }

    private fun excluded(path: Path): Boolean {
        val slashed = path.toString().replace('\\', '/') + "/"
        return EXCLUDED.any { slashed.contains(it) }
    }
}
