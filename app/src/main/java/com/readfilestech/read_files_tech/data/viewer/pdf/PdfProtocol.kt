package com.readfilestech.read_files_tech.data.viewer.pdf

import android.os.IBinder
import android.os.Parcel

/**
 * The calls between the main process and the `:pdf` process (docs D-029), written by hand on a Binder:
 * a few messages, typed here once for both sides — no AIDL to generate, no Bundle to trust. Every answer
 * starts with a status; the `:pdf` side never lets an exception cross (Binder would carry only a few kinds,
 * and kill the process for the others), and the main side never sees the file's bytes — only a page count,
 * page sizes, bitmaps (through ashmem, outside the Binder buffer), outline entries and, for PDF → TXT, the
 * pages' texts. The conversions TO a PDF go the other way: images handed over, a PDF written back.
 */
internal object PdfProtocol {
    const val DESCRIPTOR = "com.readfilestech.read_files_tech.pdf"

    /** In: the file's descriptor. Out: status; then the session, the page count, the first page's size. */
    const val OPEN = IBinder.FIRST_CALL_TRANSACTION

    /**
     * In: session, page, column width, a region (0 for none, 1 and its four sides), the most pixels.
     * Out: status; then the page's size and the bitmap.
     */
    const val RENDER = OPEN + 1

    /** In: session, the first entry wanted. Out: status; then up to [OUTLINE_CHUNK] entries, and whether more follow. */
    const val OUTLINE = OPEN + 2

    /** In: the encrypted file's descriptor, the password, the descriptor to write the decrypted file to. Out: status. */
    const val UNLOCK = OPEN + 3

    /** One way, no answer: the session — of any kind — is closed, after the call it may be in. */
    const val CLOSE = OPEN + 4

    /**
     * PDF → TXT. In: the file's descriptor, the password (empty: none). Out: status — WRONG_PASSWORD for one
     * that is not it — then the session and the page count.
     */
    const val TEXT_OPEN = OPEN + 5

    /** In: session, page, the first character wanted. Out: status; then up to [TEXT_CHUNK] characters, and whether more follow. */
    const val TEXT_PAGE = OPEN + 6

    /** Images → PDF. In: nothing. Out: status; then the session of a new document. */
    const val IMAGES_START = OPEN + 7

    /**
     * In: session, then [IMAGE_JPEG] with the file's descriptor, mirrored and degrees of its EXIF orientation,
     * or [IMAGE_PIXELS] with a bitmap (over ashmem). Out: status.
     */
    const val IMAGES_ADD = OPEN + 8

    /** In: session, the descriptor to write the PDF to (a pipe the main process drains). Out: status. */
    const val IMAGES_SAVE = OPEN + 9

    /** The last call: the service answers OPEN until it. */
    const val LAST = IMAGES_SAVE

    const val IMAGE_JPEG = 0
    const val IMAGE_PIXELS = 1

    const val OK = 0

    /** PdfRenderer refused the file as encrypted (a password, or a scheme it does not know). */
    const val ENCRYPTED = 1
    const val WRONG_PASSWORD = 2
    const val CERTIFICATE = 3
    const val UNSUPPORTED = 4
    const val UNREADABLE = 5
    const val OUT_OF_MEMORY = 6

    /**
     * Outline entries per answer: a Binder transaction carries at most 1 MB, shared by the process's
     * calls in flight. An entry is at most 256 chars of title and two ints: 500 of them, about 260 KB.
     */
    const val OUTLINE_CHUNK = 500

    /** Characters of a page's text per answer: 128 Ki characters, 256 KB in a Parcel (UTF-16), as [OUTLINE_CHUNK]. */
    const val TEXT_CHUNK = 128 * 1024

    fun writeEntry(parcel: Parcel, entry: OutlineEntry) {
        parcel.writeString(entry.title)
        parcel.writeInt(entry.depth)
        parcel.writeInt(entry.page ?: -1)
    }

    fun readEntry(parcel: Parcel) = OutlineEntry(
        title = parcel.readString().orEmpty(),
        depth = parcel.readInt(),
        page = parcel.readInt().takeIf { it >= 0 },
    )

    fun writeSize(parcel: Parcel, size: PageSize) {
        parcel.writeFloat(size.width)
        parcel.writeFloat(size.height)
    }

    fun readSize(parcel: Parcel) = PageSize(parcel.readFloat(), parcel.readFloat())
}
