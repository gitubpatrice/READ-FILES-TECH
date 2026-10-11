package com.readfilestech.read_files_tech

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.storage.StorageAccess
import com.readfilestech.read_files_tech.ui.AppRoot
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Shortcuts
import com.readfilestech.read_files_tech.ui.theme.ReadFilesTechTheme
import com.readfilestech.read_files_tech.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

/**
 * The launcher activity.
 *
 * ⚠️ Its full name, `com.readfilestech.read_files_tech.MainActivity`, is the one of the Flutter app:
 * the launcher remembers a pinned icon by component name, so renaming or moving this class would make
 * the user's icon disappear at the update (docs D-012). Guarded by MainActivityNameTest.
 *
 * Its entries (docs D-026): the launcher, and the `shortcut` extra of the three quick-settings tiles.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var access: StorageAccess

    @Inject
    lateinit var messages: UserMessages

    @Inject
    lateinit var prefs: FlutterPreferences

    private val shortcut = MutableStateFlow<Feature?>(null)
    private var accessGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        accessGranted = access.isGranted()
        // Once: a recreated activity (rotation) must not replay the tile that opened it.
        if (savedInstanceState == null) takeShortcut(intent)
        val theme = ThemeMode.read(prefs)
        setContent {
            ReadFilesTechTheme(darkTheme = theme.isDark(isSystemInDarkTheme())) {
                AppRoot(access, messages, shortcut, onShortcutHandled = { shortcut.value = null })
            }
        }
    }

    /** `singleTop`: a tile tapped while the app is open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShortcut(intent)
    }

    /**
     * Storage access just granted (back from the settings or the dialog): recreated, as the 2.15.x did —
     * Samsung applies the new permission to the running activity only after it is recreated.
     */
    override fun onResume() {
        super.onResume()
        val granted = access.isGranted()
        val justGranted = granted && !accessGranted
        accessGranted = granted
        if (justGranted) recreate()
    }

    /** Read once, then removed, whatever its value: an extra never replays (2.15.x). */
    // TooGenericExceptionCaught: before Android 13 any getExtra unparcels the whole bundle, and another
    // app's unknown Parcelable throws (BadParcelableException) — enough to kill the process at will
    // (Claude review R5). No suspending call here.
    @Suppress("TooGenericExceptionCaught")
    private fun takeShortcut(intent: Intent?) {
        // Reopened from recents, the task replays its first intent: the tile is not tapped again.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val value = try {
            intent.getStringExtra(Shortcuts.EXTRA)
        } catch (expected: RuntimeException) {
            null
        }
        runCatching { intent.removeExtra(Shortcuts.EXTRA) }
        Shortcuts.featureOf(value)?.let { shortcut.value = it }
    }
}
