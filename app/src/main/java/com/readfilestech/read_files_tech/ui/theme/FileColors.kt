package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.ui.graphics.Color

/** `kDangerRed` of the 2.15.x (#C62828), white text on it: an irreversible action never looks like "Cancel". */
val DangerRed = Color(0xFFC62828)

/** The brands' own colours for "Send to…" and "Edit in PDF Tech" (2.15.x `file_row.dart`). */
object BrandColors {
    val KDrive = Color(0xFF0098FF)
    val ProtonDrive = Color(0xFF6D4AFF)
    val PdfTech = Color(0xFFF44336)
}

/** The home's colours of the 2.15.x (`HomeScreen`): Flutter's Material 2 palette. */
object HomeColors {
    /** `Colors.orange`: the storage bar past three quarters. */
    val AlmostFull = Color(0xFFFF9800)

    /** `Colors.amber`: a favourite's star. */
    val Favorite = Color(0xFFFFC107)
}

/** The colour of a row's icon (`colorFor` of the 2.15.x): Flutter's Material 2 palette, by name. */
object FileColors {
    /** `Colors.amber`. */
    val Folder = Color(0xFFFFC107)

    /** `Colors.grey`, for any other file. */
    val Other = Color(0xFF9E9E9E)

    private val RED = Color(0xFFF44336)
    private val PURPLE = Color(0xFF9C27B0)
    private val YELLOW_700 = Color(0xFFFBC02D)
    private val ORANGE = Color(0xFFFF9800)
    private val BLUE = Color(0xFF2196F3)
    private val DEEP_PURPLE = Color(0xFF673AB7)
    private val BLUE_700 = Color(0xFF1976D2)
    private val GREEN = Color(0xFF4CAF50)

    /** The teal of the 2.15.x APK shortcut. */
    private val TEAL = Color(0xFF00897B)

    private val BY_EXTENSION: Map<String, Color> = buildMap {
        put("pdf", RED)
        listOf("jpg", "jpeg", "png", "gif").forEach { put(it, PURPLE) }
        listOf("js", "ts").forEach { put(it, YELLOW_700) }
        listOf("html", "htm").forEach { put(it, ORANGE) }
        put("css", BLUE)
        put("json", DEEP_PURPLE)
        listOf("docx", "doc").forEach { put(it, BLUE_700) }
        listOf("xlsx", "csv").forEach { put(it, GREEN) }
        put("apk", TEAL)
    }

    fun of(extension: String): Color = BY_EXTENSION[extension] ?: Other
}
