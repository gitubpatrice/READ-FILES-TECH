package com.readfilestech.read_files_tech.ui.tools.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import com.readfilestech.read_files_tech.data.scanner.ScanFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A photo asked of the system's camera app — `ACTION_IMAGE_CAPTURE` through the FileProvider, no CAMERA
 * permission (docs D-027) — into a new file of [ScanFiles.Captures], the one folder the FileProvider serves
 * for it: the scanner's photos and the OCR's. The file's path is kept in [handle] under [key], so that a
 * process killed while the camera app is open — likely on a phone with little memory — finds it back.
 */
class CameraCapture(private val context: Context, private val handle: SavedStateHandle, private val key: String) {
    val captures = ScanFiles.under(context.cacheDir).captures

    /** A new empty file, created on [io], its path kept; the URI the camera app may write it through, that file only. */
    suspend fun prepare(io: CoroutineDispatcher): Uri {
        val file = withContext(io) { captures.newFile() }
        handle[key] = file.path
        return uriOf(file)
    }

    /** The camera app is back: its grant ends here. The file it was asked to write into, or null if none was asked. */
    fun returned(): File? {
        val file = handle.remove<String>(key)?.let(::File) ?: return null
        context.revokeUriPermission(uriOf(file), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return file
    }

    /** The file asked for whose photo has not come back: to [ScanFiles.Captures.discard] when the screen goes. */
    fun pending(): File? = handle.get<String>(key)?.let(::File)

    private fun uriOf(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
