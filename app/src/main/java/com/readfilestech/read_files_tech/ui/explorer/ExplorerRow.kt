package com.readfilestech.read_files_tech.ui.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.text.withoutBidiControls
import com.readfilestech.read_files_tech.ui.common.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val IconBox = 36.dp
private const val SELECTED_TINT = 0.12f
private const val DISABLED_ALPHA = 0.38f

/**
 * One row of the explorer (`file_row.dart` of the 2.15.x): a thumbnail for an image, a coloured icon
 * otherwise, a check mark when selected; the name, then size and date.
 *
 * @param menu the row's ⋮ menu, null in selection or folder-choosing mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    entry: FileEntry,
    selected: Boolean,
    enabled: Boolean,
    thumbnails: Thumbnails,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)?,
) {
    // A dense row (the 2.15.x used dense ListTiles): a Material 3 ListItem is 72 dp on two lines, which
    // showed a third fewer files per screen. The ⋮ button keeps its 48 dp touch target.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_TINT) else Color.Transparent)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = RowHeight)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(entry, selected, thumbnails)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                entry.name.withoutBidiControls(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(details(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (menu != null) RowMenuButton(menu) else Spacer(Modifier.width(12.dp))
    }
}

private val RowHeight = 52.dp

@Composable
private fun RowMenuButton(content: @Composable (dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        if (open) content { open = false }
    }
}

@Composable
private fun EntryIcon(entry: FileEntry, selected: Boolean, thumbnails: Thumbnails) {
    val shape = RoundedCornerShape(8.dp)
    if (selected) {
        Box(Modifier.size(IconBox).clip(shape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.entry_selected),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        return
    }
    val isApk = entry.extension == FileVisuals.APK
    val pictured = !entry.isDirectory && (isApk || entry.extension in FileVisuals.IMAGE_EXTENSIONS)
    val thumbnail = if (pictured) thumbnailOf(entry, thumbnails) else null
    if (thumbnail != null) {
        // An app's icon whole, a picture filling its square.
        val scale = if (isApk) ContentScale.Fit else ContentScale.Crop
        Image(thumbnail, contentDescription = null, contentScale = scale, modifier = Modifier.size(IconBox).clip(shape))
        return
    }
    FileTypeIcon(entry.isDirectory, entry.extension)
}

/** The icon of a type of file, in its colour, on a square of that colour: a row of the explorer, or of an archive. */
@Composable
fun FileTypeIcon(isDirectory: Boolean, extension: String) {
    val tint = FileVisuals.color(isDirectory, extension)
    Box(
        Modifier.size(IconBox).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = SELECTED_TINT)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(FileVisuals.icon(isDirectory, extension), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun thumbnailOf(entry: FileEntry, thumbnails: Thumbnails): ImageBitmap? {
    // Twice the box, as `cacheWidth: 72` for a 36 px box in the 2.15.x: sharp on dense screens.
    val sizePx = with(LocalDensity.current) { (IconBox * 2).roundToPx() }
    val thumbnail by produceState(thumbnails.cached(entry.file, entry.modifiedMs), entry.path, entry.modifiedMs) {
        if (value == null) value = thumbnails.load(entry.file, entry.modifiedMs, sizePx)
    }
    return thumbnail
}

/** "1.5 MB · 11/10/2026"; a folder shows its date only (its size would need a walk of its content). */
@Composable
private fun details(entry: FileEntry): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale) }
    val date = if (entry.modifiedMs > 0) formatter.format(Instant.ofEpochMilli(entry.modifiedMs).atZone(ZoneId.systemDefault())) else null
    val size = if (entry.isDirectory) null else formatBytes(entry.size)
    return listOfNotNull(size, date).joinToString(" · ")
}
