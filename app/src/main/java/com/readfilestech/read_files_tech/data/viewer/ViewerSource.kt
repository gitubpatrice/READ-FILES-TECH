package com.readfilestech.read_files_tech.data.viewer

import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a viewer reads (docs D-028): a file of the storage today, a file of the vault decrypted inside
 * the app in phase 6. The viewers are written against this, never against a path: the vault plugs in
 * without rewriting them, and its plaintext never leaves the app (the 2.15.x decrypted it to the cache
 * and handed it to another app).
 */
interface ViewerSource {
    /** The name the user knows the file by: for the vault, its name in the vault, never a temporary's. */
    val name: String

    /** Lower case, without the dot, empty when none (the rule of FileEntry). */
    val extension: String get() = FileNames.extension(name)

    /**
     * The file another app may be handed (share, "Open with…"), or null when the content must stay in
     * the app — the vault's (D-028).
     */
    val shareable: File?

    /** The size now, in bytes: a first refusal only — whatever reads it still goes through [open]'s cap. */
    fun length(): Long

    /**
     * A new stream over the whole content, from its start. More than [cap] bytes is a
     * [com.readfilestech.read_files_tech.data.files.FileTooLargeException]: before opening when the size
     * says so, while reading otherwise.
     */
    fun open(cap: Long): InputStream

    /**
     * The content as a file a reader can seek in (PdfRenderer, ZipFile), until the handle is closed: the
     * file itself for the storage; for the vault, a private copy deleted at close (D-028). More than
     * [cap] bytes is refused as by [open].
     */
    fun acquireFile(cap: Long): SourceFile
}

/** A file to seek in, valid until [close]; closing it twice runs [onClose] once. */
class SourceFile(val file: File, private val onClose: () -> Unit = {}) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

/** A file of the storage, as the explorer, the home and the picker open it. */
class StorageFileSource(private val file: File) : ViewerSource {
    override val name: String get() = file.name

    override val shareable: File get() = file

    override fun length(): Long = file.length()

    override fun open(cap: Long): InputStream {
        FileCaps.check(file, cap)
        // Not Files.newInputStream: over a channel, a read of n bytes goes through a temporary direct
        // buffer of n bytes that its thread keeps — one read of 50 MB left 50 MB on the heap (corpus).
        return BoundedInputStream(FileInputStream(file), cap)
    }

    /** The file itself: nothing is copied, and closing the handle deletes nothing. */
    override fun acquireFile(cap: Long): SourceFile {
        FileCaps.check(file, cap)
        return SourceFile(file)
    }
}
