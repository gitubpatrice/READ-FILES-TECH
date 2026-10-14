package com.readfilestech.read_files_tech.ui

import androidx.compose.runtime.Composable
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.tools.color.ColorPickerScreen
import com.readfilestech.read_files_tech.ui.tools.diff.DiffScreen
import com.readfilestech.read_files_tech.ui.tools.encode.EncodeScreen
import com.readfilestech.read_files_tech.ui.tools.format.FormatScreen
import com.readfilestech.read_files_tech.ui.tools.hash.HashScreen
import com.readfilestech.read_files_tech.ui.tools.scanner.ScannerScreen

/**
 * The screen of each [Feature] — a tool of the home's grid, a quick-settings tile, a menu entry. A tool
 * of phase 5 adds its branch here when it is ported; the others say "not available yet".
 *
 * @param picked what the picker handed back, for a tool that asked for files or a folder
 * @param onNavigate to another destination (the picker, a viewer, the explorer)
 */
@Composable
fun FeatureHost(feature: Feature?, picked: PickedResults, onNavigate: (String) -> Unit, onBack: () -> Unit) {
    when (feature) {
        Feature.HASH -> HashScreen(picked, onNavigate, onBack)
        Feature.SCANNER -> ScannerScreen(picked, onNavigate, onBack)
        Feature.ENCODE -> EncodeScreen(onBack)
        Feature.FORMAT -> FormatScreen(picked, onNavigate, onBack)
        Feature.DIFF -> DiffScreen(picked, onNavigate, onBack)
        Feature.COLOR_PICKER -> ColorPickerScreen(onBack)
        // A feature whose screen comes with a later phase, or a route naming none (an old one restored).
        else -> PendingScreen(feature, onBack)
    }
}
