package com.readfilestech.read_files_tech.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes

/** A tool of the grid: where it leads — its screen, or "not available yet" until its phase. */
private class Tool(@param:StringRes val label: Int, @param:StringRes val hint: Int, val icon: ImageVector, val route: String)

private fun tool(feature: Feature, @StringRes hint: Int, icon: ImageVector) = Tool(feature.title, hint, icon, Routes.feature(feature))

/** The 20 tools of the 2.15.3 (`_ToolsTab`), in its order. */
private val TOOLS = listOf(
    tool(Feature.COLOR_PICKER, R.string.tool_hint_color_picker, Icons.Outlined.Palette),
    tool(Feature.TXT_TOOLS, R.string.tool_hint_txt_tools, Icons.AutoMirrored.Outlined.TextSnippet),
    tool(Feature.CSV_TOOLS, R.string.tool_hint_csv_tools, Icons.Outlined.TableChart),
    tool(Feature.DIFF, R.string.tool_hint_diff, Icons.Outlined.Difference),
    tool(Feature.HASH, R.string.tool_hint_hash, Icons.Outlined.Fingerprint),
    tool(Feature.ENCODE, R.string.tool_hint_encode, Icons.Outlined.Lock),
    tool(Feature.FORMAT, R.string.tool_hint_format, Icons.Outlined.AutoFixHigh),
    tool(Feature.EDITOR, R.string.tool_hint_editor, Icons.Outlined.Edit),
    tool(Feature.CONTENT_SEARCH, R.string.tool_hint_content_search, Icons.AutoMirrored.Outlined.ManageSearch),
    tool(Feature.CSV_EDITOR, R.string.tool_hint_csv_editor, Icons.Outlined.TableView),
    tool(Feature.ZIP_CREATOR, R.string.tool_hint_zip_creator, Icons.Outlined.FolderZip),
    tool(Feature.CONVERT, R.string.tool_hint_convert, Icons.Outlined.Transform),
    tool(Feature.OCR, R.string.tool_hint_ocr, Icons.Outlined.DocumentScanner),
    tool(Feature.COMPRESS, R.string.tool_hint_compress, Icons.Outlined.Compress),
    tool(Feature.VAULT, R.string.tool_hint_vault, Icons.Outlined.Shield),
    tool(Feature.SCANNER, R.string.tool_hint_scanner, Icons.Outlined.CameraAlt),
    tool(Feature.EXIF, R.string.tool_hint_exif, Icons.Outlined.CleaningServices),
    Tool(R.string.search_title, R.string.tool_hint_global_search, Icons.Outlined.TravelExplore, Routes.SEARCH),
    tool(Feature.DUPLICATES, R.string.tool_hint_duplicates, Icons.Outlined.ContentCopy),
    Tool(R.string.trash_title, R.string.tool_hint_trash, Icons.Outlined.Delete, Routes.TRASH),
)

/** The tools tab: two columns, each tool to its screen. */
@Composable
fun ToolsGrid(onNavigate: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(TOOLS) { tool ->
            OutlinedCard(onClick = { onNavigate(tool.route) }) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    // Two lines: on a 360 dp phone, "Duplikate & große Dateien" lost its last word on one.
                    Text(
                        stringResource(tool.label),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(tool.hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
