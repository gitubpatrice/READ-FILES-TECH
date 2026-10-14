package com.readfilestech.read_files_tech.ui.navigation

import androidx.annotation.StringRes
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind

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

    /** Installing the APK at [ARG_PATH]: a dialog over the screen that asked. */
    const val INSTALL = "install?$ARG_PATH={$ARG_PATH}"

    fun install(path: String): String = "install?$ARG_PATH=${encode(path)}"

    const val ARG_VIEWER = "viewer"

    /** How the explorer showed the folder of an image it opened: the image viewer's gallery (GalleryOrder). */
    const val ARG_GALLERY = "gallery"

    /** The viewer named [ARG_VIEWER] (a [ViewerKind]), on the file at [ARG_PATH]. */
    const val VIEWER = "viewer/{$ARG_VIEWER}?$ARG_PATH={$ARG_PATH}&$ARG_GALLERY={$ARG_GALLERY}"

    fun viewer(kind: ViewerKind, path: String, gallery: String? = null): String = buildString {
        append("viewer/${kind.name}?$ARG_PATH=${encode(path)}")
        if (gallery != null) append("&$ARG_GALLERY=${encode(gallery)}")
    }

    /**
     * A tool, a tile or a menu entry ([Feature]): its screen, or "not available yet" until its phase. A tool
     * opened on a file — by the explorer's menu (Remove EXIF), by a viewer (the CSV viewer's "Edit") — gets
     * it as [ARG_PATH].
     */
    const val FEATURE = "feature/{$ARG_FEATURE}?$ARG_PATH={$ARG_PATH}"

    /**
     * @param extensions only files of these extensions shown (lower case, no dot); folders always.
     * @param title the caller's name for the place, instead of the explorer's.
     */
    fun explorer(path: String? = null, pick: PickMode? = null, extensions: Set<String>? = null, title: String? = null): String =
        buildString {
            append("explorer")
            val args = buildList {
                if (path != null) add("$ARG_PATH=${encode(path)}")
                if (pick != null) add("$ARG_PICK=${pick.argument}")
                if (extensions != null) add("$ARG_EXTENSIONS=${encode(extensions.joinToString(EXTENSION_SEPARATOR))}")
                if (title != null) add("$ARG_TITLE=${encode(title)}")
            }
            if (args.isNotEmpty()) append(args.joinToString("&", prefix = "?"))
        }

    /**
     * The app's picker (`pickOne` / `pickMany` / `pickFolder` of the 2.15.x): its result comes back as
     * [RESULT_PICKED_FILES] or [RESULT_PICKED_FOLDER] on the caller's entry.
     */
    fun picker(pick: PickMode, extensions: Set<String>? = null, title: String? = null): String = buildString {
        append("picker?$ARG_PICK=${pick.argument}")
        if (extensions != null) append("&$ARG_EXTENSIONS=${encode(extensions.joinToString(EXTENSION_SEPARATOR))}")
        if (title != null) append("&$ARG_TITLE=${encode(title)}")
    }

    /** The extensions of an explorer route, as [explorer] wrote them; an empty set is a filter that keeps folders only. */
    fun extensionsOf(argument: String?): Set<String>? = argument?.split(EXTENSION_SEPARATOR)?.filter(String::isNotEmpty)?.toSet()

    private const val EXTENSION_SEPARATOR = ","

    /**
     * `Uri.encode`, without Android: UTF-8, everything percent-encoded but letters, digits and `_-!.~'()*`
     * — the same set, so the navigation's decoding gives the text back. Pure, the routes are tested on the JVM.
     */
    internal fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and BYTE_MASK
            val char = code.toChar()
            if (code < ASCII_LIMIT && (char.isLetterOrDigit() || char in UNRESERVED)) {
                append(char)
            } else {
                append('%').append(HEX[code shr NIBBLE]).append(HEX[code and NIBBLE_MASK])
            }
        }
    }

    private const val UNRESERVED = "_-!.~'()*"
    private const val HEX = "0123456789ABCDEF"
    private const val BYTE_MASK = 0xFF
    private const val ASCII_LIMIT = 0x80
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0xF

    fun feature(feature: Feature, path: String? = null): String =
        if (path == null) "feature/${feature.name}" else "feature/${feature.name}?$ARG_PATH=${encode(path)}"
}

/**
 * The destinations whose screens come with a later phase: the three quick-settings tiles, the tools of
 * the home's grid, its settings and about (phase 8).
 */
enum class Feature(@param:StringRes val title: Int) {
    SCANNER(R.string.feature_scanner),
    OCR(R.string.feature_ocr),
    VAULT(R.string.feature_vault),
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
