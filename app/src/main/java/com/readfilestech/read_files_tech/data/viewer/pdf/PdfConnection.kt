package com.readfilestech.read_files_tech.data.viewer.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Why the viewer shows no PDF, in its own words — none of them a damaged file. */
enum class PdfRefusal {
    /** Encrypted under a password: the viewer asks for it. */
    PASSWORD_REQUIRED,

    /** Encrypted for the certificates of its recipients (`/Adobe.PubSec`): no password opens it (docs D-005). */
    CERTIFICATE,

    /** The `:pdf` process ended (a native crash, out of memory) or could not start: the file stopped the reader. */
    READER_STOPPED,
}

class PdfRefusalException(val refusal: PdfRefusal, cause: Throwable? = null) : IOException(refusal.name, cause)

/** A page rendered by the `:pdf` process, and the page's size it learnt doing so. */
class RenderedPage(val size: PageSize, val bitmap: Bitmap)

/**
 * The main process's side of the `:pdf` process (docs D-029), one per viewer: bound when made, unbound
 * when closed. It never parses the file: it hands a descriptor over and receives a page count, page sizes,
 * bitmaps and outline entries.
 *
 * When `:pdf` dies, every call fails with [PdfRefusal.READER_STOPPED], [stopped] turns true, and the
 * connection UNBINDS at once: the system would otherwise start the service again for it, and the viewer
 * open the same file in it again — a crash in a loop. A viewer opened afterwards binds afresh.
 */
