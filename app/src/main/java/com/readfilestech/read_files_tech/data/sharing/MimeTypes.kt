package com.readfilestech.read_files_tech.data.sharing

/**
 * The MIME type the app announces when it hands a file to another app: `mimeOf` of the 2.15.x
 * (`file_type_helpers.dart`) first, so what opened a file before still opens it. A type it lacked went
 * out as `*` / `*` (every app); the caller now asks Android's own table before falling back to that.
 */
object MimeTypes {
    const val ANY = "*/*"
    const val PDF = "application/pdf"
    const val APK = "application/vnd.android.package-archive"

    private val TABLE = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "mp4" to "video/mp4",
        "mp3" to "audio/mpeg",
        "pdf" to PDF,
        "txt" to "text/plain",
        "html" to "text/html",
        "htm" to "text/html",
        "csv" to "text/csv",
        "zip" to "application/zip",
        "apk" to APK,
        "json" to "application/json",
        "xml" to "application/xml",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    /** The 2.15.x type of [extension] (lower case, no dot), or null when it had none. */
    fun of(extension: String): String? = TABLE[extension]

    /**
     * One type for several files: theirs if they all share it, `image/` + `*` if they are all images
     * of different kinds, [ANY] otherwise — what a share sheet filters its targets on.
     */
    fun common(types: List<String>): String {
        val distinct = types.distinct()
        if (distinct.size == 1) return distinct.single()
        val families = distinct.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1 && families.single() != "*") "${families.single()}/*" else ANY
    }
}
