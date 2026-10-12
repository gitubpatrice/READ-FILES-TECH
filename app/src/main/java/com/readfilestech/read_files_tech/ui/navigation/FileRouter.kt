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
 * extensions here, and nowhere else. An APK goes to the app's own installer screen, whatever the menu:
 * the 2.15.x's "Open" and "Open with…" handed it to the system around its permission check (D23).
 */
object FileRouter {
    private const val APK = "apk"

    /**
     * What the app's viewers open (`FileViewerRouter` of the 2.15.3, the table that disagreed with the
     * home's copy — S6): the home's "Open a file" offers these, `heic` no more — it was offered, then
     * refused (F17). Until phase 4, they open in another app.
     */
    val VIEWABLE: Set<String> = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp",
        "txt", "md", "csv", "xml", "json", "html", "htm", "css", "js", "php", "dart", "yaml", "yml", "log", "ini", "conf",
        "docx", "doc", "odt", "odp", "xlsx", "xls", "ods", "pdf", "zip", "epub",
    )

    fun targetFor(entry: FileEntry): FileTarget = when (entry.extension) {
        APK -> FileTarget.Internal(Routes.install(entry.path))
        else -> FileTarget.External
    }

    /**
     * "Open with…": another app, chosen by the user — even when a viewer of the app could open it —
     * except an APK, which the system's installer would cancel in silence without the app's permission
     * (Claude review R5).
     */
    fun chooserTargetFor(entry: FileEntry): FileTarget = if (entry.extension == APK) targetFor(entry) else FileTarget.External
}