class PdfConnection(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val connected = CompletableDeferred<Unit>()

    @Volatile
    private var service: IBinder? = null
    private val unbound = AtomicBoolean(false)
    private val mutableStopped = MutableStateFlow(false)

    /** The `:pdf` process ended under this viewer: what it shows can no longer be rendered. */
    val stopped: StateFlow<Boolean> = mutableStopped.asStateFlow()

    private val death = IBinder.DeathRecipient { lost() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            try {
                service.linkToDeath(death, 0)
                this@PdfConnection.service = service
                connected.complete(Unit)
            } catch (expected: RemoteException) {
                lost()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = lost()

        override fun onBindingDied(name: ComponentName?) = lost()

        override fun onNullBinding(name: ComponentName?) = lost()
    }

    init {
        if (!app.bindService(Intent(app, PdfService::class.java), connection, Context.BIND_AUTO_CREATE)) lost()
    }

    /** Waits for the `:pdf` process, started if it was not. */
    suspend fun ready() {
        try {
            withTimeout(START_MILLIS) { connected.await() }
        } catch (e: TimeoutCancellationException) {
            lost()
            throw PdfRefusalException(PdfRefusal.READER_STOPPED, e)
        }
    }

    /**
     * Opens the PDF [file] in a session of its own. Blocking: a Binder call.
     *
     * @throws PdfRefusalException PASSWORD_REQUIRED when PdfRenderer refused it as encrypted; READER_STOPPED.
     * @throws UnreadableFileException damaged, not a PDF, or without a page.
     */
    fun open(file: File): PdfDocument = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        call(PdfProtocol.OPEN, { it.writeFileDescriptor(descriptor.fileDescriptor) }) { reply ->
            when (val status = reply.readInt()) {
                PdfProtocol.OK -> PdfDocument(this, reply.readInt(), reply.readInt(), PdfProtocol.readSize(reply))
                PdfProtocol.ENCRYPTED -> throw PdfRefusalException(PdfRefusal.PASSWORD_REQUIRED)
                else -> throw failure(status)
            }
        }
    }

    /**
     * [source] decrypted with [password] into [target], by PdfBox. False when it is not the password.
     *
     * @throws PdfRefusalException CERTIFICATE; READER_STOPPED.
     * @throws UnsupportedFormatException an encryption handler no reader knows.
     */
    fun unlock(source: File, password: String, target: File): Boolean {
        val input = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val output = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        return input.use {
            output.use {
                call(PdfProtocol.UNLOCK, { data ->
                    data.writeFileDescriptor(input.fileDescriptor)
                    data.writeString(password)
                    data.writeFileDescriptor(output.fileDescriptor)
                }) { reply ->
                    when (val status = reply.readInt()) {
                        PdfProtocol.OK -> true
                        PdfProtocol.WRONG_PASSWORD -> false
                        else -> throw failure(status)
                    }
                }
            }
        }
    }

    internal fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val target = binderOrStopped()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PdfProtocol.DESCRIPTOR)
            write(data)
            target.transact(code, data, reply, 0)
            return read(reply)
        } catch (e: RemoteException) {
            // DeadObjectException: the process died during the call, or before it.
            lost()
            throw PdfRefusalException(PdfRefusal.READER_STOPPED, e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** A call without an answer, which never waits for the `:pdf` process — a close, from the main thread. */
    internal fun send(code: Int, write: (Parcel) -> Unit) {
        val target = service?.takeUnless { mutableStopped.value } ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(PdfProtocol.DESCRIPTOR)
            write(data)
            target.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (expected: RemoteException) {
            lost()
        } finally {
            data.recycle()
        }
    }

    /** The exception a status other than OK stands for. */
    internal fun failure(status: Int): Throwable = when (status) {
        PdfProtocol.CERTIFICATE -> PdfRefusalException(PdfRefusal.CERTIFICATE)
        PdfProtocol.UNSUPPORTED -> UnsupportedFormatException("encryption handler")
        PdfProtocol.OUT_OF_MEMORY -> OutOfMemoryError("in the :pdf process")
        else -> UnreadableFileException("status $status of the :pdf process")
    }

    override fun close() {
        if (!unbound.compareAndSet(false, true)) return
        try {
            app.unbindService(connection)
        } catch (expected: IllegalArgumentException) {
            // bindService failed: nothing to unbind.
        }
    }

    private fun binderOrStopped(): IBinder {
        if (mutableStopped.value) throw PdfRefusalException(PdfRefusal.READER_STOPPED)
        return service ?: throw PdfRefusalException(PdfRefusal.READER_STOPPED)
    }

    private fun lost() {
        mutableStopped.value = true
        connected.completeExceptionally(PdfRefusalException(PdfRefusal.READER_STOPPED))
        close()
    }

    private companion object {
        /** Long enough for a process to start on a loaded phone; past it, the reader is said stopped. */
        const val START_MILLIS = 15_000L
    }
}

/**
 * A document open in the `:pdf` process. Its calls block (Binder): the viewer makes them off the main
 * thread, but [close], one way, which never waits.
 */
class PdfDocument internal constructor(
    private val connection: PdfConnection,
    private val session: Int,
    val pageCount: Int,
    /** The first page's size: every page is laid out with it until its own is known. */
    val firstPage: PageSize,
) : AutoCloseable {
    /**
     * [region] of page [index]'s box in a column [width] pixels wide (PdfPageFit), all of it when null, in
     * at most [maxPixels] pixels.
     */
    fun render(index: Int, width: Int, region: PixelRect?, maxPixels: Long): RenderedPage =
        connection.call(PdfProtocol.RENDER, { data ->
            data.writeInt(session)
            data.writeInt(index)
            data.writeInt(width)
            if (region == null) {
                data.writeInt(0)
            } else {
                data.writeInt(1)
                data.writeInt(region.left)
                data.writeInt(region.top)
                data.writeInt(region.right)
                data.writeInt(region.bottom)
            }
            data.writeLong(maxPixels)
        }) { reply ->
            when (val status = reply.readInt()) {
                PdfProtocol.OK -> {
                    val size = PdfProtocol.readSize(reply)
                    RenderedPage(size, Bitmap.CREATOR.createFromParcel(reply))
                }
                else -> throw connection.failure(status)
            }
        }

    /** The outline (PdfBox, in the `:pdf` process), bounded by [OutlineWalker]; empty when the file has none. */
    fun outline(): List<OutlineEntry> {
        val entries = ArrayList<OutlineEntry>()
        var more = true
        while (more) {
            more = connection.call(PdfProtocol.OUTLINE, { data ->
                data.writeInt(session)
                data.writeInt(entries.size)
            }) { reply ->
                val status = reply.readInt()
                if (status != PdfProtocol.OK) throw connection.failure(status)
                val count = reply.readInt()
                repeat(count) { entries += PdfProtocol.readEntry(reply) }
                // An answer that adds nothing ends the reading, whatever it says follows.
                reply.readInt() == 1 && count > 0
            }
        }
        return entries
    }

    override fun close() = connection.send(PdfProtocol.CLOSE) { it.writeInt(session) }
}
