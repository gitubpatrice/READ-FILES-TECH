package com.readfilestech.read_files_tech.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.browse.NameOrder
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessage
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import com.readfilestech.read_files_tech.vault.NameTakenException
import com.readfilestech.read_files_tech.vault.VaultAlreadySetUpException
import com.readfilestech.read_files_tech.vault.VaultBackups
import com.readfilestech.read_files_tech.vault.VaultCipher
import com.readfilestech.read_files_tech.vault.VaultClock
import com.readfilestech.read_files_tech.vault.VaultCorruptedException
import com.readfilestech.read_files_tech.vault.VaultDirs
import com.readfilestech.read_files_tech.vault.VaultFiles
import com.readfilestech.read_files_tech.vault.VaultLockedException
import com.readfilestech.read_files_tech.vault.VaultLockoutException
import com.readfilestech.read_files_tech.vault.VaultService
import com.readfilestech.read_files_tech.vault.ZeroKeyRepair
import com.readfilestech.read_files_tech.vault.format.VaultNames
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** One file of the vault as the list shows it: its name in the vault, the plaintext's size. */
data class VaultItem(val encryptedName: String, val name: String, val size: Long) {
    /** The app's viewer for it (docs D-028), or null: it can only be shared or exported. */
    val viewer: ViewerKind? get() = ViewerKind.forExtension(FileNames.extension(name))
}

enum class VaultPhase { CHECKING, SETUP, LOCKED, OPEN, BROKEN }

/** A long operation under way: shown, and no other one starts meanwhile. */
sealed interface VaultWork {
    data object Creating : VaultWork
    data object Unlocking : VaultWork
    data class Importing(val done: Int, val total: Int) : VaultWork
    data object BackingUp : VaultWork
    data object Restoring : VaultWork
    data object Working : VaultWork
}

/** A question or a report over the screen. None carries a password: one asked for lives in the dialog. */
sealed interface VaultDialog {
    data object ConfirmReset : VaultDialog
    data class Namesake(val name: String) : VaultDialog
    data class ExportNamesake(val name: String) : VaultDialog
    data class ConfirmDelete(val item: VaultItem) : VaultDialog

    /**
     * The backup's password, [folder] being where it will be written — the one the settings chose, or a fallback;
     * [appOwned] when it is one of the app's own fallbacks, erased with the app.
     */
    data class BackupPassword(val folder: String, val appOwned: Boolean = false) : VaultDialog
    data class UnexpectedExtension(val path: String) : VaultDialog
    data class RestorePassword(val path: String) : VaultDialog
    data class RestoreAllThere(val count: Int) : VaultDialog
    data class BackupDone(val path: String) : VaultDialog
    data class Repair(val repaired: List<String>, val unexamined: List<String>) : VaultDialog
}

/** What the app's picker was opened for: its result comes back to the vault's screen, which dispatches it. */
enum class VaultPick { IMPORT, FOLDER, EXPORT, RESTORE }

data class VaultState(
    val phase: VaultPhase = VaultPhase.CHECKING,
    /** Empty whenever the vault is not open: the names of its files are its metadata. */
    val files: List<VaultItem> = emptyList(),
    val work: VaultWork? = null,
    /** Under the password field: why the vault was not created or opened. */
    val error: UiText? = null,
    /**
     * The `elapsedRealtime` the brute-force lockout ends at, from the last refused unlock: the form counts down to it
     * (lockoutText) instead of a time frozen at the attempt, which the 2.15.3 showed until the next one.
     */
    val lockoutEndsAt: Long? = null,
    val dialog: VaultDialog? = null,
    /** A copy decrypted for a share the user asked for, to hand to the share sheet once (docs D-034). */
    val share: String? = null,
    /** A folder chosen to encrypt: the screen opens the folder's import, once. */
    val folderToImport: String? = null,
)

/**
 * `VaultScreen` of the 2.15.3 — create, unlock, the list, import (files, a folder), open in the app (docs
 * D-028), share decrypted (D-034), export, delete, back up and restore a `.rftvault`, reset — over the data
 * layer of phase 2 (VaultService, VaultFiles, VaultBackups, ZeroKeyRepair).
 *
 * Whatever locks the vault — this screen's button, the auto-lock (30 s away, 3 min idle), the panic —
 * takes its files' names off the screen and its questions with them. Leaving the screen locks it (V-H1 of
 * the 2.15.x): quitting the vault is being done with it. A viewer, the picker or the folder's import opened
 * from here are not leaving it.
 *
 * Departures, each a defect of the 2.15.3: no exception's text on screen (`'Erreur : $e'`); an import
 * interrupted by a lock stops and says so, where the 2.15.3 counted the rest as errors; a backup's
 * password is never kept by a message's action, nor by any state that outlives its dialog (D24).
 */
