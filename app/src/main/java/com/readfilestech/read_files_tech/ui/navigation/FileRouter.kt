package com.readfilestech.read_files_tech.ui.navigation

import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.viewer.image.GalleryOrder
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind

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
 * A file with a viewer of the app ([ViewerKind], the one table of the extensions each opens) goes to
 * it; any other to another app. An APK goes to the app's own installer screen, whatever the menu: the
 * 2.15.x's "Open" and "Open with…" handed it to the system around its permission check (D23).
 */
object FileRouter {
    private const val APK = "apk"

    /**
     * What the app's viewers open (`FileViewerRouter` of the 2.15.3, the table that disagreed with the
     * home's copy — S6): the home's "Open a file" offers these, `heic` no more — it was offered, then
     * refused (F17). Those whose viewer is not ported yet open in another app; at the end of phase 4,
     * this is the union of the viewers' extensions (ViewerKindTest keeps every viewer's inside it).
     */
    val VIEWABLE: Set<String> = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp",
        "txt", "md", "csv", "xml", "json", "html", "htm", "css", "js", "php", "dart", "yaml", "yml", "log", "ini", "conf",
        "docx", "doc", "odt", "odp", "xlsx", "xls", "ods", "pdf", "zip", "epub",
    )

    /**
     * @param gallery how the explorer shows the folder, given by the explorer only: an image opens among
     *   its siblings, in that order (`FES:401` of the 2.15.3); from anywhere else, alone. The other viewers
     *   ignore it.
     */
    fun targetFor(entry: FileEntry, gallery: GalleryOrder? = null): FileTarget {
        // A folder has no extension (FileEntry): it goes nowhere here.
        val viewer = ViewerKind.forExtension(entry.extension)
        return when {
            entry.extension == APK -> FileTarget.Internal(Routes.install(entry.path))
            viewer != null -> FileTarget.Internal(
                Routes.viewer(viewer, entry.path, gallery?.takeIf { viewer == ViewerKind.IMAGE }?.toArgument()),
            )
            else -> FileTarget.External
        }
    }

    /**
     * "Open with…": another app, chosen by the user — even when a viewer of the app could open it —
     * except an APK, which the system's installer would cancel in silence without the app's permission
     * (Claude review R5).
     */
    fun chooserTargetFor(entry: FileEntry): FileTarget = if (entry.extension == APK) targetFor(entry) else FileTarget.External
}
