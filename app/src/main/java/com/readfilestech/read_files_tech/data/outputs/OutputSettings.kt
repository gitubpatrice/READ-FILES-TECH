package com.readfilestech.read_files_tech.data.outputs

import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/**
 * What the settings change about the outputs (`SettingsScreen` of the 2.15.3): their folder and the automatic
 * share, in the preferences [OutputStorage] reads — the 2.15.x's keys, so either app finds the other's choice.
 *
 * @param volumes the roots of the mounted volumes: a chosen folder must be on one of them.
 */
class OutputSettings(
    private val prefs: FlutterPreferences,
    private val outputs: OutputStorage,
    private val volumes: () -> List<File>,
) {
    /** What became of a folder chosen for the outputs. */
    sealed interface Choice {
        /** Kept, as [folder] (its canonical path). */
        data class Accepted(val folder: File) : Choice

        /** Not on a volume of the phone's storage. */
        data object NotOnStorage : Choice

        /** Under `Android/data` or `Android/obb`: other apps' folders, closed to this one since Android 11. */
        data object AppFolder : Choice

        /** A folder the app cannot write to (a read-only USB key, a card mounted read-only). */
        data object NotWritable : Choice

        /** The preference could not be written. */
        data object NotSaved : Choice
    }

    /**
     * [folder] as the outputs' base, if it is a folder of the phone's storage this app may write to. The 2.15.3
     * kept any path under `/storage/` (`setBasePath`) — a USB key mounted read-only included, which then sent
     * every output to a fallback without a word but the settings' warning.
     */
    fun choose(folder: File): Choice {
        val canonical = canonicalOrNull(folder)
        val root = canonical?.let { chosen -> volumes().mapNotNull(::canonicalOrNull).firstOrNull { chosen.isUnder(it) } }
        if (canonical == null || root == null) return Choice.NotOnStorage
        // The storage of Android 11+ compares names without case: `android/DATA` is `Android/data`.
        val inside = canonical.toRelativeString(root).split(File.separatorChar).map { it.lowercase(Locale.ROOT) }
        return when {
            inside.size >= 2 && inside[0] == "android" && inside[1] in APP_FOLDERS -> Choice.AppFolder
            !Files.isDirectory(canonical.toPath()) || !OutputStorage.canWriteIn(canonical) -> Choice.NotWritable
            prefs.commit { putString(OutputStorage.KEY_BASE_PATH, canonical.path) } -> Choice.Accepted(canonical)
            else -> Choice.NotSaved
        }
    }

    /** Back to the default folder: the preference removed, so the default follows the build (docs D-017). */
    fun reset(): Boolean = prefs.commit { remove(OutputStorage.KEY_BASE_PATH) }

    fun setAutoShare(on: Boolean): Boolean = prefs.commit { putBoolean(OutputStorage.KEY_AUTO_SHARE, on) }

    /**
     * Creates every category's folder where the outputs go now, so the user finds them in the explorer before
     * the first output (`ensureFolders` of the 2.15.3), and gives that folder: the configured one, or the
     * fallback that took them — null when none did.
     */
    fun prepareFolders(): File? = try {
        OutputCategory.entries.filter(OutputCategory::written).map(outputs::folderFor).first().parentFile
    } catch (expected: NoOutputLocationException) {
        null
    }

    private fun canonicalOrNull(file: File): File? = try {
        file.canonicalFile
    } catch (expected: IOException) {
        null
    }

    private fun File.isUnder(root: File): Boolean =
        this == root || path.startsWith(root.path.trimEnd(File.separatorChar) + File.separatorChar)

    private companion object {
        val APP_FOLDERS = setOf("data", "obb")
    }
}
