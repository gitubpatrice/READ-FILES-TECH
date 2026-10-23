package com.readfilestech.read_files_tech.ui

import androidx.compose.runtime.Composable
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.tools.color.ColorPickerScreen
import com.readfilestech.read_files_tech.ui.tools.convert.ConvertScreen
import com.readfilestech.read_files_tech.ui.tools.csveditor.CsvEditorScreen
import com.readfilestech.read_files_tech.ui.tools.csvtools.CsvToolsScreen
import com.readfilestech.read_files_tech.ui.tools.diff.DiffScreen
import com.readfilestech.read_files_tech.ui.tools.editor.CodeEditorScreen
import com.readfilestech.read_files_tech.ui.tools.encode.EncodeScreen
import com.readfilestech.read_files_tech.ui.tools.files.ContentSearchScreen
import com.readfilestech.read_files_tech.ui.tools.files.DuplicatesScreen
import com.readfilestech.read_files_tech.ui.tools.files.ZipCreatorScreen
import com.readfilestech.read_files_tech.ui.tools.format.FormatScreen
import com.readfilestech.read_files_tech.ui.tools.hash.HashScreen
import com.readfilestech.read_files_tech.ui.tools.images.CompressScreen
import com.readfilestech.read_files_tech.ui.tools.images.ExifScreen
import com.readfilestech.read_files_tech.ui.tools.ocr.OcrScreen
import com.readfilestech.read_files_tech.ui.tools.scanner.ScannerScreen
import com.readfilestech.read_files_tech.ui.tools.texttools.TxtToolsScreen
import com.readfilestech.read_files_tech.ui.vault.VaultScreen

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
        Feature.ZIP_CREATOR -> ZipCreatorScreen(picked, onNavigate, onBack)
        Feature.DUPLICATES -> DuplicatesScreen(picked, onNavigate, onBack)
        Feature.CONTENT_SEARCH -> ContentSearchScreen(picked, onNavigate, onBack)
        Feature.COLOR_PICKER -> ColorPickerScreen(onBack)
        Feature.CONVERT -> ConvertScreen(picked, onNavigate, onBack)
        Feature.COMPRESS -> CompressScreen(picked, onNavigate, onBack)
        Feature.EXIF -> ExifScreen(picked, onNavigate, onBack)
        Feature.CSV_EDITOR -> CsvEditorScreen(picked, onNavigate, onBack)
        Feature.EDITOR -> CodeEditorScreen(picked, onNavigate, onBack)
        Feature.OCR -> OcrScreen(picked, onNavigate, onBack)
        Feature.TXT_TOOLS -> TxtToolsScreen(picked, onNavigate, onBack)
        Feature.CSV_TOOLS -> CsvToolsScreen(picked, onNavigate, onBack)
        Feature.VAULT -> VaultScreen(picked, onNavigate, onBack)
        // A feature whose screen comes with a later phase, or a route naming none (an old one restored).
        else -> PendingScreen(feature, onBack)
    }
}
