package com.readfilestech.read_files_tech.data.viewer.pdf

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.graphics.createBitmap
import com.readfilestech.read_files_tech.data.convert.PdfConversionCalls
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `:pdf` process's one component (docs D-029, `android:process=":pdf"`, not exported): EVERYTHING
 * that parses a PDF runs here — PdfRenderer (pdfium, native: a crash cannot be caught, it ends its process)
 * and PdfBox. A hostile file that runs this process out of memory or crashes pdfium ends it alone: the
 * main process sees its viewer's calls fail and says so, and lives on with whatever it was doing.
 *
 * The 2.15.3 parsed every PDF in the app's own process (Syncfusion), with no handler for a failure
 * (inventory F25: no `onDocumentLoadFailed`).
 */
class PdfService : Service() {
    private val binder by lazy { PdfBinder(applicationContext) }

    override fun onBind(intent: Intent): IBinder = binder

    /** The last viewer is gone (or the main process died): every document it left open is closed. */
    override fun onUnbind(intent: Intent): Boolean {
        binder.closeAll()
        return false
    }

    override fun onDestroy() {
        binder.closeAll()
        super.onDestroy()
    }
}

/**
 * The documents the main process holds open in this process — a viewer's, a conversion's — by the id it
 * knows them by: a CLOSE closes any of them, the last unbinding closes them all.
 */
internal class PdfSessions {
    private val sessions = ConcurrentHashMap<Int, AutoCloseable>()
    private val ids = AtomicInteger()

    fun add(session: AutoCloseable): Int = ids.incrementAndGet().also { sessions[it] = session }

    fun find(id: Int): AutoCloseable? = sessions[id]

    fun close(id: Int) {
        sessions.remove(id)?.close()
    }

    fun closeAll() = sessions.keys.toList().forEach(::close)
}

/** Session [id], of the kind the call expects. */
internal inline fun <reified T : AutoCloseable> PdfSessions.of(id: Int): T = find(id) as? T ?: throw IOException("no session $id")

/** The calls of [PdfProtocol], answered in the `:pdf` process; one session per document a viewer or a conversion opened. */
internal class PdfBinder(private val context: Context) : Binder() {
    private val sessions = PdfSessions()
    private val conversions = PdfConversionCalls(context, sessions)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when {
        code !in PdfProtocol.OPEN..PdfProtocol.LAST -> super.onTransact(code, data, reply, flags)
        // Not exported already; and only this app's own processes, whatever a later manifest says.
        getCallingUid() != Process.myUid() -> false
        else -> {
            data.enforceInterface(PdfProtocol.DESCRIPTOR)
            serve(code, data, reply)
            true
        }
    }

    private fun serve(code: Int, data: Parcel, reply: Parcel?) {
        if (code == PdfProtocol.CLOSE) {
            sessions.close(data.readInt())
            return
        }
        val answer = requireNotNull(reply) { "call $code without an answer" }
        answer(answer) {
            when (code) {
                PdfProtocol.OPEN -> open(data, answer)
                PdfProtocol.RENDER -> render(data, answer)
                PdfProtocol.OUTLINE -> outline(data, answer)
                PdfProtocol.UNLOCK -> unlock(data, answer)
                else -> conversions.serve(code, data, answer)
            }
        }
    }

    fun closeAll() = sessions.closeAll()

    private fun open(data: Parcel, reply: Parcel) {
        val file = requireNotNull(data.readFileDescriptor()) { "no file" }
        val session = runCatching { PdfSession(file) }.getOrElse { error ->
            file.close()
            // PdfRenderer's word for "encrypted": a password, or a handler pdfium does not know (PubSec).
            if (error !is SecurityException) throw error
            reply.writeInt(PdfProtocol.ENCRYPTED)
            return
        }
        var kept = false
        try {
            val first = session.firstPage()
            val id = sessions.add(session)
            kept = true
            reply.writeInt(PdfProtocol.OK)
            reply.writeInt(id)
            reply.writeInt(session.pageCount)
            PdfProtocol.writeSize(reply, first)
        } finally {
            if (!kept) session.close()
        }
    }