@HiltViewModel
@Suppress("TooManyFunctions") // one entry per action of the screen, each a few lines
class VaultViewModel @Inject constructor(
    private val vault: VaultService,
    private val files: VaultFiles,
    private val dirs: VaultDirs,
    private val cipher: VaultCipher,
    private val backups: VaultBackups,
    private val repair: ZeroKeyRepair,
    private val outputs: OutputStorage,
    private val messages: UserMessages,
    private val clock: VaultClock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VaultState())
    val state: StateFlow<VaultState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var question: CompletableDeferred<Boolean>? = null

    /** What the picker is open for, and the file to export (pickFor). */
    private var pending: VaultPick? = null
    private var exporting: String? = null

    /** The backup's password between "nothing restored" and "replace": memory only, dropped with the question. */
    private var restoreRetry: Pair<String, String>? = null

    private val busy: Boolean get() = mutableState.value.work != null || job?.isActive == true

    init {
        viewModelScope.launch {
            vault.unlocked.collect { open -> if (open) opened() else locked() }
        }
        viewModelScope.launch {
            val setUp = loadOrFailure { withContext(io) { vault.isSetUp() } }
            mutableState.update { current ->
                if (current.phase != VaultPhase.CHECKING) return@update current
                val phase = setUp.fold(
                    { created ->
                        when {
                            !created -> VaultPhase.SETUP
                            vault.unlocked.value -> VaultPhase.OPEN
                            else -> VaultPhase.LOCKED
                        }
                    },
                    { VaultPhase.BROKEN },
                )
                current.copy(phase = phase)
            }
            if (mutableState.value.phase == VaultPhase.OPEN) refresh()
        }
    }

    fun setup(password: String) {
        if (busy) return
        mutableState.update { it.copy(work = VaultWork.Creating, error = null) }
        job = viewModelScope.launch {
            val created = loadOrFailure { vault.setup(password) }
            created.onSuccess { open ->
                messages.info(uiText(R.string.vault_created))
                // Locked during the derivation (the idle time, the panic) or since: created, but to be opened.
                val phase = if (open && vault.unlocked.value) VaultPhase.OPEN else VaultPhase.LOCKED
                mutableState.update { it.copy(work = null, phase = phase) }
            }.onFailure { error ->
                if (error is VaultAlreadySetUpException) {
                    mutableState.update { it.copy(work = null, phase = VaultPhase.LOCKED) }
                } else {
                    mutableState.update { it.copy(work = null, error = uiText(R.string.vault_setup_failed, vaultReason(error))) }
                }
            }
        }
    }

    fun unlock(password: String) {
        if (busy) return
        mutableState.update { it.copy(work = VaultWork.Unlocking, error = null, lockoutEndsAt = null) }
        job = viewModelScope.launch {
            val result = loadOrFailure { vault.unlock(password) }
            mutableState.update { current ->
                result.fold(
                    { open -> current.copy(work = null, error = if (open) null else uiText(R.string.vault_wrong_password)) },
                    {
                        if (it is VaultLockoutException) {
                            current.copy(work = null, lockoutEndsAt = clock.elapsedRealtimeMs() + it.remainingMs)
                        } else {
                            current.copy(work = null, error = unlockFailure(it))
                        }
                    },
                )
            }
        }
    }

    fun lock() = vault.lock()

    fun askReset() = show(VaultDialog.ConfirmReset)

    fun reset() {
        dismiss()
        if (busy) return
        run(VaultWork.Working) {
            val done = loadOrFailure { vault.reset() }
            done.onSuccess {
                messages.info(uiText(R.string.vault_reset_done))
                mutableState.update { it.copy(phase = VaultPhase.SETUP, error = null, lockoutEndsAt = null) }
            }.onFailure { messages.error(uiText(R.string.vault_reset_failed, vaultReason(it))) }
        }
    }

    /** The list read again: on return to the screen, after an operation. Only while the vault is open. */
    fun refresh() {
        if (mutableState.value.phase != VaultPhase.OPEN) return
        viewModelScope.launch {
            val listed = loadOrFailure {
                withContext(io) {
                    val items = files.list().map { VaultItem(it.name, displayName(it.name), cipher.plainLength(it)) }
                    NameOrder().sort(items, VaultItem::name)
                }
            }
            // Locked while it listed: the names stay off the screen.
            if (!vault.unlocked.value) return@launch
            listed.onSuccess { items -> mutableState.update { it.copy(files = items) } }
                .onFailure { messages.error(uiText(R.string.vault_list_failed, vaultReason(it))) }
        }
    }

    /**
     * The picker is opened for [pick]; [item] is the file to export. Kept in memory only, dropped at a lock:
     * a name of the vault has no place in the saved state, and after the process died the vault is locked.
     */
    fun pickFor(pick: VaultPick, item: VaultItem? = null) {
        pending = pick
        exporting = item?.encryptedName
    }

    /** What the picker handed back, dispatched to the action it was opened for — while the vault is open. */
    fun picked(paths: List<String>?, folder: String?) {
        val pick = pending ?: return
        val exported = exporting
        pending = null
        exporting = null
        // locked() drops the wait; this covers the picker's answer arriving before its collector ran.
        if (!vault.unlocked.value) return
        when (pick) {
            VaultPick.IMPORT -> paths?.takeIf { it.isNotEmpty() }?.let(::import)
            VaultPick.RESTORE -> paths?.firstOrNull()?.let(::restoreChosen)
            VaultPick.FOLDER -> folder?.let { path -> mutableState.update { it.copy(folderToImport = path) } }
            VaultPick.EXPORT -> if (folder != null && exported != null) export(exported, folder)
        }
    }

    fun folderImportOpened() = mutableState.update { it.copy(folderToImport = null) }

    fun notViewable() = messages.info(uiText(R.string.vault_not_viewable))

    /** Each file chosen encrypted in turn; a namesake asked about; stopped at once by a lock, and said. */
    private fun import(paths: List<String>) {
        if (busy) return
        run(VaultWork.Importing(0, paths.size)) {
            val outcomes = mutableListOf<Imported>()
            for ((index, path) in paths.withIndex()) {
                mutableState.update { it.copy(work = VaultWork.Importing(index + 1, paths.size)) }
                val outcome = importOne(File(path))
                outcomes += outcome
                if (outcome == Imported.STOPPED) break
            }
            refresh()
            val stopped = Imported.STOPPED in outcomes
            val failed = outcomes.count { it == Imported.FAILED }
            say(
                VaultMessages.imported(outcomes.count { it == Imported.DONE }, outcomes.count { it == Imported.KEPT }, failed, stopped),
                isError = failed > 0 || stopped,
            )
        }
    }

    private enum class Imported { DONE, KEPT, FAILED, STOPPED }

    /** One file of an import; a namesake asked about — "Keep", or the vault locked while it asked: kept. */
    private suspend fun importOne(source: File): Imported {
        var outcome = loadOrFailure { files.import(source) }
        if (outcome.exceptionOrNull() is NameTakenException) {
            if (!ask(VaultDialog.Namesake(source.name))) return Imported.KEPT
            outcome = loadOrFailure { files.import(source, overwrite = true) }
        }
        return when (outcome.exceptionOrNull()) {
            null -> Imported.DONE
            is VaultLockedException -> Imported.STOPPED
            else -> Imported.FAILED
        }
    }

    /** The share sheet for a copy decrypted for it (docs D-034), its folder purged when the vault locks. */
    fun share(item: VaultItem) {
        if (busy) return
        run(VaultWork.Working) {
            val copy = loadOrFailure { withContext(io) { files.copy(encrypted(item), VaultFiles.Copy.SHARE) } }
            // Locked while it decrypted: the copy is purged, nothing to hand over.
            copy.onSuccess { file -> if (vault.unlocked.value) mutableState.update { it.copy(share = file.path) } }
                .onFailure { messages.error(uiText(R.string.vault_share_failed, vaultReason(it))) }
        }
    }

    fun shared() = mutableState.update { it.copy(share = null) }

    private fun export(encryptedName: String, folder: String) {
        if (busy) return
        run(VaultWork.Working) {
            val file = File(dirs.vault, encryptedName)
            val name = displayName(encryptedName)
            var outcome = loadOrFailure { files.export(file, File(folder)) }
            if (outcome.exceptionOrNull() is NameTakenException) {
                if (!ask(VaultDialog.ExportNamesake(name))) {
                    messages.info(uiText(R.string.vault_export_cancelled))
                    return@run
                }
                outcome = loadOrFailure { files.export(file, File(folder), overwrite = true) }
            }
            outcome.onSuccess { messages.info(uiText(R.string.vault_exported, it.name)) }
                .onFailure { messages.error(uiText(R.string.vault_export_failed, vaultReason(it))) }
        }
    }

    fun askDelete(item: VaultItem) = show(VaultDialog.ConfirmDelete(item))

    fun delete(item: VaultItem) {
        dismiss()
        if (busy) return
        run(VaultWork.Working) {
            loadOrFailure { files.delete(encrypted(item)) }
                .onFailure { messages.error(uiText(R.string.vault_delete_failed, vaultReason(it))) }
            refresh()
        }
    }

    fun askBackup() {
        if (mutableState.value.files.isEmpty()) {
            messages.info(uiText(R.string.vault_backup_empty))
            return
        }
        viewModelScope.launch {
            // Where it goes, said before the password is typed: the output folder is a setting, and a fallback
            // takes the backup when that folder cannot (the 2.15.3 named `Files Tech/` whatever the setting).
            // A fallback of the app's own goes with it: a backup there would not outlive an uninstall, the very
            // thing the terms say to back up before — said in the dialog (legal review, phase 8).
            loadOrFailure {
                withContext(io) {
                    val folder = outputs.folderFor(OutputCategory.VAULT_BACKUPS)
                    folder to !folder.canonicalFile.toPath().startsWith(outputs.basePath().canonicalFile.toPath())
                }
            }
                .onSuccess { (folder, appOwned) -> show(VaultDialog.BackupPassword(folder.path, appOwned)) }
                .onFailure { messages.error(uiText(R.string.vault_backup_failed, backupReason(it))) }
        }
    }

    /** The whole vault to `<outputs>/Sauvegardes coffre/coffre_<stamp>.rftvault`, reserved only once written. */
    fun backup(password: String) {
        dismiss()
        if (busy) return
        run(VaultWork.BackingUp) {
            val saved = loadOrFailure {
                withContext(io) {
                    val folder = outputs.folderFor(OutputCategory.VAULT_BACKUPS)
                    backups.export(password, VaultBackups.Destination(folder) { outputs.reserveAt(folder, BACKUP_NAME, BACKUP_EXTENSION) })
                }
            }
            saved.onSuccess { show(VaultDialog.BackupDone(it.path)) }
                .onFailure { messages.error(uiText(R.string.vault_backup_failed, backupReason(it))) }
        }
    }

    private fun restoreChosen(path: String) {
        val expected = FileNames.extension(File(path).name) == BACKUP_EXTENSION
        show(if (expected) VaultDialog.RestorePassword(path) else VaultDialog.UnexpectedExtension(path))
    }

    fun restoreAnyway(path: String) = show(VaultDialog.RestorePassword(path))

    fun restore(path: String, password: String) = restoreWith(path, password, overwrite = false)

    /** "Nothing restored, all already there": the same backup again, its files replacing the vault's. */
    fun restoreReplacing() {
        val (path, password) = restoreRetry ?: return
        restoreRetry = null
        restoreWith(path, password, overwrite = true)
    }

    private fun restoreWith(path: String, password: String, overwrite: Boolean) {
        dismiss()
        if (busy) return
        run(VaultWork.Restoring) {
            val restored = loadOrFailure { backups.restore(File(path), password, overwrite) }
            refresh()
            restored.onSuccess { result ->
                if (!overwrite && allAlreadyThere(result)) {
                    // Locked meanwhile: no "Replace?" over a locked vault, its backup's password not kept.
                    if (vault.unlocked.value) {
                        restoreRetry = path to password
                        show(VaultDialog.RestoreAllThere(result.skipped))
                    }
                } else {
                    say(VaultMessages.restored(result), isError = result.failed.isNotEmpty())
                }
            }.onFailure { messages.error(uiText(R.string.vault_restore_failed, vaultReason(it))) }
        }
    }

    /** The repair report seen: it is not shown again. */
    fun repairSeen() {
        dismiss()
        viewModelScope.launch { loadOrFailure { withContext(io) { repair.clearReport() } } }
    }

    /** A yes or no to the question on screen; any other dialog closed. */
    fun answer(yes: Boolean) {
        val asked = question
        if (asked != null) {
            asked.complete(yes)
        } else {
            dismiss()
        }
    }

    fun dismiss() {
        question?.complete(false)
        question = null
        restoreRetry = null
        mutableState.update { it.copy(dialog = null) }
    }

    /**
     * Leaving the vault's screen locks it: the 2.15.x left it open, to be reopened without a password (V-H1).
     * Whether or not it is open yet: an unlock or a creation under way installs its key at the end of its
     * derivation unless a lock came meanwhile — left during one, the vault would open with no screen.
     */
    override fun onCleared() = vault.lock()

    private fun opened() {
        if (mutableState.value.phase == VaultPhase.CHECKING) return
        mutableState.update { it.copy(phase = VaultPhase.OPEN, error = null) }
        refresh()
        repairOnce()
    }

    /** The vault locked, from wherever: its files' names off the screen, its questions closed, its work stopping. */
    private fun locked() {
        question?.complete(false)
        question = null
        restoreRetry = null
        pending = null
        exporting = null
        mutableState.update { current ->
            val phase = if (current.phase == VaultPhase.OPEN) VaultPhase.LOCKED else current.phase
            current.copy(phase = phase, files = emptyList(), dialog = null, share = null, folderToImport = null)
        }
    }

    /**
     * The files the 2.5.5 to 2.15.2 encrypted under a zeroed key, repaired once, at the first unlock after the
     * update (docs D-016), its report kept until seen: an interrupted pass, a screen left or a lock lose nothing.
     */
    private fun repairOnce() {
        viewModelScope.launch {
            loadOrFailure { repair.runOnce() }
            val report = loadOrFailure { withContext(io) { repair.pendingReport() } }.getOrNull() ?: return@launch
            // The names are the vault's metadata: never over a vault locked meanwhile.
            if (vault.unlocked.value && mutableState.value.dialog == null) show(VaultDialog.Repair(report.repaired, report.unexamined))
        }
    }

    /** A lockout is not among them: it is counted down (lockoutEndsAt). */
    private fun unlockFailure(error: Throwable): UiText = when (error) {
        is VaultCorruptedException -> uiText(R.string.vault_corrupted)
        else -> uiText(R.string.vault_unlock_failed, vaultReason(error))
    }

    private fun encrypted(item: VaultItem) = File(dirs.vault, item.encryptedName)

    private fun show(dialog: VaultDialog) = mutableState.update { it.copy(dialog = dialog) }

    /**
     * Shows [dialog] and waits for its answer: false when dismissed, or when the vault locks meanwhile — or
     * has locked already, between the call that raised the question and now: then nothing is shown.
     */
    private suspend fun ask(dialog: VaultDialog): Boolean {
        if (!vault.unlocked.value) return false
        val answer = CompletableDeferred<Boolean>()
        question = answer
        show(dialog)
        return try {
            answer.await()
        } finally {
            if (question === answer) question = null
            mutableState.update { if (it.dialog == dialog) it.copy(dialog = null) else it }
        }
    }

    private fun run(work: VaultWork, block: suspend () -> Unit) {
        mutableState.update { it.copy(work = work) }
        job = viewModelScope.launch {
            try {
                block()
            } finally {
                mutableState.update { it.copy(work = null) }
            }
        }
    }

    private fun say(text: UiText?, isError: Boolean) {
        if (text != null) messages.post(UserMessage(text, isError = isError))
    }

    private companion object {
        const val BACKUP_NAME = "coffre"
        const val BACKUP_EXTENSION = "rftvault"
    }
}

/** A vault file's name as the user knows it: a control character a 2.15.x let in shown as `_`. */
private fun displayName(encryptedName: String): String = VaultNames.readableName(encryptedName) ?: VaultNames.plainName(encryptedName)

/** A restore that wrote nothing because every file of the backup was already there: "Replace them?" */
private fun allAlreadyThere(result: VaultBackups.RestoreResult): Boolean =
    result.restored == 0 && result.skipped > 0 && result.failed.isEmpty()
