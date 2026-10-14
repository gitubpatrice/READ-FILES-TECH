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

    /**
     * Rows and columns one sheet of a workbook (.xlsx, .xls, .ods) shows, its first row included: the
     * 2.15.3's (`xlsx_viewer_screen.dart:36-37`, `ods_service.dart:46, 53`). Past them nothing more is read
     * of an .xlsx sheet, and the screen says what is not shown — the 2.15.3 cut silently.
     */
    const val SHEET_ROWS = 5_000
    const val SHEET_COLUMNS = 200

    /** Chars kept of one cell's text: the grid shows two lines of a column 120 dp wide. No 2.15.3 counterpart. */
    const val SHEET_CELL_CHARS = 256

    /**
     * Chars of text one sheet keeps once read — its cells' texts and its workbook's number formats: past it,
     * the sheet is refused. 5 000 rows of 200 cells reach it at 32 chars a cell; it is 64 MiB of heap at
     * most. No 2.15.3 counterpart: it held every sheet of the workbook whole (inventory D21).
     */
    const val SHEET_TEXT_CHARS = 32 * MIB

    /** The longest number format, Excel's own limit: a longer one is shown as "General", never parsed. */
    const val SHEET_FORMAT_CHARS = 255
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
     * Pixels of an animated GIF or WebP the image viewer plays. Android decodes an animation's frames at
     * full size whatever subsampling is asked — on a Galaxy S9 an 8 000 × 8 000 GIF held 256 MB of native
     * memory at its first frame — and keeps more than one frame while it plays. Larger, its first frame
     * shows, subsampled as a still image. 4K UHD: the largest a screen recording or a camera makes. No
     * 2.15.3 counterpart.
     */
    const val ANIMATION_PIXELS = 3840L * 2160

    /**
     * Elements nested in one XML part of a document (docx, odt, odp): deeper, the file is refused. No
     * 2.15.3 counterpart — its regex scan built no tree. Word's deepest real nesting, a text box in a
     * table cell, stays under 30; a million nested elements is refused here instead of being held.
     */
    const val XML_DEPTH = 256

    /**
     * The longest stretch of an XML part between two tags — one text, or one tag with its attributes —
     * in chars: the parser holds a stretch whole before handing it over (Android's KXmlParser in a
     * StringBuilder, about 6 bytes per char at its peak, so 24 MB here). No 2.15.3 counterpart: it decoded
     * the whole part into one String (200 MiB of XML, up to 400 MB of chars), then copied it once per
     * `replaceAll` (`text_extraction_service.dart:155-169`).
     */
    const val XML_STRETCH_CHARS = 4 * MIB

    /**
     * Elements in one XML part of a document: past it, the file holds too many items. What a part costs is
     * its elements, not its bytes: 200 MiB of `<w:p/>` (a 300 KB file, 35 million elements) took 59 s to
     * refuse on an emulator in a debug build, 10.7 s in the release one; refused here, 7.6 s and 1.3 s. Word's
     * densest corpus file, a French text whose every accented letter is a run of its own, holds an element
     * every 37.5 bytes: a real part reaches this bound at 150 MB of XML, three quarters of the part cap.
     * No 2.15.3 counterpart (its regex built no elements).
     */
    const val XML_ELEMENTS = 4_000_000

    /**
     * Bytes of a Markdown file shown rendered; a longer one opens on its source, laid out lazily as the text
     * viewer's. No 2.15.3 counterpart: it rendered the whole file in one widget (`md_viewer_screen.dart`,
     * gpt_markdown), and 50 MiB froze it long before its cap. A README of a large project is under 100 KB;
     * commonmark-java's worst case measured at 1 MiB on the JVM (a link every 7 bytes, a table row every
     * 10) held under 60 MB while it parsed, and took under half a second.
     */
    const val MARKDOWN_RENDERED = 1 * MIB

    /**
     * Blocks nested in a Markdown text (quotes, lists), and inline nodes nested in one paragraph (emphasis,
     * images): deeper markers are shown as text (commonmark-java's maxOpenBlockParsers and maxInlineNesting).
     * No 2.15.3 counterpart; a real document nests a few levels.
     */
    const val MARKDOWN_NESTING = 32

    /**
     * Cells of a Markdown text's tables, counted before it is parsed: the columns of each delimiter row
     * times the lines after it until a blank one, an upper bound. Past it, the text opens on its source.
     * GFM pads every row to the header's columns (commonmark-java's TableBlockParser), so 1 MiB holding a
     * header of 60 000 columns over 100 000 one-character lines would be six billion cells. A cell costs
     * about 140 bytes once parsed (measured): 14 MB here. No 2.15.3 counterpart.
     */
    const val MARKDOWN_TABLE_CELLS = 100_000

    /**
     * Brackets (`[`, `![`) open at once in one paragraph, counted before it is parsed: past it, the text opens
     * on its source. commonmark-java 0.30 takes a time in the square of the images nested in one another —
     * measured on the JVM, 80 000 of them (480 KB) took 4.2 s, 150 000 took 15.5 s; 1 000 take a millisecond.
     * A real text opens a few at once. No 2.15.3 counterpart.
     */
    const val MARKDOWN_OPEN_BRACKETS = 1_000

    /**
     * Local images a Markdown text may show: the first ones it names by a path in its own folder are looked
     * up on the storage when it is rendered, and decoded when they come into view; the others keep their
     * icon. The 2.15.3 had no bound: `Image.file` for each (`md_viewer_screen.dart:87`).
     */
    const val MARKDOWN_IMAGES = 64

    /**
     * Bytes of decoded images the Markdown viewer keeps: past it, the image shown least recently is
     * dropped, decoded again if it comes back into view. Each is decoded at the width it is shown at, at
     * most four widths tall (ImageScale): a phone's screenshot is about 10 MB. No 2.15.3 counterpart.
     */
    const val MARKDOWN_IMAGE_BYTES = 32 * MIB

    /**
     * Values of a JSON file — each scalar, array and object: past it, the file is shown as its text. The
     * viewer keeps 10 bytes per value beside the file's bytes, and 8 per row of its tree: a file of 50 MiB
     * holding this many values in one array keeps 87 MB once opened (measured on the JVM; 3.7 million kept
     * 119 MB). No 2.15.3 counterpart: it decoded the whole file into Dart objects
     * (`json_viewer_screen.dart:41`), then laid out every child of an opened node at once.
     */
    const val JSON_VALUES = 2_000_000

    /**
     * Arrays and objects nested in a JSON file: deeper, it is shown as its text. The default limit of Jackson
     * and of Python's json module (its recursion limit); the 2.15.3's recursive decoder had none, and a file
     * of a million `[` was a stack overflow.
     */
    const val JSON_DEPTH = 1_000

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
