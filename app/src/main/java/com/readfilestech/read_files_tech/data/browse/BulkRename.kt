package com.readfilestech.read_files_tech.data.browse

import com.readfilestech.read_files_tech.data.search.SearchPattern
import java.io.File
import java.io.IOException
import java.util.Locale

/** How the bulk rename makes each new name (`BulkRenameScreen` of the 2.15.3, its three modes). */
sealed interface RenameRule {
    /** `<base>_<number>`: [start], then one more per item, on [digits] digits at least (`Photo_001`). */
    data class Numbering(val base: String, val start: Int, val digits: Int) : RenameRule

    /** `<prefix><name><suffix>`, the suffix before the extension. */
    data class Affixes(val prefix: String, val suffix: String) : RenameRule

    /** Each match of [pattern] in the name replaced by [replacement] (`$1` for a group); no pattern, no change at all. */
    data class Replace(val pattern: SearchPattern?, val replacement: String) : RenameRule
}

/** Why a new name is refused, before anything is renamed. */
enum class NameProblem {
    /** Empty, `.` or `..`, a `/`, a `\` or a control character. */
    INVALID,

    /** Over 255 bytes in UTF-8, the longest name a folder of the phone holds. */
    TOO_LONG,

    /** Two items of the batch would take this name — case ignored, as the phone's storage ignores it. */
    DUPLICATE,

    /** Another item of the folder has this name, case ignored — one of the batch too, before it is renamed. */
    TAKEN,
}

/** An item and its new name; [problem] says why it cannot take it. */
data class RenameProposal(val entry: FileEntry, val newName: String, val problem: NameProblem?) {
    val changes: Boolean get() = newName != entry.name
}

/** What a batch did: [renamed] items, then the items not renamed with their reasons, in order. */
data class RenameReport(val renamed: Int, val failures: List<Pair<File, IOException>>)

/**
 * The bulk rename of the explorer's selection (`bulk_rename_screen.dart` of the 2.15.3): every new name
 * worked out and checked BEFORE anything is renamed — the whole batch refused on one name invalid, twice in
 * the batch, or already in the folder. The 2.15.3 compared the new names to each other case included, and
 * checked the folder file by file while renaming: `A.jpg` beside an `a.jpg` passed, on a storage that
 * ignores case. Each item is then renamed by the explorer's own [FileActions.rename] — its reservation of
 * the new name, its rollback, its case-only change — never by a second implementation.
 *
 * The extension (from the LAST dot) is kept when asked; a folder has none, nor has a hidden `.bashrc`: the
 * 2.15.3 took its dot for an extension and named it `.bashr` + `.`… (`bulk_rename_screen.dart:49-52`).
 */
object BulkRename {
    /**
     * Each of [entries] with its new name under [rule]. [existing] gives the names in a folder, all of them,
     * hidden ones included; [check] runs between two names — a cancellation, a deadline.
     *
     * @throws com.readfilestech.read_files_tech.data.search.InvalidPatternException a replacement naming a
     *   group the pattern does not have.
     */
    fun preview(
        entries: List<FileEntry>,
        rule: RenameRule,
        keepExtension: Boolean,
        existing: (File) -> Set<String>,
        check: () -> Unit = {},
    ): List<RenameProposal> {
        val names = entries.mapIndexed { index, entry ->
            check()
            newName(entry, index, rule, keepExtension, check)
        }
        val counts = names.groupingBy(::key).eachCount()
        val taken = entries.mapNotNull { it.file.absoluteFile.parentFile }.distinct()
            .associateWith { folder -> existing(folder).mapTo(HashSet(), ::key) }
        return entries.zip(names) { entry, name ->
            val problem = when {
                !FileNames.isValid(name) -> NameProblem.INVALID
                name.toByteArray(Charsets.UTF_8).size > FileActions.MAX_NAME_BYTES -> NameProblem.TOO_LONG
                counts.getValue(key(name)) > 1 -> NameProblem.DUPLICATE
                // Its own name under another case is its own: `photo.JPG` → `photo.jpg` (FileActions).
                key(name) != key(entry.name) && key(name) in taken[entry.file.absoluteFile.parentFile].orEmpty() -> NameProblem.TAKEN
                else -> null
            }
            RenameProposal(entry, name, problem)
        }
    }

    /**
     * Renames each proposal that changes a name, through [rename] (FileActions.rename): a failure does not
     * stop the batch, and the report names the items not renamed and why.
     */
    fun apply(proposals: List<RenameProposal>, rename: (File, String) -> File): RenameReport {
        require(proposals.none { it.problem != null }) { "a batch with a refused name" }
        var renamed = 0
        val failures = mutableListOf<Pair<File, IOException>>()
        for (proposal in proposals.filter(RenameProposal::changes)) {
            try {
                rename(proposal.entry.file, proposal.newName)
                renamed++
            } catch (e: IOException) {
                failures += proposal.entry.file to e
            }
        }
        return RenameReport(renamed, failures)
    }

    private fun newName(entry: FileEntry, index: Int, rule: RenameRule, keepExtension: Boolean, check: () -> Unit): String {
        val name = entry.name
        // The 2.15.3: an empty pattern leaves the name as it is, extension included.
        if (rule is RenameRule.Replace && rule.pattern == null) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = !entry.isDirectory && dot > 0 && dot < name.length - 1
        val stem = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension && keepExtension) name.substring(dot) else ""
        val newStem = when (rule) {
            is RenameRule.Numbering -> "${rule.base}_${(rule.start.toLong() + index).toString().padStart(rule.digits, '0')}"
            is RenameRule.Affixes -> "${rule.prefix}$stem${rule.suffix}"
            is RenameRule.Replace -> requireNotNull(rule.pattern).replaceAll(stem, rule.replacement, check)
        }
        return newStem + extension
    }

    /** Names compared as the phone's storage compares them: case ignored. */
    private fun key(name: String): String = name.lowercase(Locale.ROOT)
}
