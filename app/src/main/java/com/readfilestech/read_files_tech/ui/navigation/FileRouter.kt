package com.readfilestech.read_files_tech.ui.navigation

import com.readfilestech.read_files_tech.data.browse.FileEntry

/** Where opening a file leads. */
sealed interface FileTarget {
    /** A screen of the app (a viewer). */
    data class Internal(val route: String) : FileTarget

    /** Another app, through the system (ACTION_VIEW). */
    data object External : FileTarget
}

/**
 * THE one table from a file to what opens it. The 2.15.x had two that disagreed (`FileViewerRouter`
 * and `HomeScreen._screenForExt`, inventory §1.H: bmp, heic, xml and six more were handled by one and
 * not the other — finding S6). Every screen that opens a file asks here.
 *
 * Phase 3: no viewer is ported yet, so every file goes to another app; each viewer of phase 4 adds its
 * extensions here, and nowhere else. An APK says its installer comes with phase 3b: handed to the system
 * without REQUEST_INSTALL_PACKAGES, it was cancelled by the installer without a word (Claude review R4).
 */
object FileRouter {
    private val INSTALLER = FileTarget.Internal(Routes.pending(Feature.APK_INSTALL))

    fun targetFor(entry: FileEntry): FileTarget = when (entry.extension) {
        "apk" -> INSTALLER
        else -> FileTarget.External
    }

    /**
     * "Open with…": another app, chosen by the user — even when a viewer of the app could open it —
     * except an APK, which the system's installer would cancel in silence (Claude review R5).
     */
    fun chooserTargetFor(entry: FileEntry): FileTarget = if (entry.extension == "apk") INSTALLER else FileTarget.External
}
