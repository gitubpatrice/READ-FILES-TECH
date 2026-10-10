package com.readfilestech.read_files_tech.vault.format

/**
 * Names in the vault directory (`app_flutter/vault/`), the same as the Flutter app's — with two of
 * its defects corrected (docs/INVENTAIRE-2.15.2.md §9.2):
 * - D26: the 2.15.x hid the sentinel with `endsWith("_check.enc")`, so a file stored as
 *   `rapport_check.enc` vanished from the list and from every backup. Here: equality.
 * - D24: the residue of an interrupted atomic write (`X.enc.<n>-<hex>.tmp`) is not a vault file.
 */
object VaultNames {
    const val ENC_SUFFIX = ".enc"

    /** The sentinel: an encrypted known text, proof that a password is the right one. */
    const val SENTINEL_FILE = "_check.enc"
    const val SENTINEL_PLAIN = "read_files_tech_vault_v1"

    fun isSentinel(fileName: String): Boolean = fileName == SENTINEL_FILE

    /** A file of the vault directory that holds a user's file. */
    fun isVaultFile(fileName: String): Boolean = fileName.endsWith(ENC_SUFFIX) && !isSentinel(fileName)

    fun encryptedName(plainName: String): String = plainName + ENC_SUFFIX

    fun plainName(encryptedName: String): String = encryptedName.removeSuffix(ENC_SUFFIX)

    /**
     * Why an entry of a `.rftvault` must not be restored, or null when it may.
     *
     * The rule of the 2.15.x (`_parseBackupPayload`): a bare file name (`PathSafe.basename(name) ==
     * name`: not empty, not `.` or `..`, no `/`, `\` or NUL) with no control character and none of
     * `<>:"|?*`. Plus D25: an entry that would become the sentinel is refused — restored with
     * "overwrite", it made the vault unopenable.
     */
    fun restoreRefusal(name: String): String? = when {
        name.isEmpty() || name == "." || name == ".." -> "not a file name"
        name.any { it == '/' || it == '\\' } -> "contains a path separator"
        name.any { it < ' ' } -> "contains a control character"
        name.any { it in "<>:\"|?*" } -> "contains a reserved character"
        isSentinel(encryptedName(name)) -> "would overwrite the vault's sentinel"
        else -> null
    }
}