    private fun render(data: Parcel, reply: Parcel) {
        val session = sessions.of<PdfSession>(data.readInt())
        val index = data.readInt()
        val width = data.readInt()
        val region = if (data.readInt() == 0) null else PixelRect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
        val maxPixels = data.readLong()
        val (size, bitmap) = session.render(index, width, region, maxPixels)
        try {
            reply.writeInt(PdfProtocol.OK)
            PdfProtocol.writeSize(reply, size)
            // Over ashmem, outside the Binder buffer: the pixels are copied once, into memory both processes map.
            bitmap.writeToParcel(reply, 0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun outline(data: Parcel, reply: Parcel) {
        val session = sessions.of<PdfSession>(data.readInt())
        val from = data.readInt()
        val entries = session.outline { PdfBox.ready(context) }
        val chunk = entries.drop(from).take(PdfProtocol.OUTLINE_CHUNK)
        reply.writeInt(PdfProtocol.OK)
        reply.writeInt(chunk.size)
        chunk.forEach { PdfProtocol.writeEntry(reply, it) }
        reply.writeInt(if (from + chunk.size < entries.size) 1 else 0)
    }

    private fun unlock(data: Parcel, reply: Parcel) {
        // Each descriptor owned by its stream the moment it is read: whatever throws after, neither stays open in
        // this process (Claude review R14).
        ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(data.readFileDescriptor()) { "no file" }).use { source ->
            val password = data.readString().orEmpty()
            val target = requireNotNull(data.readFileDescriptor()) { "no target" }
            val outcome = ParcelFileDescriptor.AutoCloseOutputStream(target).buffered().use { out ->
                PdfBox.ready(context)
                PdfUnlock.decrypt(ChannelRandomAccess(source.channel), password, out, PdfBox.scratch(context))
            }
            reply.writeInt(
                when (outcome) {
                    UnlockOutcome.DECRYPTED -> PdfProtocol.OK
                    UnlockOutcome.WRONG_PASSWORD -> PdfProtocol.WRONG_PASSWORD
                    UnlockOutcome.CERTIFICATE -> PdfProtocol.CERTIFICATE
                    UnlockOutcome.UNSUPPORTED -> PdfProtocol.UNSUPPORTED
                },
            )
        }
    }

    /**
     * [block]'s answer, or its status when it throws: the answer written so far is dropped. Whatever a
     * hostile file makes the readers throw stays in this process, an OutOfMemoryError and a stack overflow
     * included — what cannot be caught (a native crash) ends the process, which the main one sees.
     */
    // TooGenericExceptionCaught: the parsers' own exceptions on a hostile file, all of them a refusal.
    @Suppress("TooGenericExceptionCaught")
    private inline fun answer(reply: Parcel, block: () -> Unit) {
        val start = reply.dataPosition()
        val status = try {
            block()
            return
        } catch (expected: OutOfMemoryError) {
            PdfProtocol.OUT_OF_MEMORY
        } catch (expected: StackOverflowError) {
            PdfProtocol.UNREADABLE
        } catch (expected: Exception) {
            PdfProtocol.UNREADABLE
        }
        reply.setDataSize(start)
        reply.setDataPosition(start)
        reply.writeInt(status)
    }
}

/** PdfBox's resources (its fonts, glyph lists), loaded once, and only in the `:pdf` process (docs D-029). */
internal object PdfBox {
    /** What PdfBox holds in memory of a document it reads or writes; past it, its scratch goes to a file. */
    private const val SCRATCH_MEMORY = 8L * 1024 * 1024

    @Volatile
    private var ready = false

    @Synchronized
    fun ready(context: Context) {
        if (ready) return
        PDFBoxResourceLoader.init(context)
        ready = true
    }

    /**
     * PdfBox's scratch: [SCRATCH_MEMORY] in memory, then files in a folder of their own, purged with the
     * vault's plaintext and served by no FileProvider ([PdfUnlocked.SCRATCH_DIR]). Created here: a lock may
     * have removed it.
     */
    fun scratch(context: Context): MemoryUsageSetting {
        val folder = File(context.cacheDir, PdfUnlocked.SCRATCH_DIR)
        Files.createDirectories(folder.toPath())
        // Bounded on disk too: past it PdfBox throws ("Maximum allowed scratch file memory exceeded"), a refusal.
        return MemoryUsageSetting.setupMixed(SCRATCH_MEMORY, FileCaps.PDF_SCRATCH).setTempDir(folder)
    }
}

/**
 * One document a viewer opened: its renderer, and its outline once read. PdfRenderer renders one page at a
 * time, and refuses to close with one open: renders and the close take the same lock. The outline is read
 * by PdfBox, from a duplicate of the descriptor read by position, under a lock of its own.
 */
private class PdfSession(private val file: ParcelFileDescriptor) : AutoCloseable {
    // PdfRenderer owns the descriptor it is given: closing it closes [file].
    private val renderer = PdfRenderer(file)
    val pageCount: Int = renderer.pageCount
    private var closed = false
    private val outlineLock = Any()
    private var entries: List<OutlineEntry>? = null

    /** The first page's size, which the screen lays every page out with until it knows theirs. */
    fun firstPage(): PageSize {
        if (pageCount <= 0) throw IOException("no page")
        return synchronized(this) { renderer.openPage(0).use { PageSize(it.width.toFloat(), it.height.toFloat()) } }
    }

    @Synchronized
    fun render(index: Int, width: Int, region: PixelRect?, maxPixels: Long): Pair<PageSize, Bitmap> {
        if (closed || index !in 0 until pageCount) throw IOException("no page $index of $pageCount, or closed")
        return renderer.openPage(index).use { page ->
            val size = PageSize(page.width.toFloat(), page.height.toFloat())
            val plan = PdfPageFit.render(size, width, region, maxPixels) ?: throw IOException("nothing to render")
            val bitmap = createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
            // PdfRenderer draws on a TRANSPARENT bitmap: a page's own white is not drawn.
            bitmap.eraseColor(Color.WHITE)
            if (plan.scale > 0f) {
                val transform = Matrix().apply {
                    setScale(plan.scale, plan.scale)
                    postTranslate(plan.dx, plan.dy)
                }
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            size to bitmap
        }
    }

    /** The outline, read the first time it is asked; [prepare] readies PdfBox. */
    fun outline(prepare: () -> Unit): List<OutlineEntry> = synchronized(outlineLock) {
        entries ?: run {
            prepare()
            val copy = file.dup()
            PdfBoxOutline.read(ChannelRandomAccess(ParcelFileDescriptor.AutoCloseInputStream(copy).channel), pageCount)
        }.also { entries = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
    }
}
