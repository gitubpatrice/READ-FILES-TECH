package com.readfilestech.read_files_tech.ui.viewer

/**
 * The app's viewers and the extensions each opens: the table [com.readfilestech.read_files_tech.ui.navigation.FileRouter]
 * reads, and the only one. A viewer of phase 4 adds its entry here and its branch in [ViewerHost]; an
 * extension belongs to one viewer at most, and only to one the home's "Open a file" offers (ViewerKindTest).
 *
 * The names travel in the routes: never rename one (a route saved by the system across a process death
 * would lead nowhere).
 */
enum class ViewerKind(val extensions: Set<String>) {
    /** `TxtViewerScreen` of the 2.15.3: plain text, and the code it coloured (css, js, php, xml, dart). */
    TEXT(setOf("txt", "log", "ini", "conf", "yaml", "yml", "xml", "css", "js", "php", "dart")),

    /** `CsvViewerScreen` of the 2.15.3. */
    CSV(setOf("csv")),

    /** `ImageViewerScreen` of the 2.15.3: a gallery over the images of the folder, zoom, info, share. */
    IMAGE(setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")),

    /** `ZipViewerScreen` of the 2.15.3: the listing, one entry shared, "Extract all". */
    ZIP(setOf("zip")),
    ;

    companion object {
        fun forExtension(extension: String): ViewerKind? = entries.find { extension in it.extensions }

        /** The viewer a route names; null for a name no viewer has (an old route, a forged one). */
        fun fromArgument(name: String?): ViewerKind? = entries.find { it.name == name }
    }
}
