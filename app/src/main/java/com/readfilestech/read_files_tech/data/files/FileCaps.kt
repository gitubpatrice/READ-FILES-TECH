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

    /**
     * What the CSV viewer builds from one file, whatever its size: Excel's own limits for a sheet —
     * rows (the header row included), columns, and characters in a cell — so that every CSV Excel opens
     * whole opens whole here. The 2.15.3 had none: it laid every cell out at once (a `DataTable`), and a
     * file of 50 MiB froze it long before its cap. Rows and columns beyond are not shown, and the screen
     * says so; a longer cell is shown, searched and sorted by its first [CSV_CELL_CHARS] characters.
     */
    const val CSV_ROWS = 1_048_576
    const val CSV_COLUMNS = 16_384
    const val CSV_CELL_CHARS = 32_767
    const val IMAGE_FILE = 100 * MIB
    const val IMAGES_TO_PDF_TOTAL = 200 * MIB
    const val ZIP_CREATE_TOTAL = 200 * MIB
    const val ZIP_VIEWER = 500 * MIB

    /**
     * Entries an archive may list. No 2.15.3 equivalent: archive 4 decoded every entry of a 500 MiB file
     * in memory. The listing costs about a hundred bytes and its name per entry; 70 000 entries are
     * listed (corpus), a central directory declaring billions is refused before a record is read.
     */
    const val ZIP_VIEWER_ENTRIES = 200_000

    /**
     * Bytes of central directory read for a listing — its names are what a listing keeps. No 2.15.3
     * equivalent; 200 000 entries with paths of about a hundred characters fit in it.
     */
    const val ZIP_VIEWER_DIRECTORY = 32 * MIB
    const val HTML_VIEWER = 20 * MIB
    const val PDF_SIGNATURE_SOURCE = 100 * MIB
    const val ZIP_EXTRACT_TOTAL = 1024 * MIB

    /** Pixels of a decoded image, whatever its file size. */
    const val IMAGE_PIXELS = 64_000_000L

    /**
     * A first refusal, before opening: the size it reports now. It bounds no read — a file that grows,
     * or a node that reports 0 — so a reader ALSO reads through [CappedInputStream] at the same cap
     * (Claude review R8).
     *
     * @throws FileTooLargeException [file] is larger than [cap].
     */
    fun check(file: File, cap: Long) {
        if (file.length() > cap) throw FileTooLargeException(cap)
    }
}
