package com.readfilestech.read_files_tech.ui.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.recents.RecentFile
import com.readfilestech.read_files_tech.data.storage.StorageSpace
import com.readfilestech.read_files_tech.ui.common.formatBytes
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.theme.FileColors
import com.readfilestech.read_files_tech.ui.theme.HomeColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The home tab: storage, "Resume", quick accesses, favourites, the recent files. */
@Composable
fun HomeContent(
    state: HomeUiState,
    onOpen: (String) -> Unit,
    onToggleFavorite: (RecentFile) -> Unit,
    onShare: (FileEntry) -> Unit,
    onRemove: (RecentFile) -> Unit,
    onNavigate: (String) -> Unit,
) {
    val labels = state.quickAccess.associateWith { quickAccessLabel(it.kind) }
    val tiles = state.quickAccess.map { access ->
        QuickTile(labels.getValue(access), quickAccessIcon(access.kind)) {
            onNavigate(Routes.explorer(access.dir.path, extensions = access.extensions, title = labels.getValue(access)))
        }
    } + featureTiles(onNavigate)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        state.space?.let { space -> item { StorageCard(space) } }
        state.recents.firstOrNull()?.let { last ->
            item { SectionTitle(stringResource(R.string.home_resume)) }
            item { ResumeCard(last) { onOpen(last.path) } }
        }
        item { SectionTitle(stringResource(R.string.home_quick_access)) }
        items(tiles.chunked(QUICK_COLUMNS)) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> QuickTileCard(tile, Modifier.weight(1f)) }
                repeat(QUICK_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (state.favorites.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_favorites)) }
            items(state.favorites, key = { "fav:" + it.path }) { file ->
                RecentRow(file, onOpen = { onOpen(file.path) }, menu = { RecentMenu(file, onToggleFavorite, onShare, onRemove) })
            }
        }
        item { SectionTitle(stringResource(R.string.home_recent)) }
        if (state.recents.isEmpty() && state.loaded) {
            item {
                Text(
                    stringResource(R.string.picker_empty_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        items(state.others, key = { "recent:" + it.path }) { file ->
            RecentRow(file, onOpen = { onOpen(file.path) }, menu = { RecentMenu(file, onToggleFavorite, onShare, onRemove) })
        }
    }
}

private class QuickTile(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun featureTiles(onNavigate: (String) -> Unit) = listOf(
    QuickTile(stringResource(R.string.feature_scanner), Icons.Outlined.CameraAlt) {
        onNavigate(Routes.pending(Feature.SCANNER))
    },
    QuickTile(stringResource(R.string.feature_vault), Icons.Outlined.Shield) { onNavigate(Routes.pending(Feature.VAULT)) },
)

@Composable
private fun QuickTileCard(tile: QuickTile, modifier: Modifier) {
    OutlinedCard(onClick = tile.onClick, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(tile.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "{used} used of {total} · {free} free", the bar orange past three quarters, red past nine tenths. */
@Composable
private fun StorageCard(space: StorageSpace) {
    val color = when (HomeLogic.spaceLevel(space)) {
        SpaceLevel.CRITICAL -> MaterialTheme.colorScheme.error
        SpaceLevel.HIGH -> HomeColors.AlmostFull
        SpaceLevel.NORMAL -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.home_storage), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                stringResource(R.string.home_space_used, formatBytes(space.used), formatBytes(space.total)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.home_space_free, formatBytes(space.free)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (space.used.toFloat() / space.total).coerceIn(0f, 1f) },
            color = color,
            // Its own track: in the dark theme the default one is the bar's colour, the used part lost in it.
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(7.dp),
        )
    }
}

/** The last file opened, favourite or not (as the 2.15.x): one tap opens it again. */
@Composable
private fun ResumeCard(file: RecentFile, onOpen: () -> Unit) {
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ExtensionBadge(file)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(recentLine(file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A recent file: its extension, name, when it was opened and its size; [menu] if it has one. */
@Composable
fun RecentRow(file: RecentFile, onOpen: () -> Unit, menu: (@Composable () -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionBadge(file)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(recentLine(file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (menu != null) menu()
    }
}

@Composable
private fun RecentMenu(
    file: RecentFile,
    onToggleFavorite: (RecentFile) -> Unit,
    onShare: (FileEntry) -> Unit,
    onRemove: (RecentFile) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = {
            open = true
        }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (file.isFavorite) R.string.favorite_remove else R.string.favorite_add)) },
                leadingIcon = {
                    Icon(if (file.isFavorite) Icons.Outlined.StarBorder else Icons.Outlined.Star, contentDescription = null)
                },
                onClick = {
                    open = false
                    onToggleFavorite(file)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                onClick = {
                    open = false
                    onShare(FileEntry(file.path, file.name, isDirectory = false, size = file.sizeBytes, modifiedMs = 0, isSymlink = false))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recent_remove)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    onRemove(file)
                },
            )
        }
    }
}

/** The extension in a square of its colour (the explorer's for the same type), a star on a favourite. */
@Composable
private fun ExtensionBadge(file: RecentFile) {
    val extension = FileNames.extension(file.name)
    val color = FileColors.of(extension)
    Box(Modifier.size(44.dp)) {
        Surface(
            color = color.copy(alpha = BADGE_ALPHA),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(40.dp).align(Alignment.Center),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    extension.uppercase().take(MAX_BADGE_CHARS),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (file.isFavorite) {
            Icon(
                Icons.Outlined.Star,
                contentDescription = null,
                tint = HomeColors.Favorite,
                modifier = Modifier.size(16.dp).align(Alignment.TopEnd)
            )
        }
    }
}

/** "{when} · {size}". */
@Composable
private fun recentLine(file: RecentFile): String {
    val locale = LocalConfiguration.current.locales[0]
    val opened = Instant.ofEpochMilli(file.lastOpened.epochMicros() / MICROS_PER_MILLI).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = when (val date = HomeLogic.recentDate(opened, LocalDate.now())) {
        RecentDate.Today -> stringResource(R.string.date_today)
        RecentDate.Yesterday -> stringResource(R.string.date_yesterday)
        is RecentDate.DaysAgo -> pluralStringResource(R.plurals.date_days_ago, date.days, date.days)
        is RecentDate.On -> date.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }
    return stringResource(R.string.recent_line, day, formatBytes(file.sizeBytes))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun quickAccessLabel(kind: QuickAccessKind): String = stringResource(
    when (kind) {
        QuickAccessKind.CAMERA -> R.string.quick_camera
        QuickAccessKind.SCREENSHOTS -> R.string.picker_screenshots
        QuickAccessKind.DOCUMENTS -> R.string.picker_documents
        QuickAccessKind.VIDEOS -> R.string.picker_movies
        QuickAccessKind.DOWNLOADS -> R.string.picker_downloads
        QuickAccessKind.APKS -> R.string.picker_apks
        QuickAccessKind.GALLERY -> R.string.picker_pictures
    },
)

private fun quickAccessIcon(kind: QuickAccessKind): ImageVector = when (kind) {
    QuickAccessKind.CAMERA -> Icons.Outlined.PhotoCamera
    QuickAccessKind.SCREENSHOTS -> Icons.Outlined.Screenshot
    QuickAccessKind.DOCUMENTS -> Icons.Outlined.Description
    QuickAccessKind.VIDEOS -> Icons.Outlined.VideoLibrary
    QuickAccessKind.DOWNLOADS -> Icons.Outlined.Download
    QuickAccessKind.APKS -> Icons.Outlined.Android
    QuickAccessKind.GALLERY -> Icons.Outlined.PhotoLibrary
}

private const val QUICK_COLUMNS = 3
private const val MAX_BADGE_CHARS = 4
private const val BADGE_ALPHA = 0.15f
private const val MICROS_PER_MILLI = 1_000L
