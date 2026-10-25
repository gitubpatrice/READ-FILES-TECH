package com.readfilestech.read_files_tech.vault

/**
 * The preference keys of the vault, the bare Dart keys of `VaultService` (2.15.3), read and written in
 * place in the Flutter app's file (docs D-003, D-018). Types are the plugin's: an `int` is a `Long`.
 *
 * Grouped so that every reset reads ONE list (lesson V-L5 of the 2.15.x: a key added to a mechanism
 * and forgotten in its reset left a new vault locked for 30 minutes).
 */
object VaultKeys {
    /** Base64 of the 16-byte salt. */
    const val SALT = "vault_salt_v1"

    /** Written LAST at setup: without it, the vault does not exist. */
    const val SETUP = "vault_setup_v1"

    /** Absent or `pbkdf2`: PBKDF2 (before 2.6.0). `argon2id`: Argon2id, parameters below. */
    const val KDF_VERSION = "vault_kdf_version"
    const val KDF_PBKDF2 = "pbkdf2"
    const val KDF_ARGON2ID = "argon2id"

    /** Absent on a 2.6.0–2.7.0 vault: the legacy 16 MiB × 4 then applies. */
    const val ARGON2_MEMORY_KB = "vault_argon2_mem_kb"
    const val ARGON2_ITERATIONS = "vault_argon2_iter"

    /** Set on a vault created since 2.12.0: a v1 blob there can only be a substitution. */
    const val V2_ONLY = "vault_v2_only"

    const val UNLOCK_FAILS = "vault_unlock_fails"
    const val LOCKOUT_UNTIL = "vault_lockout_until_ms"
    const val LOCKOUT_UNTIL_ELAPSED = "vault_lockout_until_elapsed"
    const val LOCKOUT_ARMED_ELAPSED = "vault_lockout_armed_elapsed"

    const val BACKUP_FAILS = "vault_backup_fails"
    const val BACKUP_LOCKOUT_UNTIL = "vault_backup_lockout_until_ms"
    const val BACKUP_LOCKOUT_UNTIL_ELAPSED = "vault_backup_lockout_until_elapsed"
    const val BACKUP_LOCKOUT_ARMED_ELAPSED = "vault_backup_lockout_armed_elapsed"

    /** The one-off repair of the files a zeroed key encrypted (D1, docs D-016). */
    const val ZERO_KEY_REPAIR_DONE = "vault_zero_key_repair_v1"
    const val ZERO_KEY_REPAIR_ATTEMPTS = "vault_zero_key_repair_attempts"
    const val ZERO_KEY_REPAIR_CURRENT = "vault_zero_key_repair_current"
    const val ZERO_KEY_REPAIR_SKIPPED = "vault_zero_key_repair_skipped"
    const val ZERO_KEY_REPAIR_REPORT = "vault_zero_key_repair_report"
    const val ZERO_KEY_REPAIR_UNEXAMINED = "vault_zero_key_repair_unexamined"

    /** One lockout: its counter, its two deadlines and the instant it was armed. */
    class Lockout(val fails: String, val until: String, val untilElapsed: String, val armedElapsed: String) {
        val all = listOf(fails, until, untilElapsed, armedElapsed)
    }

    val UNLOCK_LOCKOUT = Lockout(UNLOCK_FAILS, LOCKOUT_UNTIL, LOCKOUT_UNTIL_ELAPSED, LOCKOUT_ARMED_ELAPSED)
    val BACKUP_LOCKOUT = Lockout(BACKUP_FAILS, BACKUP_LOCKOUT_UNTIL, BACKUP_LOCKOUT_UNTIL_ELAPSED, BACKUP_LOCKOUT_ARMED_ELAPSED)

    val ZERO_KEY_REPAIR = listOf(
        ZERO_KEY_REPAIR_DONE,
        ZERO_KEY_REPAIR_ATTEMPTS,
        ZERO_KEY_REPAIR_CURRENT,
        ZERO_KEY_REPAIR_SKIPPED,
        ZERO_KEY_REPAIR_REPORT,
        ZERO_KEY_REPAIR_UNEXAMINED,
    )

    /** What belongs to ONE vault: a reset erases all of it, a new setup writes what it needs again. */
    val VAULT_STATE: List<String> = listOf(SALT, SETUP, KDF_VERSION, ARGON2_MEMORY_KB, ARGON2_ITERATIONS, V2_ONLY) +
        UNLOCK_LOCKOUT.all + BACKUP_LOCKOUT.all + ZERO_KEY_REPAIR
}
