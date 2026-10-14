package com.readfilestech.read_files_tech.ui.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.TextPreview
import com.readfilestech.read_files_tech.data.images.ImageToolRules
import com.readfilestech.read_files_tech.ui.theme.BrandColors
import com.readfilestech.read_files_tech.ui.theme.DangerRed

/**
 * A file's menu, in the 2.15.x order. Not yet here: "Edit" (the editor, phase 5) — it comes back with its
 * screen. "Remove metadata" is offered on the formats that carry EXIF (the 2.15.x also on GIF and BMP).
 */
@Composable
fun FileMenu(entry: FileEntry, kDrive: Boolean, proton: Boolean, onAction: (EntryAction, FileEntry) -> Unit, dismiss: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = dismiss) {
        val item = MenuItems(entry, onAction, dismiss)
        item(EntryAction.OPEN, R.string.action_open, Icons.AutoMirrored.Outlined.OpenInNew)
        item(EntryAction.OPEN_WITH, R.string.action_open_with, Icons.Outlined.Apps)
        if (entry.extension in TextPreview.EXTENSIONS) item(EntryAction.PREVIEW, R.string.action_preview, Icons.Outlined.Visibility)
        if (entry.extension == "pdf") {
            item(EntryAction.EDIT_IN_PDF_TECH, R.string.action_edit_in_pdf_tech, Icons.Outlined.PictureAsPdf, BrandColors.PdfTech)
        }
        if (entry.extension in ImageToolRules.METADATA_EXTENSIONS) {
            item(EntryAction.STRIP_METADATA, R.string.action_strip_metadata, Icons.Outlined.CleaningServices)
        }
        item(EntryAction.SHARE, R.string.action_share, Icons.Filled.Share)
        if (kDrive) item(EntryAction.SEND_TO_KDRIVE, R.string.action_send_to_kdrive, Icons.Outlined.CloudUpload, BrandColors.KDrive)
        if (proton) item(EntryAction.SEND_TO_PROTON, R.string.action_send_to_proton, Icons.Outlined.CloudUpload, BrandColors.ProtonDrive)
        HorizontalDivider()
        item(EntryAction.RENAME, R.string.action_rename, Icons.Outlined.DriveFileRenameOutline)
        item(EntryAction.COPY_TO, R.string.action_copy_to, Icons.Outlined.ContentCopy)
        item(EntryAction.MOVE_TO, R.string.action_move_to, Icons.AutoMirrored.Filled.DriveFileMove)
        item(EntryAction.INFO, R.string.action_info, Icons.Outlined.Info)
        HorizontalDivider()
        DeleteItems(item)
    }
}

/** A folder's menu, as the 2.15.x: rename, info, trash, delete. */
@Composable
fun FolderMenu(entry: FileEntry, onAction: (EntryAction, FileEntry) -> Unit, dismiss: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = dismiss) {
        val item = MenuItems(entry, onAction, dismiss)
        item(EntryAction.RENAME, R.string.action_rename, Icons.Outlined.DriveFileRenameOutline)
        item(EntryAction.INFO, R.string.action_info, Icons.Outlined.Info)
        HorizontalDivider()
        DeleteItems(item)
    }
}

/** "Move to trash" first, "Delete permanently" in red under it: never mistaken for each other. */
@Composable
private fun DeleteItems(item: MenuItems) {
    item(EntryAction.TRASH, R.string.action_trash, Icons.Outlined.Delete)
    item(EntryAction.DELETE_FOREVER, R.string.action_delete_forever, Icons.Filled.DeleteForever, DangerRed, coloredText = true)
}

/** The items of one row's menu: each closes the menu, then acts on the row's entry. */
private class MenuItems(
    private val entry: FileEntry,
    private val onAction: (EntryAction, FileEntry) -> Unit,
    private val dismiss: () -> Unit,
) {
    @Composable
    operator fun invoke(action: EntryAction, label: Int, icon: ImageVector, tint: Color? = null, coloredText: Boolean = false) {
        DropdownMenuItem(
            text = { Text(stringResource(label), color = if (coloredText && tint != null) tint else Color.Unspecified) },
            // Without a tint, the item's own icon colour (the menu's theme), not one read outside it.
            leadingIcon = { Icon(icon, contentDescription = null, tint = tint ?: LocalContentColor.current) },
            onClick = {
                dismiss()
                onAction(action, entry)
            },
        )
    }
}
