package com.readfilestech.read_files_tech

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.readfilestech.read_files_tech.ui.PortInProgressScreen
import com.readfilestech.read_files_tech.ui.theme.ReadFilesTechTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The launcher activity.
 *
 * ⚠️ Its full name, `com.readfilestech.read_files_tech.MainActivity`, is the one of the Flutter app:
 * the launcher remembers a pinned icon by component name, so renaming or moving this class would make
 * the user's icon disappear at the update (docs D-012). Guarded by MainActivityNameTest.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReadFilesTechTheme {
                PortInProgressScreen(versionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}
