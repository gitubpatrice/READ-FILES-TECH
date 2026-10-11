package com.readfilestech.read_files_tech.ui.navigation

import android.net.Uri

/** The app's destinations. Paths travel URI-encoded: a file name may hold `?`, `&`, `#` or `%`. */
object Routes {
    const val ARG_PATH = "path"
    const val ARG_PICK = "pick"
    const val ARG_FEATURE = "feature"
    const val PICK_FOLDER = "folder"

    /** Set on the PREVIOUS entry's saved state by a folder chooser, read by the explorer that asked. */
    const val RESULT_PICKED_FOLDER = "picked_folder"

    const val EXPLORER = "explorer?$ARG_PATH={$ARG_PATH}&$ARG_PICK={$ARG_PICK}"
    const val TRASH = "trash"
    const val SEARCH = "search"

    /** A screen of a later phase of the port: says so instead of failing (see [Feature]). */
    const val PENDING = "pending/{$ARG_FEATURE}"

    fun explorer(path: String? = null, pickFolder: Boolean = false): String = buildString {
        append("explorer")
        val args = buildList {
            if (path != null) add("$ARG_PATH=${Uri.encode(path)}")
            if (pickFolder) add("$ARG_PICK=$PICK_FOLDER")
        }
        if (args.isNotEmpty()) append(args.joinToString("&", prefix = "?"))
    }

    fun pending(feature: Feature): String = "pending/${feature.name}"
}

/**
 * The destinations reached from phase 3 whose screens come with a later phase: the three quick-settings
 * tiles (scanner, OCR, vault), and the internal viewers.
 */
enum class Feature { SCANNER, OCR, VAULT }
