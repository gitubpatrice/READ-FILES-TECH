package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.vault.format.Argon2Params

/**
 * What the vault refuses, each with its own type: the screen says something different for each, and
 * a wrong password must never be confused with a broken vault (that one must not count as a failure).
 *
 * A wrong password is not here: `unlock` returns false, a restore throws
 * [com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException].
 */
sealed class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** No key in memory: unlock first. */
open class VaultLockedException(message: String = "the vault is locked") : VaultException(message)

/**
 * The vault was locked WHILE the operation ran (D1, docs D-013): it stops before writing, and its
 * output is removed. Never a partial success.
 */
class LockedDuringOperationException : VaultLockedException("the vault was locked during the operation")

/** Too many failures: [remainingMs] before the next attempt. */
class VaultLockoutException(val remainingMs: Long) : VaultException("too many attempts, ${remainingMs}ms left")

/** The vault cannot be opened as stored: missing salt or sentinel, an unknown KDF, an unreadable key. Not counted as a failure. */
class VaultCorruptedException(reason: String, cause: Throwable? = null) : VaultException("vault corrupted: $reason", cause)

/** The preferences file refused a write that guards the vault (a failure counter, the setup flags). */
class VaultStorageException(reason: String) : VaultException(reason)

/** A vault exists already: a second setup would replace its salt and lose every file. */
class VaultAlreadySetUpException : VaultException("a vault exists already")

/** A file of that name is in the vault, or at the destination: the caller asks, then passes overwrite. */
class NameTakenException(val name: String) : VaultException("$name exists already")

/** A name the vault cannot hold: it would overwrite the sentinel, or it is not a single file name. */
class NameRefusedException(val name: String, reason: String) : VaultException("$name refused: $reason")

/** Beyond what one operation may hold: [sizeBytes] for a limit of [limitBytes]. */
class TooLargeException(val sizeBytes: Long, val limitBytes: Long) : VaultException("$sizeBytes bytes, limit $limitBytes")

/**
 * A `.rftvault` whose Argon2 cost this device's heap cannot afford: refused BEFORE deriving, instead of
 * an `OutOfMemoryError` (external review of the plan, finding 19).
 */
class BackupTooCostlyException(val params: Argon2Params, cause: Throwable? = null) :
    VaultException("Argon2 ${params.memoryKB} KiB × ${params.iterations} is beyond this device", cause)
