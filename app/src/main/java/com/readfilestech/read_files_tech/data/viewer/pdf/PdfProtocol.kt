package com.readfilestech.read_files_tech.data.viewer.pdf

import android.os.IBinder
import android.os.Parcel

/**
 * The calls between the main process and the `:pdf` process (docs D-029), written by hand on a Binder:
 * a few messages, typed here once for both sides — no AIDL to generate, no Bundle to trust. Every answer
 * starts with a status; the `:pdf` side never lets an exception cross (Binder would carry only a few kinds,
 * and kill the process for the others), and the main side never sees the file's bytes — only a page count,
 * page sizes, bitmaps (through ashmem, outside the Binder buffer) and outline entries.
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

    /** One way, no answer: the session is closed, after the render it may be in. */
    const val CLOSE = OPEN + 4

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
