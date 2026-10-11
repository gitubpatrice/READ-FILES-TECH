package com.readfilestech.read_files_tech.ui.theme

import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead

/** The theme the user chose in the 2.15.x (`theme_mode`: Flutter's `ThemeMode.name`). */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        const val PREF_KEY = "theme_mode"

        /** `light`, `dark` or `system`; anything else, or nothing, follows the system (Flutter's default). */
        fun read(prefs: FlutterPreferences): ThemeMode = when ((prefs.string(PREF_KEY) as? PrefRead.Present)?.value) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }
}
