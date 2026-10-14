package com.readfilestech.read_files_tech.ui.tools

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.tools.TooManyLinesException
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText

private const val MIB = 1024L * 1024

/** Why the file [name] could not be read by a tool, said with its name: too large, too many lines, or the system's reason. */
fun readFailure(name: String, error: Throwable): UiText = when (error) {
    is FileTooLargeException -> uiText(R.string.tool_too_large, name, error.capBytes / MIB, uiText(R.string.unit_megabytes))
    is TooManyLinesException -> uiText(R.string.diff_too_many_lines, name, error.limit)
    else -> uiText(R.string.tool_read_failed, name, IoErrors.reasonOf(error))
}
