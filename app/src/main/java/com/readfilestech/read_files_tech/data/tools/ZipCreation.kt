package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.convert.SelectionTooLargeException
import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.outputs.create
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** The file [name] could not be read into the archive; nothing was written. */
class ZipSourceException(val name: String, cause: Throwable) : IOException("cannot read $name", cause)

/**
 * The archive of "Create a ZIP" (`zip_creator_screen.dart` of the 2.15.3): the files chosen, each under its
 * own name. The 2.15.3 read every file whole into memory, then encoded the archive whole, then wrote it;
 * here each file is STREAMED into the archive by pieces, so its cap only bounds the archive's size — checked
 * on the sizes BEFORE anything is written, and again while reading (a file grown meanwhile).
 */
object ZipCreation {
    private const val PIECE = 64 * 1024

    /** The bytes of [files] together. @throws SelectionTooLargeException past [cap], the 2.15.3's 200 MB. */
    fun checkTotal(files: List<File>, cap: Long = FileCaps.ZIP_CREATE_TOTAL): Long {
        val total = files.sumOf { it.length() }
        if (total > cap) throw SelectionTooLargeException(cap)
        return total
    }

    /**
     * Each file's name in the archive: its own name, made safe — a `\` (which Windows' extractors take for
     * a folder: `..\..\x`) and control characters become `_` — and UNIQUE, case ignored: `notes (1).txt`
     * after `notes.txt` or `NOTES.TXT`. The 2.15.3 wrote two entries of one name for two files of the same
     * name in two folders (inventory F24), and an extraction on Android's storage, which ignores case,
     * replaced one by the other.
     */
    fun entryNames(names: List<String>): List<String> {
        val taken = HashSet<String>()
        return names.map { original ->
            val safe = safeName(original)
            val dot = safe.lastIndexOf('.')
            val base = if (dot > 0) safe.substring(0, dot) else safe
            val ext = if (dot > 0) safe.substring(dot) else ""
            generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }
                .let { sequenceOf(safe) + it }
                .first { taken.add(it.lowercase(Locale.ROOT)) }
        }
    }

    private fun safeName(name: String): String {
        val safe = name.map { if (it == '\\' || it < ' ' || it == DELETE) '_' else it }.joinToString("")
        return if (safe.isEmpty() || safe == "." || safe == "..") "_" else safe
    }

    /**
     * Writes the archive of [files] into [out], which it leaves open (the output is published by its
     * caller). [check] runs between two pieces — a cancellation —, [onProgress] gets the bytes read so far.
     *
     * @throws SelectionTooLargeException the files grew past [cap] while being read.
     * @throws ZipSourceException a file could not be read, named.
     */
    fun write(files: List<File>, out: OutputStream, cap: Long, check: () -> Unit, onProgress: (Long) -> Unit) {
        val archive = Archive(out, cap, check, onProgress)
        try {
            files.zip(entryNames(files.map(File::getName))).forEach { (file, name) -> archive.add(file, name) }
            archive.finish()
        } finally {
            archive.release()
        }
    }

    /** A ZIP stream whose compressor is ended at will: [ZipOutputStream] ends it in close() only, which closes the caller's stream. */
    private class Zip(out: OutputStream) : ZipOutputStream(out) {
        fun release() = def.end()
    }

    /** One archive being written: its entries one after the other, and the bytes read so far against [cap]. */
    private class Archive(
        out: OutputStream,
        private val cap: Long,
        private val check: () -> Unit,
        private val onProgress: (Long) -> Unit,
    ) {
        private val zip = Zip(out).apply { setLevel(Deflater.DEFAULT_COMPRESSION) }
        private val buffer = ByteArray(PIECE)
        private var read = 0L

        fun add(file: File, name: String) {
            check()
            open(file).use { input ->
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                copy(input, file.name)
                zip.closeEntry()
            }
        }

        /** finish, not close: the stream under it is the caller's. */
        fun finish() {
            zip.finish()
            zip.flush()
        }

        /** The compressor's native memory let go, written or not (Claude review R24: left to the collector). */
        fun release() = zip.release()

        /**
         * [file] within what the cap leaves. Never a link followed: the picker hands files, and a link swapped
         * in since would be read through — opened without following one too, not only checked before
         * (Claude review R24). A pipe or a device is refused before it is opened: opening a pipe waits.
         */
        private fun open(file: File): BoundedInputStream = try {
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("not a regular file")
            BoundedInputStream(Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS), cap - read)
        } catch (e: IOException) {
            throw ZipSourceException(file.name, e)
        }

        private fun copy(input: BoundedInputStream, name: String) {
            while (true) {
                check()
                val n = readPiece(input, name)
                if (n == -1) return
                zip.write(buffer, 0, n)
                read += n
                onProgress(read)
            }
        }

        private fun readPiece(input: BoundedInputStream, name: String): Int = try {
            input.read(buffer)
        } catch (grown: FileTooLargeException) {
            throw SelectionTooLargeException(cap).apply { initCause(grown) }
        } catch (e: IOException) {
            throw ZipSourceException(name, e)
        }
    }

    private const val DELETE = '\u007F'
}

/**
 * Makes the archive of [ZipCreation] as an output: `Conversions/archive_<stamp>.zip`, the 2.15.3's name and
 * folder, written atomically — a failure or a stop leaves nothing there, not even the empty reservation.
 */
class ZipCreator @Inject constructor(private val outputs: OutputStorage) {
    /** [onProgress] gets the part done, from 0 to 1. */
    suspend fun create(files: List<File>, onProgress: (Float) -> Unit): File {
        val job = currentCoroutineContext()
        val cap = FileCaps.ZIP_CREATE_TOTAL
        val total = ZipCreation.checkTotal(files, cap).coerceAtLeast(1)
        // A stop during the last piece: nothing is published ([create] checks once the archive is written).
        return outputs.create(OutputCategory.CONVERSIONS, NAME, EXTENSION) { stream ->
            ZipCreation.write(
                files,
                stream,
                cap,
                { job.ensureActive() }
            ) { read -> onProgress((read.toFloat() / total).coerceAtMost(1f)) }
        }
    }

    private companion object {
        const val NAME = "archive"
        const val EXTENSION = "zip"
    }
}
