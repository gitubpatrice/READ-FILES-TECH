package com.readfilestech.read_files_tech

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.data.storage.StorageAccess
import com.readfilestech.read_files_tech.startup.StartupCleanup
import com.readfilestech.read_files_tech.ui.AppRoot
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Shortcuts
import com.readfilestech.read_files_tech.ui.theme.ReadFilesTechTheme
import com.readfilestech.read_files_tech.ui.theme.ThemeSettings
import com.readfilestech.read_files_tech.vault.VaultAutoLock
import com.readfilestech.read_files_tech.vault.VaultService
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
    lateinit var themes: ThemeSettings

    @Inject
    lateinit var startupCleanup: StartupCleanup

    @Inject
    lateinit var autoLock: VaultAutoLock

    @Inject
    lateinit var vault: VaultService

    private val shortcut = MutableStateFlow<Feature?>(null)
    private var accessGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: the manifest's theme is the splash (res/values/themes.xml); this puts the app's
        // window theme in its place, and the splash goes at the first frame.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Before any screen: a scan left by a process that died is kept only when its screen comes back.
        startupCleanup.clearScansOnce(restoring = savedInstanceState != null)
        enableEdgeToEdge()
        accessGranted = access.isGranted()
        // Once: a recreated activity (rotation) must not replay the tile that opened it.
        if (savedInstanceState == null) takeShortcut(intent)
        dropExtras(intent)
        setContent {
            // Followed, not read once: the theme chosen in the settings applies at once.
            val theme by themes.current.collectAsStateWithLifecycle()
            val dark = theme.isDark(isSystemInDarkTheme())
            // The bars' icons follow the APP's theme: left to the system's, a dark theme chosen on a light
            // system drew dark clock and battery icons on the dark app (seen on emulator 36, phase 4).
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            ReadFilesTechTheme(darkTheme = dark) {
                AppRoot(access, messages, vault, shortcut, onShortcutHandled = { shortcut.value = null })
            }
        }
    }

    /** `singleTop`: a tile tapped while the app is open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShortcut(intent)
        dropExtras(intent)
    }

    /**
     * Every other extra dropped, unread: this activity is exported, and the navigation reads its intent
     * for the extras of an explicit deep link (`android-support-nav:controller:deepLinkIds`), which any
     * app could forge to open any screen — the installer among them — with arguments of its choice
     * (security review, phase 3b). The `shortcut` extra was the only entry, already read. Replaced, not
     * unparcelled: another app's unknown Parcelable is never opened.
     */
    private fun dropExtras(intent: Intent?) {
        intent?.replaceExtras(null as Bundle?)
    }

    /** The vault's own locks (VaultAutoLock): the app back on screen, checked before anything is shown. */
    override fun onStart() {
        super.onStart()
        autoLock.appStarted()
    }

    override fun onStop() {
        super.onStop()
        autoLock.appStopped()
    }

    /** Every finger put down on the app, on whatever screen, starts the vault's idle time again (2.15.3). */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) autoLock.touched()
        return super.dispatchTouchEvent(event)
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

    private companion object {
        /** The scrims `enableEdgeToEdge` draws by default behind a three-button navigation bar. */
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
