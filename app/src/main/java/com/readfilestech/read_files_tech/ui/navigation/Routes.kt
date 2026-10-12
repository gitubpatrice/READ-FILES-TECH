package com.readfilestech.read_files_tech.ui.navigation

import android.net.Uri
import com.readfilestech.read_files_tech.ui.explorer.PickMode

/** The app's destinations. Paths travel URI-encoded: a file name may hold `?`, `&`, `#` or `%`. */
object Routes {
    const val ARG_PATH = "path"
    const val ARG_PICK = "pick"
    const val ARG_EXTENSIONS = "ext"
    const val ARG_TITLE = "title"
    const val ARG_FEATURE = "feature"

    /** Set on the PREVIOUS entry's saved state by a folder chooser, read by the explorer that asked. */
    const val RESULT_PICKED_FOLDER = "picked_folder"

    /** The same for files: an ArrayList of paths, in the order chosen. */
    const val RESULT_PICKED_FILES = "picked_files"

    const val EXPLORER = "explorer?$ARG_PATH={$ARG_PATH}&$ARG_PICK={$ARG_PICK}&$ARG_EXTENSIONS={$ARG_EXTENSIONS}&$ARG_TITLE={$ARG_TITLE}"
    const val TRASH = "trash"
    const val SEARCH = "search"

    /** A screen of a later phase of the port: says so instead of failing (see [Feature]). */
    const val PENDING = "pending/{$ARG_FEATURE}"

    /**
     * @param extensions only files of these extensions shown (lower case, no dot); folders always.
     * @param title the caller's name for the place, instead of the explorer's.
     */
    fun explorer(path: String? = null, pick: PickMode? = null, extensions: Set<String>? = null, title: String? = null): String =
        buildString {
            append("explorer")
            val args = buildList {
                if (path != null) add("$ARG_PATH=${Uri.encode(path)}")
                if (pick != null) add("$ARG_PICK=${pick.argument}")
                if (extensions != null) add("$ARG_EXTENSIONS=${Uri.encode(extensions.joinToString(EXTENSION_SEPARATOR))}")
                if (title != null) add("$ARG_TITLE=${Uri.encode(title)}")
            }
            if (args.isNotEmpty()) append(args.joinToString("&", prefix = "?"))
        }

    /** The extensions of an explorer route, as [explorer] wrote them; an empty set is a filter that keeps folders only. */
    fun extensionsOf(argument: String?): Set<String>? = argument?.split(EXTENSION_SEPARATOR)?.filter(String::isNotEmpty)?.toSet()

    private const val EXTENSION_SEPARATOR = ","

    fun pending(feature: Feature): String = "pending/${feature.name}"
}

/**
 * The destinations reached from phase 3 whose screens come with a later phase: the three quick-settings
 * tiles (scanner, OCR, vault) and installing an APK (phase 3b).
 */
enum class Feature { SCANNER, OCR, VAULT, APK_INSTALL }
