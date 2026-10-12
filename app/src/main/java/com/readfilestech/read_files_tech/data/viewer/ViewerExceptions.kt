package com.readfilestech.read_files_tech.data.viewer

import java.io.IOException

/**
 * The file is not what its extension says, or is damaged: a viewer refuses it in words (the 2.15.x
 * showed the parser's exception — "FormatException: …" — inventory §1.C). [reason] is for the log and
 * the tests, never for the screen.
 */
class UnreadableFileException(reason: String, cause: Throwable? = null) : IOException(reason, cause)

/** A format the viewer knows and does not open (a `.doc` in the OLE format of Word 97, for one). */
class UnsupportedFormatException(reason: String) : IOException(reason)
