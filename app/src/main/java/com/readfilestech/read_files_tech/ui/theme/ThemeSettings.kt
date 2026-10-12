package com.readfilestech.read_files_tech.ui.theme

import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The theme chosen in the home's menu: applied at once, kept in `theme_mode` as the 2.15.x keeps it. */
@Singleton
class ThemeSettings @Inject constructor(private val prefs: FlutterPreferences) {
    private val mode = MutableStateFlow(ThemeMode.read(prefs))
    val current: StateFlow<ThemeMode> = mode.asStateFlow()

    fun set(value: ThemeMode) {
        prefs.commit { putString(ThemeMode.PREF_KEY, value.prefValue) }
        mode.value = value
    }
}
