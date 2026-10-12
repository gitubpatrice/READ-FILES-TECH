package com.readfilestech.read_files_tech.ui.viewer

import androidx.compose.runtime.Composable
import com.readfilestech.read_files_tech.ui.PendingScreen
import com.readfilestech.read_files_tech.ui.viewer.text.TextViewerScreen

/** The screen of each viewer: one branch per [ViewerKind], added with its entry. */
@Composable
fun ViewerHost(kind: ViewerKind?, onBack: () -> Unit) {
    when (kind) {
        ViewerKind.TEXT -> TextViewerScreen(onBack)
        // A route naming no viewer (an old one restored after an update): said, not a crash.
        null -> PendingScreen(null, onBack)
    }
}
