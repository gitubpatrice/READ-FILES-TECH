package com.readfilestech.read_files_tech.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.readfilestech.read_files_tech.ui.theme.brandButtonColors

/**
 * A filled button that destroys nothing — "Open", "Create", "Unlock", "Check for updates": white on the
 * checkerboard's blue in both themes ([brandButtonColors]), as in Notes Tech. A destructive action is never one:
 * it is written in red (ConfirmDangerDialog).
 */
@Composable
fun BrandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, colors = brandButtonColors(), content = content)
}
