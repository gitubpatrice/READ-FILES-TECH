package com.readfilestech.read_files_tech.data.files

import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writing a file so that it is either the old one or the complete new one — never a truncated mix —
 * the way `atomicWriteBytes` of the Flutter app does it (`lib/utils/atomic_write.dart`, 2.15.3).
 *
 * The temporary file sits NEXT TO the target (a rename is atomic only within one file system), under a
 * name unique to each call — `<target>.<n>-<hex>.tmp`, the Flutter app's pattern, so the residue of an
 * interrupted write is recognised whichever app left it (VaultNames, D24). A fixed name would let two
 * concurrent writes share one temporary file, and a name an attacker can guess could be planted
 * beforehand as a symbolic link.
 *
 * Unlike the Flutter version, the data is synced to the disk (`fsync`) before the rename: without it
 * a power cut right after the rename can leave the new name over an empty file.
 */
object AtomicFiles {
    private val sequence = AtomicInteger()
    private val random = SecureRandom()

    private const val SEQUENCE_MASK = 0xFFFF
    private const val HEX = 16

    /**
     * Writes [target] through [write], atomically. On any failure — an exception, a cancellation —
     * the temporary file is removed and [target] is left as it was.
     *
     * [publish] puts the complete temporary file in place; a caller wraps [AtomicFiles.publish] in its
     * own last-moment checks (the vault still open, no namesake appeared), run as one step with it.
     */
    fun write(target: File, publish: (from: File, to: File) -> Unit = ::publish, write: (OutputStream) -> Unit) {
        val tmp = temporaryFor(target)
        // Created EXCLUSIVELY: never an existing file, nor a link planted at that name; and ours to
        // delete only once created (GPT review R3).
        val channel = FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        var published = false
        try {
            channel.use {
                val out = Channels.newOutputStream(it)
                write(out)
                out.flush()
                it.force(true)
            }
            publish(tmp, target)
            published = true
        } finally {
            if (!published) tmp.delete()
        }
    }

    fun writeBytes(target: File, bytes: ByteArray) = write(target) { it.write(bytes) }

    /**
     * Moves the complete file [from] over [target], atomically. [from] must be on the same file system
     * and already synced: it is a file built next to its destination, published in one step.
     */
    fun publish(from: File, target: File) {
        Files.move(from.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** A fresh temporary name next to [target]; the file is NOT created. */
    fun temporaryFor(target: File): File {
        val n = sequence.incrementAndGet() and SEQUENCE_MASK
        val r = Integer.toUnsignedLong(random.nextInt()).toString(HEX)
        return File(target.parentFile, "${target.name}.$n-$r.tmp")
    }
}
