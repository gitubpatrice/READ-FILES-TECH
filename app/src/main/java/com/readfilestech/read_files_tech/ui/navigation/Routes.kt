package com.readfilestech.read_files_tech.ui.navigation

import android.net.Uri
import androidx.annotation.StringRes
import com.readfilestech.read_files_tech.R
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

    /** The start: home, tools, explorer (`HomeScreen` of the 2.15.x). */
    const val HOME = "home"
    const val PICKER = "picker?$ARG_PICK={$ARG_PICK}&$ARG_EXTENSIONS={$ARG_EXTENSIONS}&$ARG_TITLE={$ARG_TITLE}"
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

    /**
     * The app's picker (`pickOne` / `pickMany` / `pickFolder` of the 2.15.x): its result comes back as
     * [RESULT_PICKED_FILES] or [RESULT_PICKED_FOLDER] on the caller's entry.
     */
    fun picker(pick: PickMode, extensions: Set<String>? = null, title: String? = null): String = buildString {
        append("picker?$ARG_PICK=${pick.argument}")
        if (extensions != null) append("&$ARG_EXTENSIONS=${Uri.encode(extensions.joinToString(EXTENSION_SEPARATOR))}")
        if (title != null) append("&$ARG_TITLE=${Uri.encode(title)}")
    }

    /** The extensions of an explorer route, as [explorer] wrote them; an empty set is a filter that keeps folders only. */
    fun extensionsOf(argument: String?): Set<String>? = argument?.split(EXTENSION_SEPARATOR)?.filter(String::isNotEmpty)?.toSet()

    private const val EXTENSION_SEPARATOR = ","

    fun pending(feature: Feature): String = "pending/${feature.name}"
}

/**
 * The destinations whose screens come with a later phase: the three quick-settings tiles, the tools of
 * the home's grid, its settings and about (phase 8), and installing an APK until its lot of phase 3b.
 */
enum class Feature(@param:StringRes val title: Int) {
    SCANNER(R.string.feature_scanner),
    OCR(R.string.feature_ocr),
    VAULT(R.string.feature_vault),
    APK_INSTALL(R.string.feature_apk_install),
    COLOR_PICKER(R.string.feature_color_picker),
    TXT_TOOLS(R.string.feature_txt_tools),
    CSV_TOOLS(R.string.feature_csv_tools),
    DIFF(R.string.feature_diff),
    HASH(R.string.feature_hash),
    ENCODE(R.string.feature_encode),
    FORMAT(R.string.feature_format),
    EDITOR(R.string.feature_editor),
    CONTENT_SEARCH(R.string.feature_content_search),
    CSV_EDITOR(R.string.feature_csv_editor),
    ZIP_CREATOR(R.string.feature_zip_creator),
    CONVERT(R.string.feature_convert),
    COMPRESS(R.string.feature_compress),
    EXIF(R.string.feature_exif),
    DUPLICATES(R.string.feature_duplicates),
    SETTINGS(R.string.feature_settings),
    ABOUT(R.string.feature_about),
}
