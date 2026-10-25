package com.readfilestech.read_files_tech.data.viewer.pdf

import com.tom_roush.pdfbox.io.RandomAccessRead
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * PdfBox's reads of a file the `:pdf` process was handed as a descriptor (docs D-029): POSITIONAL reads
 * (`pread`), through one page of [PAGE] bytes. Never the descriptor's own offset, which the duplicate
 * PdfRenderer reads shares; never a read the size PdfBox asks for — a heap buffer given to a channel goes
 * through a temporary direct buffer of its size, which the thread keeps (TextDocument.readAll). PdfBox's
 * own file reader needs a path, and its stream reader copies the whole file first.
 */
// TooManyFunctions: PdfBox's RandomAccessRead, twelve methods to implement, and the page they read through.
@Suppress("TooManyFunctions")
class ChannelRandomAccess(private val channel: FileChannel) : RandomAccessRead {
    private val length = channel.size()
    private val page: ByteBuffer = ByteBuffer.allocate(PAGE)
    private var pageStart = -1L
    private var pageLength = 0
    private var position = 0L
    private var closed = false

    override fun read(): Int {
        if (!fill()) return -1
        return page.get((position++ - pageStart).toInt()).toInt() and BYTE
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var done = 0
        while (done < len && fill()) {
            val at = (position - pageStart).toInt()
            val count = min(len - done, pageLength - at)
            System.arraycopy(page.array(), at, b, off + done, count)
            position += count
            done += count
        }
        return if (done == 0) -1 else done
    }

    override fun getPosition(): Long = position

    override fun seek(position: Long) {
        checkOpen()
        if (position < 0) throw IOException("seek to $position")
        this.position = position
    }

    override fun length(): Long = length

    override fun isClosed(): Boolean = closed

    override fun peek(): Int = read().also { if (it != -1) position-- }

    override fun rewind(bytes: Int) = seek(position - bytes)

    override fun readFully(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var done = 0
        while (done < length) {
            val count = read(bytes, done, length - done)
            if (count < 0) throw EOFException("$length bytes asked at ${position - done}, $done left")
            done += count
        }
        return bytes
    }

    override fun isEOF(): Boolean = peek() == -1

    override fun available(): Int = (length - position).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** Closes the channel — PdfBox closes its source with its document. */
    override fun close() {
        closed = true
        channel.close()
    }

    /** The page holding [position] is loaded; false at the end of the file. */
    private fun fill(): Boolean {
        checkOpen()
        if (position < length && (position < pageStart || position >= pageStart + pageLength)) {
            pageStart = position - position % PAGE
            page.clear()
            do {
                val read = channel.read(page, pageStart + page.position())
            } while (read >= 0 && page.hasRemaining())
            pageLength = page.position()
        }
        // A file that shrank since it was opened ends where its bytes do.
        return position < length && position < pageStart + pageLength
    }

    private fun checkOpen() {
        if (closed) throw IOException("closed")
    }

    private companion object {
        const val PAGE = 4096
        const val BYTE = 0xFF
    }
}
