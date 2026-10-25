package com.readfilestech.read_files_tech.data.sharing

import java.io.File
import java.io.IOException

/**
 * Which file a share of a vault file may serve (docs D-034) — the one way the vault's plaintext leaves the
 * app, which [PathGuard] refuses everywhere else: a copy the vault decrypted for that share, in a slot of
 * its own under `cache/vault_shared/`.
 */
object VaultShares {
    /** [copy], resolved — links followed —, when it is a file of a slot of [root]; null otherwise. */
    fun resolve(copy: File, root: File): File? {
        val realRoot = real(root)
        return real(copy)?.takeIf { realRoot != null && it.parentFile?.parentFile == realRoot && it.isFile }
    }

    /** The real path, every link resolved — on any file system, where `canonicalFile` may not — or null when absent. */
    private fun real(file: File): File? = try {
        file.toPath().toRealPath().toFile()
    } catch (expected: IOException) {
        null
    }
}
