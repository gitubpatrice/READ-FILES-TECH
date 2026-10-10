package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The app theme. The 2.15.x lets the user pick system / light / dark (a preference read back in the
 * data phase); until then it follows the system, which is also the Flutter default.
 */
@Composable
fun ReadFilesTechTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
