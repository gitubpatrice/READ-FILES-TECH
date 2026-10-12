package com.readfilestech.read_files_tech.data.files

import java.io.File
import java.io.IOException

/** A file above the cap of what reads it: the screen says so instead of running out of memory. */
class FileTooLargeException(val capBytes: Long) : IOException("file larger than $capBytes bytes")

/**
 * The size caps of the 2.15.3 (`lib/utils/file_caps.dart`), one place for the viewers and tools of
 * phases 4 and 5 — each reads its cap here, never a number of its own. The vault keeps its own caps
 * (phase 2: `VaultFiles.IMPORT_MAX_BYTES` is the 2.15.x's `vaultBackup`, which it documented as a
 * restore cap it never was — inventory S2), the trash its metadata cap (`TrashEntry.META_MAX_BYTES`).
 */
object FileCaps {
    private const val MIB = 1024L * 1024

    const val TEXT_VIEWER = 50 * MIB
    const val DOC_ZIPPED = 50 * MIB
    const val SPREADSHEET = 50 * MIB
    const val EPUB_FILE = 100 * MIB
    const val EPUB_CHAPTER = 50 * MIB

    /** One entry of an archive, once decompressed (zip, ods, epub, docx text). */
    const val ZIP_ENTRY_DECOMPRESSED = 200 * MIB
    const val CSV_FILE = 50 * MIB
    const val IMAGE_FILE = 100 * MIB
    const val IMAGES_TO_PDF_TOTAL = 200 * MIB
    const val ZIP_CREATE_TOTAL = 200 * MIB
    const val ZIP_VIEWER = 500 * MIB
    const val HTML_VIEWER = 20 * MIB
    const val PDF_SIGNATURE_SOURCE = 100 * MIB
    const val ZIP_EXTRACT_TOTAL = 1024 * MIB

    /** Pixels of a decoded image, whatever its file size. */
    const val IMAGE_PIXELS = 64_000_000L

    /** @throws FileTooLargeException [file] is larger than [cap]. */
    fun check(file: File, cap: Long) {
        if (file.length() > cap) throw FileTooLargeException(cap)
    }
}
