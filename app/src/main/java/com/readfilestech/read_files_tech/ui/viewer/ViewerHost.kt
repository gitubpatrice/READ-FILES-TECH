package com.readfilestech.read_files_tech.ui.viewer

import androidx.compose.runtime.Composable
import com.readfilestech.read_files_tech.ui.MissingScreen
import com.readfilestech.read_files_tech.ui.viewer.csv.CsvViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.document.DocumentViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.html.HtmlViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.image.ImageViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.json.JsonViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.markdown.MarkdownViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.pdf.PdfViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.reader.ReaderViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.sheet.SheetViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.text.TextViewerScreen
import com.readfilestech.read_files_tech.ui.viewer.zip.ZipViewerScreen

/**
 * The screen of each viewer: one branch per [ViewerKind], added with its entry. [onNavigate] leads to
 * another destination of the app (the editor of the text and CSV viewers, the folder a ZIP extraction wrote).
 */
@Composable
fun ViewerHost(kind: ViewerKind?, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    when (kind) {
        ViewerKind.TEXT -> TextViewerScreen(onBack, onNavigate)
        ViewerKind.CSV -> CsvViewerScreen(onBack, onNavigate)
        ViewerKind.IMAGE -> ImageViewerScreen(onBack)
        ViewerKind.ZIP -> ZipViewerScreen(onBack, onNavigate)
        ViewerKind.DOCUMENT -> DocumentViewerScreen(onBack)
        ViewerKind.SHEET -> SheetViewerScreen(onBack)
        ViewerKind.MARKDOWN -> MarkdownViewerScreen(onBack)
        ViewerKind.JSON -> JsonViewerScreen(onBack)
        ViewerKind.HTML -> HtmlViewerScreen(onBack, onNavigate)
        ViewerKind.READER -> ReaderViewerScreen(onBack)
        ViewerKind.PDF -> PdfViewerScreen(onBack)
        // A route naming no viewer (an old one restored after an update): said, not a crash.
        null -> MissingScreen(onBack)
    }
}
