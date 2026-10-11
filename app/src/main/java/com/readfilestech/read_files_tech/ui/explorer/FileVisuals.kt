package com.readfilestech.read_files_tech.ui.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Css
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Html
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Javascript
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.readfilestech.read_files_tech.ui.theme.FileColors

/** The icon and colour of a row: `iconFor` and `colorFor` of the 2.15.x (`file_type_helpers.dart`). */
object FileVisuals {
    /** The extensions shown as a thumbnail (`imageExts` of the 2.15.x). */
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

    fun icon(isDirectory: Boolean, extension: String): ImageVector =
        if (isDirectory) Icons.Outlined.Folder else ICONS[extension] ?: Icons.AutoMirrored.Outlined.InsertDriveFile

    fun color(isDirectory: Boolean, extension: String): Color = if (isDirectory) FileColors.Folder else FileColors.of(extension)

    private val ICONS: Map<String, ImageVector> = buildMap {
        put("pdf", Icons.Outlined.PictureAsPdf)
        listOf("jpg", "jpeg", "png", "gif", "webp").forEach { put(it, Icons.Outlined.Image) }
        listOf("mp4", "avi", "mov").forEach { put(it, Icons.Outlined.Videocam) }
        listOf("mp3", "wav", "flac").forEach { put(it, Icons.Outlined.Audiotrack) }
        listOf("zip", "rar", "7z").forEach { put(it, Icons.Outlined.FolderZip) }
        listOf("docx", "doc", "odt").forEach { put(it, Icons.AutoMirrored.Outlined.Article) }
        listOf("xlsx", "xls", "csv").forEach { put(it, Icons.Outlined.TableChart) }
        listOf("html", "htm").forEach { put(it, Icons.Outlined.Html) }
        listOf("js", "ts").forEach { put(it, Icons.Outlined.Javascript) }
        put("css", Icons.Outlined.Css)
        put("json", Icons.Outlined.DataObject)
        put("md", Icons.AutoMirrored.Outlined.TextSnippet)
        put("apk", Icons.Outlined.Android)
    }
}
