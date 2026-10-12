package com.readfilestech.read_files_tech.ui.viewer

import androidx.compose.runtime.Composable
import com.readfilestech.read_files_tech.ui.PendingScreen
import com.readfilestech.read_files_tech.ui.viewer.csv.CsvViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.image.ImageViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.text.TextViewerScreen

/**
 * The screen of each viewer: one branch per [ViewerKind], added with its entry. [onNavigate] leads to
 * another destination of the app (the CSV viewer's editor).
 */
@Composable
fun ViewerHost(kind: ViewerKind?, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    when (kind) {
        ViewerKind.TEXT -> TextViewerScreen(onBack)
        ViewerKind.CSV -> CsvViewerScreen(onBack, onNavigate)
        ViewerKind.IMAGE -> ImageViewerScreen(onBack)
        // A route naming no viewer (an old one restored after an update): said, not a crash.
        null -> PendingScreen(null, onBack)
    }
}
