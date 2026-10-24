package com.readfilestech.read_files_tech.ui.explorer

import android.content.ClipData
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.TextPreview
import com.readfilestech.read_files_tech.data.sharing.MimeTypes
import com.readfilestech.read_files_tech.data.text.CsvRows
import com.readfilestech.read_files_tech.ui.common.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the info dialog reads once it opens. */
private data class EntryDetails(val size: Long, val modifiedMs: Long?, val accessedMs: Long?, val children: Int?)

/** `showFileInfoDialog` of the 2.15.x: type, extension, MIME, size, dates, link, path — and "copy the path". */
@Composable
fun EntryInfoDialog(entry: FileEntry, onCopied: (String) -> Unit, onDismiss: () -> Unit) {
    val details by produceState<EntryDetails?>(null, entry.path) {
        value = withContext(Dispatchers.IO) { readDetails(entry) }
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                FileVisuals.icon(entry.isDirectory, entry.extension),
                contentDescription = null,
                tint = FileVisuals.color(entry.isDirectory, entry.extension)
            )
        },
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val unknown = stringResource(R.string.info_unknown)
                InfoRow(R.string.info_type, stringResource(if (entry.isDirectory) R.string.info_type_folder else R.string.info_type_file))
                if (!entry.isDirectory) {
                    InfoRow(R.string.info_extension, entry.extension.ifEmpty { "—" })
                    InfoRow(
                        R.string.info_mime,
                        MimeTypes.of(entry.extension) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension) ?: unknown
                    )
                    details?.let {
                        InfoRow(
                            R.string.info_size,
                            stringResource(
                                R.string.info_size_value,
                                formatBytes(it.size),
                                pluralStringResource(R.plurals.bytes_count, it.size.toPluralCount(), it.size)
                            )
                        )
                    }
                }
                details?.children?.let {
                    InfoRow(R.string.info_items, NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(it))
                }
                InfoRow(R.string.info_modified, dateTime(details?.modifiedMs))
                InfoRow(R.string.info_accessed, dateTime(details?.accessedMs))
                if (entry.isSymlink) InfoRow(R.string.info_symlink, stringResource(R.string.yes))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.info_path), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                SelectionContainer { Text(entry.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                // Said and closed once copied: the dialog's scope ends with it (GPT review, phase 8).
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(entry.name, entry.path)))
                    onCopied(entry.path)
                    onDismiss()
                }
            }) { Text(stringResource(R.string.action_copy_path)) }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

private fun readDetails(entry: FileEntry): EntryDetails = try {
    val attributes = Files.readAttributes(entry.file.toPath(), BasicFileAttributes::class.java)
    EntryDetails(
        size = attributes.size(),
        modifiedMs = attributes.lastModifiedTime().toMillis(),
        accessedMs = attributes.lastAccessTime().toMillis(),
        children = if (entry.isDirectory) entry.file.list()?.size else null,
    )
} catch (expected: IOException) {
    EntryDetails(entry.size, entry.modifiedMs.takeIf { it > 0 }, null, null)
}

@Composable
private fun InfoRow(label: Int, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun dateTime(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(
        FormatStyle.SHORT
    ).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

/** What the preview sheet shows. */
private sealed interface PreviewContent {
    data object Loading : PreviewContent

    data class Lines(val lines: List<String>, val csv: Boolean) : PreviewContent

    data object Unreadable : PreviewContent
}

/**
 * "Preview" (`file_preview_sheet.dart`): the first 40 lines of a text file, a CSV as a 10-row table,
 * and "Open" in the corner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(entry: FileEntry, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val content by produceState<PreviewContent>(PreviewContent.Loading, entry.path) {
        value = withContext(Dispatchers.IO) {
            try {
                PreviewContent.Lines(TextPreview.firstLines(entry.file), csv = entry.extension == "csv")
            } catch (expected: IOException) {
                PreviewContent.Unreadable
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(FileVisuals.icon(false, entry.extension), contentDescription = null, tint = FileVisuals.color(false, entry.extension))
            Spacer(Modifier.size(8.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
        }
        HorizontalDivider()
        Column(Modifier.navigationBarsPadding().padding(14.dp).verticalScroll(rememberScrollState())) {
            when (val shown = content) {
                PreviewContent.Loading -> Unit
                PreviewContent.Unreadable -> Text(
                    stringResource(R.string.preview_unreadable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is PreviewContent.Lines -> if (shown.csv) CsvPreview(shown.lines) else TextLines(shown.lines)
            }
        }
    }
}

@Composable
private fun TextLines(lines: List<String>) {
    SelectionContainer {
        Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The first ten rows of a CSV, header shaded (the 2.15.x `_CsvPreview`), split on the separator the file
 * uses — the 2.15.x's `csv` package detected it, and Excel's French CSV is separated by `;`.
 */
@Composable
private fun CsvPreview(lines: List<String>) {
    val text = lines.take(CSV_LINES).joinToString("\n")
    val dialect = CsvRows.dialectOf(text)
    val rows = CsvRows.parse(text.substring(dialect.start), dialect.separator, maxRows = CSV_ROWS)
    if (rows.isEmpty()) {
        Text(stringResource(R.string.preview_empty))
        return
    }
    val columns = rows.maxOf { it.size }
    val cells = rows.map { row -> List(columns) { row.getOrElse(it) { "" }.trim() } }
    // Each column as wide as its widest cell (the header in bold), as Flutter's IntrinsicColumnWidth.
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val widths = (0 until columns).map { column ->
        val widest = cells.withIndex().maxOf { (index, row) ->
            measurer.measure(row[column], style.copy(fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal)).size.width
        }
        with(density) { widest.toDp() } + CELL_PADDING * 2
    }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        cells.forEachIndexed { index, row ->
            Row {
                row.forEachIndexed { column, cell ->
                    Text(
                        cell,
                        style = style,
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .width(widths[column])
                            .then(if (index == 0) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            .padding(horizontal = CELL_PADDING, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private val CELL_PADDING = 8.dp

private const val CSV_LINES = 11
private const val CSV_ROWS = 10

/** A plural is chosen on an Int: a size beyond it is "many" anyway. */
private fun Long.toPluralCount(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
