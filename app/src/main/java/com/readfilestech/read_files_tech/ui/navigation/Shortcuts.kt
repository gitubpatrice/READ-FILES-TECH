package com.readfilestech.read_files_tech.ui.navigation

/**
 * The `shortcut` extra the quick-settings tiles put on the launch intent (`QuickTiles.kt` of the 2.15.x),
 * read through a STRICT allow-list: the activity is exported, any app can start it with any extra, and
 * only these three values ever lead anywhere.
 */
object Shortcuts {
    const val EXTRA = "shortcut"

    private val ALLOWED = mapOf(
        "scanner" to Feature.SCANNER,
        "ocr" to Feature.OCR,
        "vault" to Feature.VAULT,
    )

    /** The features a tile opens; the others (an APK's installer) are never reached by an extra. */
    val TILE_FEATURES: Set<Feature> = ALLOWED.values.toSet()

    fun featureOf(value: String?): Feature? = ALLOWED[value]

    /** The value a tile puts in the extra for [feature], one of [TILE_FEATURES]. */
    fun valueOf(feature: Feature): String =
        requireNotNull(ALLOWED.entries.find { it.value == feature }?.key) { "$feature has no tile" }
}
