package com.readfilestech.read_files_tech.ui.tools

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.files.MIB
import com.readfilestech.read_files_tech.data.outputs.NoOutputLocationException
import com.readfilestech.read_files_tech.data.tools.TooManyLinesException
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText

/** Why the file [name] could not be read by a tool, said with its name: too large, too many lines, or the system's reason. */
fun readFailure(name: String, error: Throwable): UiText = when (error) {
    is FileTooLargeException -> uiText(R.string.tool_too_large, name, error.capBytes / MIB, uiText(R.string.unit_megabytes))
    is TooManyLinesException -> uiText(R.string.diff_too_many_lines, name, error.limit)
    else -> uiText(R.string.tool_read_failed, name, IoErrors.reasonOf(error))
}

/**
 * The failures of a tool's output that every tool says the same way: no folder takes it (the output folder and
 * its fallbacks refuse it), or the phone ran out of memory making it from [name] — the file worked on, or the
 * output's own name when there is none. Null for any other failure, which each tool says its own way.
 */
fun outputFailure(name: String, error: Throwable): UiText? = when (error) {
    is NoOutputLocationException -> uiText(R.string.convert_no_output)
    is OutOfMemoryError -> uiText(R.string.convert_out_of_memory, name)
    else -> null
}
