package com.readfilestech.read_files_tech.vault.format

/**
 * Names in the vault directory (`app_flutter/vault/`), the same as the Flutter app's — with four of its
 * defects corrected (docs/INVENTAIRE-2.15.2.md §9.2):
 * - D26: the 2.15.x hid the sentinel with `endsWith("_check.enc")`, so a file stored as
 *   `rapport_check.enc` vanished from the list and from every backup. Here: equality.
 * - D24: the residue of an interrupted atomic write (`X.<n>-<hex>.tmp`) was listed as a file. Here it
 *   is recognised by that exact pattern, and only it: any other name is listed, as by the 2.15.x.
 * - D25 / D27: a backup entry or an imported file named `_check` became the sentinel and, restored or
 *   imported with "overwrite", made the vault unopenable. Here [refusal] refuses it on both paths, and
 *   [encryptedName] refuses to produce the sentinel at all.
 * - D28: the 2.15.x imported and exported a name holding `<>:"|?*` (legal on the phone's file system),
 *   then refused it at restore — the file was lost. Here import and restore follow the same rule.
 */
object VaultNames {
    const val ENC_SUFFIX = ".enc"

    /** The sentinel: an encrypted known text, proof that a password is the right one. */
    const val SENTINEL_FILE = "_check.enc"
    const val SENTINEL_PLAIN = "read_files_tech_vault_v1"

    /** `<target>.<n>-<hex>.tmp`: the temporary file of `atomic_write.dart` and of AtomicFiles. */
    private val WRITE_RESIDUE = Regex("""^.+\.\d+-[0-9a-f]+\.tmp$""")

    fun isSentinel(fileName: String): Boolean = fileName == SENTINEL_FILE

    fun isWriteResidue(fileName: String): Boolean = WRITE_RESIDUE.matches(fileName)

    /** A file of the vault directory that holds a user's file. */
    fun isVaultFile(fileName: String): Boolean = !isSentinel(fileName) && !isWriteResidue(fileName)

    /** @throws IllegalArgumentException for the one name that is the sentinel's: [refusal] must come first. */
    fun encryptedName(plainName: String): String {
        val name = plainName + ENC_SUFFIX
        require(!isSentinel(name)) { "a file cannot take the sentinel's name" }
        return name
    }

    fun plainName(encryptedName: String): String = encryptedName.removeSuffix(ENC_SUFFIX)

    /**
     * Why a file named [name] must not be stored in the vault — imported, restored from a backup, or
     * decrypted under that name — or null when it may.
     *
     * A bare file name (`PathSafe.basename(name) == name` of the 2.15.x: not empty, not `.` or `..`, no
     * `/` or `\`), no control character (NUL included), and not the sentinel's.
     */
    fun refusal(name: String): String? = when {
        name.isEmpty() || name == "." || name == ".." -> "not a file name"
        name.any { it == '/' || it == '\\' } -> "contains a path separator"
        name.any { it < ' ' } -> "contains a control character"
        isSentinel(name + ENC_SUFFIX) -> "would overwrite the vault's sentinel"
        else -> null
    }
}
