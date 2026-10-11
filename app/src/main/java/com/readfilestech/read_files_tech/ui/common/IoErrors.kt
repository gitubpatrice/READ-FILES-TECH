package com.readfilestech.read_files_tech.ui.common

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.InvalidFileNameException
import com.readfilestech.read_files_tech.data.files.NoFreeNameException
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/**
 * Why a file operation failed, in words the user can act on. The 2.15.x showed the exception itself
 * ("Impossible de renommer ce fichier : FileSystemException: …"); a raw exception on screen is
 * English, technical, and was banned across the apps after Notes Tech 2.0.9.
 */
object IoErrors {
    fun reasonOf(error: Throwable): UiText = uiText(
        when (error) {
            is InvalidFileNameException -> R.string.error_invalid_name
            is FileAlreadyExistsException -> R.string.error_already_exists
            is NoFreeNameException -> R.string.error_no_free_name
            is AccessDeniedException -> R.string.error_access_denied
            is NoSuchFileException -> R.string.error_not_found
            // java.io says "not found" for a refused write too ("open failed: EACCES", "EROFS" on an SD
            // card under Android 8-10): its errno first, "not found" only when it gives none (Claude R5).
            is FileNotFoundException -> reasonOfMessage(error.message).takeIf {
                it != R.string.error_io
            } ?: R.string.error_not_found
            is FileSystemException -> reasonOfMessage(error.reason)
            is IOException -> reasonOfMessage(error.message)
            else -> R.string.error_unexpected
        },
    )

    /** The libcore message carries the errno text ("Read-only file system", "No space left on device"). */
    private fun reasonOfMessage(message: String?): Int {
        val text = message.orEmpty()
        return when {
            "Read-only file system" in text -> R.string.error_read_only
            "No space left" in text -> R.string.error_no_space
            "Permission denied" in text -> R.string.error_access_denied
            "File name too long" in text -> R.string.error_name_too_long
            else -> R.string.error_io
        }
    }
}
