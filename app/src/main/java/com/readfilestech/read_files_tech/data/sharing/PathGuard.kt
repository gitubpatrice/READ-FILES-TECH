package com.readfilestech.read_files_tech.data.sharing

import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Which files the app may hand to another app (open with, share, send): `safeCanonical` of the 2.15.x
 * `MainActivity.kt`. The path is resolved first — links followed — and every later step uses the
 * RESOLVED file, so a link swapped in between cannot redirect it.
 *
 * Refused: anything outside [allowedRoots]; another app's `Android/data` or `Android/obb` folder; the
 * cache folders that hold vault plaintext ([blockedDirs]).
 *
 * The 2.15.x tested "is it ours" with `contains("/Android/data/<package>")`, which also matched a
 * `<package>.anything` folder of ANOTHER app; the folder name is now compared whole.
 */
class PathGuard(
    private val allowedRoots: List<File>,
    private val blockedDirs: List<File>,
    private val packageName: String,
) {
    /** The resolved file if it may leave the app, null otherwise (or when it cannot be resolved). */
    fun resolve(file: File): File? {
        val canonical = try {
            file.canonicalFile
        } catch (expected: IOException) {
            return null
        }
        // Compared component by component (Path.startsWith): `vault_decrypted` is not under `vault_decrypt`.
        val path = canonical.toPath()
        return canonical.takeIf {
            !isOtherAppsFolder(path) &&
                blockedDirs.none { dir -> path.startsWith(dir.toPath()) } &&
                allowedRoots.any { root -> path.startsWith(root.toPath()) }
        }
    }

    private fun isOtherAppsFolder(path: Path): Boolean {
        val segments = path.map(Path::toString)
        return segments.indices.any { i ->
            segments[i] == "Android" &&
                segments.getOrNull(i + 1) in PRIVATE_FOLDERS &&
                segments.getOrNull(i + 2).let { it != null && it != packageName }
        }
    }

    private companion object {
        val PRIVATE_FOLDERS = setOf("data", "obb")
    }
}
