package com.readfilestech.read_files_tech.data.files

import com.readfilestech.read_files_tech.BuildConfig

/**
 * The names the app uses on SHARED storage, where every build sees the same folders (docs D-017).
 *
 * The `.next` build of the port installs next to the real Read Files Tech: its private storage is its
 * own, but with the all-files permission it would see the real `/storage/emulated/0/.RFT_Corbeille/` and
 * `Files Tech/`. Emptying "its" trash on the S9 or the S24 would erase the user's real deleted files, and
 * a metadata file in a faulty format would be read back by the real 2.15.3. So in `.next` mode both
 * names carry the suffix; only the build that replaces the Flutter app uses the real ones.
 */
class SharedStorageNames(replacesFlutterApp: Boolean = BuildConfig.REPLACES_FLUTTER_APP) {
    private val suffix = if (replacesFlutterApp) "" else NEXT_SUFFIX

    /** `<volume>/.RFT_Corbeille/`: `TrashService.trashDirName` of the 2.15.x. */
    val trashDir = FLUTTER_TRASH_DIR + suffix

    /** `<volume>/Files Tech/`: the outputs and backups folder of the 2.15.x. */
    val filesTechDir = FLUTTER_FILES_TECH_DIR + suffix

    companion object {
        const val FLUTTER_TRASH_DIR = ".RFT_Corbeille"
        const val FLUTTER_FILES_TECH_DIR = "Files Tech"
        const val NEXT_SUFFIX = ".next"
    }
}
