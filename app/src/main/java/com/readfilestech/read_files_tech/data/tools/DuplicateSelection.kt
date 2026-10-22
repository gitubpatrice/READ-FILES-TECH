package com.readfilestech.read_files_tech.data.tools

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** What [DuplicateSelection.trashable] lets go to the trash, and how many ticked files it kept back. */
data class TrashPlan(val paths: List<String>, val keptBack: Int)

/**
 * The files ticked for the trash on the duplicates screen, with its one rule: NEVER every copy of a group —
 * at least one stays, whatever the tab a file was ticked in. The 2.15.3 kept that rule in the duplicates tab
 * only, while the "Largest" tab ticked into the same selection without it: every copy of a group could be
 * deleted, for good (`duplicates_screen.dart:102, 315`, inventory D7). A file of no group (a large file)
 * may always be ticked.
 */
class DuplicateSelection(private val groups: List<DuplicateGroup>) {
    private val groupOf: Map<String, DuplicateGroup> = buildMap {
        groups.forEach { group -> group.files.forEach { put(it.path, group) } }
    }

    /** Whether [path] may be ticked or unticked now: unticking always; ticking unless it is its group's last copy left. */
    fun canToggle(path: String, ticked: Set<String>): Boolean =
        path in ticked || groupOf[path]?.let { group -> group.files.count { it.path in ticked } < group.files.size - 1 } ?: true

    /**
     * What may go to the trash of [ticked], checked again on the disk at the last moment. A copy goes only if
     * it is still the file that was compared, and if a copy of its group that STAYS still is too — another app
     * may have deleted or rewritten it since. Every other ticked copy is kept back, and counted. [ticked]
     * holding a whole group never happens through [canToggle]; it is refused here all the same.
     *
     * @param unchanged whether a file is still as listed (size, date); the disk's answer by default.
     */
    fun trashable(ticked: Set<String>, unchanged: (DuplicateFile) -> Boolean = ::isUnchanged): TrashPlan {
        val paths = mutableListOf<String>()
        var keptBack = 0
        for (path in ticked) {
            val group = groupOf[path]
            val allowed = group == null ||
                (unchanged(group.files.first { it.path == path }) && group.files.any { it.path !in ticked && unchanged(it) })
            if (allowed) paths += path else keptBack++
        }
        return TrashPlan(paths, keptBack)
    }

    /**
     * The rule once [paths] went to the trash: each group keeps its other copies, one left alone included
     * — its group's last, never ticked (Claude review R24: a group dissolved freed its last copy).
     */
    fun without(paths: Set<String>): DuplicateSelection =
        DuplicateSelection(groups.map { group -> group.copy(files = group.files.filterNot { it.path in paths }) })

    companion object {
        /** The file at [file]'s path is a regular file of the size and date listed. */
        fun isUnchanged(file: DuplicateFile): Boolean = try {
            val attributes = Files.readAttributes(Paths.get(file.path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            attributes.isRegularFile && attributes.size() == file.size && attributes.lastModifiedTime().toMillis() == file.modifiedMs
        } catch (expected: IOException) {
            false
        }
    }
}
