package com.readfilestech.read_files_tech.data.extractions

import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

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
    /** `Files Tech/Extractions/` on shared storage (suffixed in a `.next` build, docs D-017). */
    private val extractionsRoot: File,
    /** The copy of one file; a test passes one that fails, as a full disk would. */
    private val copy: (from: Path, to: Path) -> Unit = { from, to -> Files.copy(from, to) },
) {
    /** One old extraction folder: [archiveName] as the 2.15.x derived it, what it holds. */
    data class Folder(val dir: File, val archiveName: String, val files: Int, val bytes: Long)

    /** What a move did: files moved and verified, files left in place (none unless it stopped). */
    data class Moved(val destination: File, val moved: Int, val left: Int, val stoppedBy: IOException?)

    fun find(): List<Folder> = appFlutter.listFiles().orEmpty()
        .filter(::isExtractionFolder)
        .map { dir ->
            val files = regularFiles(dir)
            Folder(dir, dir.name.removeSuffix(SUFFIX), files.size, files.sumOf { it.length() })
        }
        .sortedBy { it.dir.name }

    fun delete(folder: Folder): Boolean = FileTrees.delete(folder.dir)

    /** `<archive>_extracted`, a folder itself (not a link to one), with an archive name before the suffix. */
    private fun isExtractionFolder(dir: File) =
        Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) && dir.name.endsWith(SUFFIX) && dir.name.length > SUFFIX.length

    /**
     * Moves [folder] into a NEW folder of [extractionsRoot] — never over an existing one: `name`, then
     * `name (2)`, `name (3)`… Each file is copied, checked (size and SHA-256) and only then removed from
     * the private folder. A failure — no space left — stops the move cleanly: the partial copy of the
     * file in hand is removed, the files not yet moved stay where they were.
     */
    fun move(folder: Folder): Moved {
        extractionsRoot.mkdirs()
        val destination = reserveFolder(folder.archiveName)
        val sources = regularFiles(folder.dir)
        var moved = 0
        var stoppedBy: IOException? = null
        for (source in sources) {
            val target = File(destination, source.relativeTo(folder.dir).path)
            try {
                copyVerified(source, target)
                source.delete()
                moved++
            } catch (e: IOException) {
                target.delete()
                stoppedBy = e
                break
            }
        }
        // The folders the move emptied go too; anything still holding a file stays.
        if (moved == sources.size) FileTrees.delete(folder.dir)
        return Moved(destination, moved, sources.size - moved, stoppedBy)
    }

    private fun reserveFolder(name: String): File {
        val candidates = sequenceOf(name) + (2..MAX_CANDIDATES).asSequence().map { "$name ($it)" }
        for (candidate in candidates) {
            val dir = File(extractionsRoot, candidate)
            if (runCatching { Files.createDirectory(dir.toPath()) }.isSuccess) return dir
        }
        throw IOException("no free folder name for $name in ${extractionsRoot.path}")
    }

    private fun copyVerified(source: File, target: File) {
        target.parentFile?.mkdirs()
        copy(source.toPath(), target.toPath())
        val same = source.length() == target.length() && sha256(source).contentEquals(sha256(target))
        if (!same) throw IOException("the copy of ${source.name} differs from it")
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    /** Every regular file under [root]; a symbolic link is never followed nor moved. */
    private fun regularFiles(root: File): List<File> {
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

    companion object {
        /** `'${base}_extracted'` of `zip_viewer_screen.dart`. */
        const val SUFFIX = "_extracted"
        const val EXTRACTIONS_DIR = "Extractions"
        private const val MAX_CANDIDATES = 1_000
        private const val BUFFER = 64 * 1024
    }
}
