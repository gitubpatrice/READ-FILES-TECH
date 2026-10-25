package com.readfilestech.read_files_tech.data.convert

import android.content.Context
import android.graphics.Bitmap
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.images.ExifOrientation
import com.readfilestech.read_files_tech.data.images.ImageBounds
import com.readfilestech.read_files_tech.data.scanner.JpegInfo
import com.readfilestech.read_files_tech.data.viewer.pdf.ChannelRandomAccess
import com.readfilestech.read_files_tech.data.viewer.pdf.LockedPdfException
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfBox
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfProtocol
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfSessions
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfUnlock
import com.readfilestech.read_files_tech.data.viewer.pdf.UnlockOutcome
import com.readfilestech.read_files_tech.data.viewer.pdf.of
import java.io.BufferedInputStream

/**
 * The conversions' calls of [PdfProtocol], answered in the `:pdf` process (docs D-029) — PDF → TXT and
 * Images → PDF, everything PdfBox does for them. Their failures are answered by the binder's guard as any
 * call's: a hostile file ends in a status, never in an exception crossing to the main process.
 */
internal class PdfConversionCalls(private val context: Context, private val sessions: PdfSessions) {
    fun serve(code: Int, data: Parcel, reply: Parcel) {
        when (code) {
            PdfProtocol.TEXT_OPEN -> textOpen(data, reply)
            PdfProtocol.TEXT_PAGE -> textPage(data, reply)
            PdfProtocol.IMAGES_START -> imagesStart(reply)
            PdfProtocol.IMAGES_ADD -> imagesAdd(data, reply)
            else -> imagesSave(data, reply)
        }
    }

    private fun textOpen(data: Parcel, reply: Parcel) {
        // The descriptor owned by its stream the moment it is read, as the viewer's unlock (Claude review R14).
        val source = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(data.readFileDescriptor()) { "no file" })
        var taken = false
        val opened = try {
            val password = data.readString().orEmpty()
            PdfBox.ready(context)
            val memory = PdfBox.scratch(context)
            val access = ChannelRandomAccess(source.channel)
            // From here PdfUnlock closes it, whatever happens.
            taken = true
            PdfUnlock.open(access, password, memory)
        } catch (e: LockedPdfException) {
            reply.writeInt(statusOf(e.outcome))
            return
        } finally {
            if (!taken) source.close()
        }
        var kept = false
        try {
            val pages = PdfPageTexts(opened)
            val id = sessions.add(pages)
            kept = true
            reply.writeInt(PdfProtocol.OK)
            reply.writeInt(id)
            reply.writeInt(pages.pageCount)
        } finally {
            if (!kept) opened.close()
        }
    }

    private fun textPage(data: Parcel, reply: Parcel) {
        val pages = sessions.of<PdfPageTexts>(data.readInt())
        val index = data.readInt()
        val from = data.readInt()
        val chunk = pages.chunk(index, from, PdfProtocol.TEXT_CHUNK)
        reply.writeInt(PdfProtocol.OK)
        reply.writeString(chunk.text)
        reply.writeInt(if (chunk.more) 1 else 0)
    }

    private fun imagesStart(reply: Parcel) {
        val id = sessions.add(ImagesPdf(PdfBox.scratch(context)))
        reply.writeInt(PdfProtocol.OK)
        reply.writeInt(id)
    }

    private fun imagesAdd(data: Parcel, reply: Parcel) {
        val document = sessions.of<ImagesPdf>(data.readInt())
        if (data.readInt() == PdfProtocol.IMAGE_JPEG) {
            ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(data.readFileDescriptor()) { "no image" }).use { input ->
                val orientation = ExifOrientation(mirrored = data.readInt() == 1, degrees = data.readInt())
                // Read again here, from the descriptor: what is embedded is what was measured.
                val info = JpegInfo.read(BufferedInputStream(input))
                ImageBounds.check(info.width.toLong(), info.height.toLong())
                input.channel.position(0)
                document.addJpeg(BoundedInputStream(input, FileCaps.IMAGE_FILE), info, orientation)
            }
        } else {
            val bitmap = Bitmap.CREATOR.createFromParcel(data)
            try {
                document.addPixels(BitmapRows(bitmap))
            } finally {
                bitmap.recycle()
            }
        }
        reply.writeInt(PdfProtocol.OK)
    }

    private fun imagesSave(data: Parcel, reply: Parcel) {
        val document = sessions.of<ImagesPdf>(data.readInt())
        val target = requireNotNull(data.readFileDescriptor()) { "no target" }
        // Closed before the answer: the main process reads the pipe to its end, then gets the status.
        ParcelFileDescriptor.AutoCloseOutputStream(target).buffered().use(document::save)
        reply.writeInt(PdfProtocol.OK)
    }

    private fun statusOf(outcome: UnlockOutcome): Int = when (outcome) {
        UnlockOutcome.WRONG_PASSWORD -> PdfProtocol.WRONG_PASSWORD
        UnlockOutcome.CERTIFICATE -> PdfProtocol.CERTIFICATE
        UnlockOutcome.UNSUPPORTED -> PdfProtocol.UNSUPPORTED
        UnlockOutcome.DECRYPTED -> PdfProtocol.OK
    }
}

/** A bitmap the main process decoded (ImageDecoding: upright, within its caps), read a row at a time. */
private class BitmapRows(private val bitmap: Bitmap) : PixelRows {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override val hasAlpha: Boolean get() = bitmap.hasAlpha()

    override fun row(y: Int, argb: IntArray) = bitmap.getPixels(argb, 0, width, 0, y, width, 1)
}
