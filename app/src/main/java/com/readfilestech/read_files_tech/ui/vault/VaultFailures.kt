package com.readfilestech.read_files_tech.ui.vault

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.MIB
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.outputFailure
import com.readfilestech.read_files_tech.vault.BackupTooCostlyException
import com.readfilestech.read_files_tech.vault.NameRefusedException
import com.readfilestech.read_files_tech.vault.TooLargeException
import com.readfilestech.read_files_tech.vault.VaultCorruptedException
import com.readfilestech.read_files_tech.vault.VaultLockedException
import com.readfilestech.read_files_tech.vault.VaultLockoutException
import com.readfilestech.read_files_tech.vault.VaultStorageException
import com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException
import com.readfilestech.read_files_tech.vault.format.VaultFormatException

/**
 * Why a vault operation failed, in words — never the exception's own text, which says "sentinel" or names a
 * file of the vault (the 2.15.3 showed `'Erreur : $e'`). A wrong backup password and an altered backup are
 * one message: telling them apart would tell an attacker which one it was.
 */
internal fun vaultReason(error: Throwable): UiText = when (error) {
    is VaultLockoutException -> uiText(R.string.vault_reason_lockout, waitText(error.remainingMs))
    // LockedDuringOperationException included: the vault locked while the operation ran.
    is VaultLockedException -> uiText(R.string.vault_reason_locked)
    is VaultCorruptedException -> uiText(R.string.vault_reason_corrupted)
    is VaultStorageException -> uiText(R.string.vault_reason_storage)
    is TooLargeException -> uiText(R.string.vault_reason_too_large, error.limitBytes / MIB, uiText(R.string.unit_megabytes))
    is NameRefusedException -> uiText(R.string.vault_reason_name)
    is VaultAuthenticationException -> uiText(R.string.vault_reason_auth)
    is VaultFormatException -> uiText(R.string.vault_reason_format)
    is BackupTooCostlyException -> uiText(R.string.vault_reason_too_costly)
    else -> outputFailure(error) ?: IoErrors.reasonOf(error)
}

/** What a backup's export says of a file whose name a restore would refuse: which one, and what to do. */
internal fun backupReason(error: Throwable): UiText =
    if (error is NameRefusedException) uiText(R.string.vault_reason_backup_name, error.name) else vaultReason(error)

/** A lockout's wait, whole seconds under a minute, whole minutes above — rounded UP: never "0 seconds". */
internal fun waitText(remainingMs: Long): UiText {
    val seconds = ((remainingMs + MS_PER_SECOND - 1) / MS_PER_SECOND).coerceAtLeast(1)
    return if (seconds < SECONDS_PER_MINUTE) {
        UiText.Plural(R.plurals.vault_wait_seconds, seconds.toInt())
    } else {
        UiText.Plural(R.plurals.vault_wait_minutes, ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt())
    }
}

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
