package com.readfilestech.read_files_tech.data.convert

import android.graphics.Bitmap
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.readfilestech.read_files_tech.data.images.ExifOrientation
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfConnection
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfProtocol
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusalException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * PDF → TXT: [file] opened in the `:pdf` process with [password] (empty: none) — null when it is not its
 * password. Blocking (Binder). The main process never parses the file: it gets a page count, then texts.
 *
 * @throws PdfRefusalException CERTIFICATE (no password opens it), READER_STOPPED.
 * @throws com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException an unknown encryption.
 * @throws com.readfilestech.read_files_tech.data.viewer.UnreadableFileException damaged, or not a PDF.
 */
fun PdfConnection.openText(file: File, password: String): PdfTextDocument? =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        call(PdfProtocol.TEXT_OPEN, { data ->
            data.writeFileDescriptor(descriptor.fileDescriptor)
            data.writeString(password)
        }) { reply ->
            when (val status = reply.readInt()) {
                PdfProtocol.OK -> PdfTextDocument(this, reply.readInt(), reply.readInt())
                PdfProtocol.WRONG_PASSWORD -> null
                else -> throw failure(status)
            }
        }
    }

/** Images → PDF: a new, empty document in the `:pdf` process. Blocking (Binder). */
fun PdfConnection.newImagesPdf(): PdfImagesDocument = call(PdfProtocol.IMAGES_START, {}) { reply ->
    when (val status = reply.readInt()) {
        PdfProtocol.OK -> PdfImagesDocument(this, reply.readInt())
        else -> throw failure(status)
    }
}

/** A PDF open in the `:pdf` process for its text; its calls block (Binder), [close] never waits. */
class PdfTextDocument internal constructor(private val connection: PdfConnection, private val session: Int, val pageCount: Int) :
    AutoCloseable {
    /** Page [index]'s text (0 is the first), to [onChunk] piece by piece, in order. */
    fun page(index: Int, onChunk: (String) -> Unit) {
        var from = 0
        var more = true
        while (more) {
            val chunk = connection.call(PdfProtocol.TEXT_PAGE, { data ->
                data.writeInt(session)
                data.writeInt(index)
                data.writeInt(from)
            }) { reply ->
                when (val status = reply.readInt()) {
                    PdfProtocol.OK -> TextChunk(reply.readString().orEmpty(), reply.readInt() == 1)
                    else -> throw connection.failure(status)
                }
            }
            onChunk(chunk.text)
            from += chunk.text.length
            // An answer that adds nothing ends the page, whatever it says follows.
            more = chunk.more && chunk.text.isNotEmpty()
        }
    }

    override fun close() = connection.send(PdfProtocol.CLOSE) { it.writeInt(session) }
}

/** A PDF of images being made in the `:pdf` process; its calls block (Binder), [close] never waits. */
class PdfImagesDocument internal constructor(private val connection: PdfConnection, private val session: Int) : AutoCloseable {
    /** A page of the JPEG [file], copied as it is, upright by [orientation]. */
    fun addJpeg(file: File, orientation: ExifOrientation) =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            add { data ->
                data.writeInt(PdfProtocol.IMAGE_JPEG)
                data.writeFileDescriptor(descriptor.fileDescriptor)
                data.writeInt(if (orientation.mirrored) 1 else 0)
                data.writeInt(orientation.degrees)
            }
        }

    /** A page of [bitmap]'s pixels, upright, without loss; its pixels cross over ashmem, outside the Binder buffer. */
    fun addBitmap(bitmap: Bitmap) = add { data ->
        data.writeInt(PdfProtocol.IMAGE_PIXELS)
        bitmap.writeToParcel(data, 0)
    }

    /**
     * The document, written on [out] (not closed) through a pipe this process drains while the `:pdf`
     * process writes it: an output is written by the main process (OutputStorage, atomically), and the
     * PDF never sits whole in either process.
     */
    fun save(out: OutputStream) {
        val (reading, writing) = ParcelFileDescriptor.createPipe()
        var drained: IOException? = null
        val drain = thread(name = "images-pdf") {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(reading).use { it.copyTo(out, PIPE_BUFFER) }
            } catch (e: IOException) {
                // Its end of the pipe closed: the `:pdf` process stops writing, and says so.
                drained = e
            }
        }
        var called: Throwable? = null
        try {
            writing.use { pipe ->
                connection.call(PdfProtocol.IMAGES_SAVE, { data ->
                    data.writeInt(session)
                    data.writeFileDescriptor(pipe.fileDescriptor)
                }) { reply -> reply.readInt().let { if (it != PdfProtocol.OK) throw connection.failure(it) } }
            }
        } catch (e: IOException) {
            called = e
        } finally {
            // Both ends of the writer closed (ours above, the `:pdf` process's before it answered, or with it):
            // the drain sees the end of the pipe.
            drain.join()
        }
        // Why the writing stopped on this side (a full disk) says more than the refusal it caused there.
        (drained ?: called)?.let { throw it }
    }

    private fun add(write: (Parcel) -> Unit) = connection.call(PdfProtocol.IMAGES_ADD, { data ->
        data.writeInt(session)
        write(data)
    }) { reply -> reply.readInt().let { if (it != PdfProtocol.OK) throw connection.failure(it) } }

    override fun close() = connection.send(PdfProtocol.CLOSE) { it.writeInt(session) }

    private companion object {
        const val PIPE_BUFFER = 64 * 1024
    }
}
