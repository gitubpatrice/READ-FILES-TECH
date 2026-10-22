package com.readfilestech.read_files_tech.data.scanner

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * The scanner's private folder, `cache/scanner/`:
 * - `capture/` ([captures]) — the files the camera app writes into, one per photo asked for: the only
 *   part of it the FileProvider serves (res/xml/file_paths.xml), and only for the URI handed to the
 *   camera; a photo leaves it as soon as the camera returns — moved into its scan, or, the OCR's, deleted
 *   once decoded. PathGuard lets nothing of `cache/scanner/`
 *   out to another app (of `cache/`, only `zip_shared/` may leave).
 * - `<session>/` — one per scan in progress: `session.json`, the photos taken (`src-<id>.jpg`) and the
 *   processed pages (`page-<id>-<revision>.jpg`).
 *
 * Emptied when the app starts afresh (StartupCleanup.clearScansOnce), after an export, and when the
 * scanner is left.
 */
class ScanFiles(val root: File) {
    val captures = Captures()

    fun sessionDir(sessionId: String): File {
        require(ScanSession.isId(sessionId)) { "not a session id" }
        return File(root, sessionId)
    }

    /** The file of a page's photo. */
    fun sourceFile(sessionId: String, source: PageSource): File = when (source) {
        is PageSource.Owned -> {
            require(ScanSession.isOwnedName(source.name)) { "not an owned photo" }
            File(sessionDir(sessionId), source.name)
        }
        is PageSource.External -> File(source.path)
    }

    /** The processed JPEG of [page]. */
    fun pageFile(sessionId: String, page: ScanPage): File = File(sessionDir(sessionId), "page-${page.id}-${page.revision}.jpg")

    /** Written atomically: a process killed half-way leaves the previous session, never a truncated one. */
    fun save(session: ScanSession) {
        val dir = sessionDir(session.id)
        Files.createDirectories(dir.toPath())
        AtomicFiles.writeBytes(File(dir, SESSION_FILE), session.toJson().toByteArray(Charsets.UTF_8))
    }

    /** The session saved under [sessionId], or null: none, unreadable, or not this app's. */
    fun load(sessionId: String): ScanSession? {
        if (!ScanSession.isId(sessionId)) return null
        val file = File(sessionDir(sessionId), SESSION_FILE)
        val text = try {
            file.takeIf { it.length() <= MAX_SESSION_BYTES }?.readText(Charsets.UTF_8)
        } catch (expected: IOException) {
            null
        }
        return text?.let(ScanSession::fromJson)?.takeIf { it.id == sessionId }
    }

    /** Deletes what [page] left in its session's folder: its processed JPEGs, and its photo if it was the session's. */
    fun deletePageFiles(sessionId: String, page: ScanPage) {
        val dir = sessionDir(sessionId)
        dir.listFiles { f -> f.name.startsWith("page-${page.id}-") }?.forEach { it.delete() }
        (page.source as? PageSource.Owned)?.let { File(dir, it.name).delete() }
    }

    /** The processed JPEGs of [page] other than its current one (a page made again). */
    fun deleteOldRevisions(sessionId: String, page: ScanPage) {
        val current = pageFile(sessionId, page).name
        val old = sessionDir(sessionId).listFiles { f -> f.name.startsWith("page-${page.id}-") && f.name != current }
        old?.forEach { it.delete() }
    }

    fun deleteSession(sessionId: String) {
        if (ScanSession.isId(sessionId)) FileTrees.delete(sessionDir(sessionId))
    }

    /** Everything: the sessions and the captures. True when nothing is left. */
    fun purge(): Boolean = FileTrees.delete(root)

    /** `capture/`: the files the camera app writes the photos into. */
    inner class Captures {
        val dir: File get() = File(root, CAPTURE_DIR)

        /** A new empty file for the camera app to write into, created exclusively. */
        fun newFile(): File {
            Files.createDirectories(dir.toPath())
            val file = File(dir, "${newId()}.jpg")
            Files.createFile(file.toPath())
            return file
        }

        /** Whether [file] is one of [newFile]'s, by its place and name: the only files [discard] deletes. */
        fun isOwn(file: File): Boolean = file.parentFile?.canonicalFile == dir.canonicalFile && CAPTURE_NAME.matches(file.name)

        fun discard(file: File) {
            if (isOwn(file)) Files.deleteIfExists(file.toPath())
        }

        /** The photo the camera wrote into [capture], moved into the session's folder (out of the served one). */
        fun adopt(capture: File, sessionId: String): PageSource.Owned {
            require(isOwn(capture)) { "not a capture" }
            val folder = sessionDir(sessionId)
            Files.createDirectories(folder.toPath())
            val name = "src-${newId()}.jpg"
            Files.move(capture.toPath(), File(folder, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
            return PageSource.Owned(name)
        }
    }

    companion object {
        /** The scanner's folder under the app's [cache] folder. */
        fun under(cache: File) = ScanFiles(File(cache, DIR))

        private val random = SecureRandom()

        /** A random id: sixteen hexadecimal digits, the shape [ScanSession.isId] checks. */
        fun newId(): String = ByteArray(ID_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        const val DIR = "scanner"
        const val CAPTURE_DIR = "capture"
        private const val SESSION_FILE = "session.json"
        private const val ID_BYTES = 8

        /** A session file is a few kilobytes: anything far larger is not one. */
        private const val MAX_SESSION_BYTES = 1L shl 20
        private val CAPTURE_NAME = Regex("^[0-9a-f]{16}\\.jpg$")
    }
}
