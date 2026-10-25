package com.readfilestech.read_files_tech.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.common.SectionCard
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.theme.ToolColors
import com.readfilestech.read_files_tech.ui.theme.TwoTone

/** A tool of the grid: its name, what it does, its icon in its colour, and where it leads. */
private class Tool(
    @param:StringRes val label: Int,
    @param:StringRes val hint: Int,
    val icon: ImageVector,
    val tone: TwoTone,
    val route: String,
)

private fun tool(feature: Feature, @StringRes hint: Int, icon: ImageVector, tone: TwoTone) =
    Tool(feature.title, hint, icon, tone, Routes.feature(feature))

/** The 20 tools of the 2.15.3 (`_ToolsTab`), in its order and its colours; the trash in a neutral blue-grey. */
private val TOOLS = listOf(
    tool(Feature.COLOR_PICKER, R.string.tool_hint_color_picker, Icons.Outlined.Palette, ToolColors.Pink),
    tool(Feature.TXT_TOOLS, R.string.tool_hint_txt_tools, Icons.AutoMirrored.Outlined.TextSnippet, ToolColors.BlueGrey),
    tool(Feature.CSV_TOOLS, R.string.tool_hint_csv_tools, Icons.Outlined.TableChart, ToolColors.Green),
    tool(Feature.DIFF, R.string.tool_hint_diff, Icons.Outlined.Difference, ToolColors.Indigo),
    tool(Feature.HASH, R.string.tool_hint_hash, Icons.Outlined.Fingerprint, ToolColors.Teal),
    tool(Feature.ENCODE, R.string.tool_hint_encode, Icons.Outlined.Lock, ToolColors.Orange),
    tool(Feature.FORMAT, R.string.tool_hint_format, Icons.Outlined.AutoFixHigh, ToolColors.Purple),
    tool(Feature.EDITOR, R.string.tool_hint_editor, Icons.Outlined.Edit, ToolColors.Cyan),
    tool(Feature.CONTENT_SEARCH, R.string.tool_hint_content_search, Icons.AutoMirrored.Outlined.ManageSearch, ToolColors.DeepOrange),
    tool(Feature.CSV_EDITOR, R.string.tool_hint_csv_editor, Icons.Outlined.TableView, ToolColors.Green),
    tool(Feature.ZIP_CREATOR, R.string.tool_hint_zip_creator, Icons.Outlined.FolderZip, ToolColors.Orange),
    tool(Feature.CONVERT, R.string.tool_hint_convert, Icons.Outlined.Transform, ToolColors.DeepPurple),
    tool(Feature.OCR, R.string.tool_hint_ocr, Icons.Outlined.DocumentScanner, ToolColors.Blue),
    tool(Feature.COMPRESS, R.string.tool_hint_compress, Icons.Outlined.Compress, ToolColors.Amber),
    tool(Feature.VAULT, R.string.tool_hint_vault, Icons.Outlined.Shield, ToolColors.Red),
    tool(Feature.SCANNER, R.string.tool_hint_scanner, Icons.Outlined.CameraAlt, ToolColors.Teal),
    tool(Feature.EXIF, R.string.tool_hint_exif, Icons.Outlined.CleaningServices, ToolColors.Brown),
    Tool(R.string.search_title, R.string.tool_hint_global_search, Icons.Outlined.TravelExplore, ToolColors.Indigo, Routes.SEARCH),
    tool(Feature.DUPLICATES, R.string.tool_hint_duplicates, Icons.Outlined.ContentCopy, ToolColors.DeepOrange),
    Tool(R.string.trash_title, R.string.tool_hint_trash, Icons.Outlined.Delete, ToolColors.BlueGrey, Routes.TRASH),
)

private const val COLUMNS = 2

/**
 * The tools tab: two columns, each tool a card to its screen — its icon in a square tinted with its colour, as App
 * Manager Tech's grid. Rows, not a lazy grid: the two cards of a row take the height of the taller (a title on two
 * lines), which a lazy grid does not give; twenty cards need no laziness.
 */
@Composable
fun ToolsGrid(onNavigate: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TOOLS.chunked(COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tool ->
                    ToolCard(tool, tint = tool.tone.on(scheme), modifier = Modifier.weight(1f).fillMaxHeight()) {
                        onNavigate(tool.route)
                    }
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A tool: its icon, 22 dp in a 48 dp square tinted at 14 % — the square gives every icon the same weight, whatever
 * its glyph —, its name in `onSurface` (the tool's colour would not read as text: amber is 1.8:1 on white), and
 * what it does. One button for TalkBack: the card.
 */
@Composable
private fun ToolCard(tool: Tool, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    SectionCard(modifier = modifier, onClick = onClick) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(Modifier.size(48.dp), shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = ICON_TINT_ALPHA)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(tool.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            // Two lines: on a 360 dp phone, "Duplikate & große Dateien" lost its last word on one.
            Text(
                stringResource(tool.label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(tool.hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val ICON_TINT_ALPHA = 0.14f
