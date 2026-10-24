package com.readfilestech.read_files_tech.data.extractions

import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.tools.FileHashes
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The "Extract all" folders of the 2.15.x (docs D-022): `app_flutter/<archive>_extracted/`, private —
 * neither the other apps nor the explorer of the app could open them (D17), and the panic left them
 * (D16). The user chooses, once: move them into `Files Tech/Extractions/` (what "Extract all" was for),
 * delete them, or later. Nothing is moved in silence: from private to shared storage, these files
 * become readable by any app with storage access.
 */
class LegacyExtractions(
    /** `app_flutter/` of the 2.15.x. */
    private val appFlutter: File,
    /**
     * `Files Tech/Extractions/` on shared storage (suffixed in a `.next` build, docs D-017), asked at each move:
     * the outputs' folder can change in the settings, and which one takes the files depends on storage access.
     * It throws an [IOException] when no folder of shared storage can.
     */
    private val extractionsRoot: () -> File,
    /**
     * The copy of one file INTO its reservation (truncated, never unlinked first); a test passes one that
     * fails, as a full disk would.
     */
    private val copy: (from: Path, to: Path) -> Unit = { from, to ->
        Files.newOutputStream(to, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { Files.copy(from, it) }
    },
) {
    /** One old extraction folder: [archiveName] as the 2.15.x derived it, what it holds. */
    data class Folder(val dir: File, val archiveName: String, val files: Int, val bytes: Long)

    /** What a move did: files moved and verified, files left in place (none unless it stopped). */
    data class Moved(val destination: File, val moved: Int, val left: Int, val stoppedBy: IOException?)

    fun find(): List<Folder> = appFlutter.listFiles().orEmpty()
        .filter(::isExtractionFolder)
        .map { dir ->
            val files = Trees.regularFiles(dir)
            Folder(dir, dir.name.removeSuffix(SUFFIX), files.size, files.sumOf { it.length() })
        }
        .sortedBy { it.dir.name }

    fun delete(folder: Folder): Boolean = FileTrees.delete(folder.dir)

    /**
     * Moves [folder] into a NEW folder of [extractionsRoot] — never over an existing one: `name`, then
     * `name (2)`, `name (3)`… Each file is copied, checked (size and SHA-256) and only then removed from
     * the private folder. A failure — no space left — stops the move cleanly: the partial copy of the
     * file in hand is removed, the files not yet moved stay where they were.
     *
     * @throws IOException no folder could be made to move it into: nothing moved.
     */
    fun move(folder: Folder): Moved {
        val root = extractionsRoot()
        root.mkdirs()
        val destination = reserveFolder(root, folder.archiveName)
        // Every subfolder first, empty ones included: the private folder is deleted once its files are
        // moved, and an empty folder of the archive would be lost with it (GPT review R3).
        Trees.subfolders(folder.dir).forEach { File(destination, it.relativeTo(folder.dir).path).mkdirs() }
        val sources = Trees.regularFiles(folder.dir)
        var moved = 0
        var stoppedBy: IOException? = null
        for (source in sources) {
            val failure = moveOne(source, File(destination, source.relativeTo(folder.dir).path))
            if (failure != null) {
                stoppedBy = failure
                break
            }
            moved++
        }
        // The folders the move emptied go; anything still holding something — a file left, a link, an
        // entry the walk could not read — stays (Claude review R3: a recursive delete took it all).
        if (moved == sources.size) Trees.removeEmptyFolders(folder.dir)
        return Moved(destination, moved, sources.size - moved, stoppedBy)
    }

    /**
     * One file, or why it stopped. The target is RESERVED first, by an exclusive creation: on a
     * case-insensitive volume (FUSE casefold, sdcardfs, exFAT) `notes.txt` lands on the `Notes.txt` just
     * moved, and deleting a target after a failed copy would delete THAT file, whose source is already
     * gone (Claude review R3). A target that cannot be reserved stops the move with nothing deleted.
     */
    private fun moveOne(source: File, target: File): IOException? {
        try {
            target.parentFile?.mkdirs()
            Files.createFile(target.toPath())
        } catch (e: IOException) {
            return e
        }
        return try {
            copyVerified(source, target)
            source.delete()
            null
        } catch (e: IOException) {
            target.delete() // ours: reserved above
            e
        }
    }

    private fun reserveFolder(root: File, name: String): File {
        val candidates = sequenceOf(name) + (2..MAX_CANDIDATES).asSequence().map { "$name ($it)" }
        for (candidate in candidates) {
            val dir = File(root, candidate)
            if (runCatching { Files.createDirectory(dir.toPath()) }.isSuccess) return dir
        }
        throw IOException("no free folder name for $name in ${root.path}")
    }

    private fun copyVerified(source: File, target: File) {
        target.parentFile?.mkdirs()
        copy(source.toPath(), target.toPath())
        val same = source.length() == target.length() && FileHashes.sha256(source) == FileHashes.sha256(target)
        if (!same) throw IOException("the copy of ${source.name} differs from it")
    }

    companion object {
        /** `'${base}_extracted'` of `zip_viewer_screen.dart`. */
        const val SUFFIX = "_extracted"
        const val EXTRACTIONS_DIR = "Extractions"
        private const val MAX_CANDIDATES = 1_000

        /**
         * `<archive>_extracted`, a folder itself (not a link to one), with an archive name before the suffix —
         * here and for the panic, which erases them (D16 of the inventory).
         */
        fun isExtractionFolder(dir: File) =
            Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) && dir.name.endsWith(SUFFIX) && dir.name.length > SUFFIX.length
    }
}

/** Walking an extraction folder: never following a link, going on past what cannot be read. */
private object Trees {
    /** The folders the move emptied, deepest first; anything still holding something stays with it. */
    fun removeEmptyFolders(root: File) {
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    // Files.delete refuses a folder that is not empty.
                    runCatching { Files.delete(dir) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }

    /** Every folder under [root], [root] excluded; a symbolic link is never followed. */
    fun subfolders(root: File): List<File> {
        val out = mutableListOf<File>()
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root.toPath()) out += dir.toFile()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return out
    }

    /** Every regular file under [root]; a symbolic link is never followed nor moved. */
    fun regularFiles(root: File): List<File> {
        val out = mutableListOf<File>()
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) out += file.toFile()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return out.sortedBy { it.path }
    }
}
