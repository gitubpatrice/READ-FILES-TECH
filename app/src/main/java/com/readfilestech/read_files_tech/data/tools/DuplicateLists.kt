package com.readfilestech.read_files_tech.data.tools

/**
 * A scan's report as the duplicates screen shows it once files went to the trash ([gone]), and which of those
 * came back — the trash's "Undo". The 2.15.x dropped them from its lists for good: back on the disk, they showed
 * again only after a new scan.
 */
object DuplicateLists {
    /** [report] without the [gone] files: a group left with one copy is no longer listed (its copy stays its last, DuplicateSelection). */
    fun without(report: DuplicateReport, gone: Set<String>): DuplicateReport {
        if (gone.isEmpty()) return report
        val groups = report.groups
            .map { group -> group.copy(files = group.files.filterNot { it.path in gone }) }
            .filter { it.files.size >= 2 }
        return report.copy(groups = groups, largest = report.largest.filterNot { it.path in gone })
    }

    /**
     * Those of [gone] back where they were, as they were: the trash moves a file, size and date kept. One changed
     * meanwhile stays out of the lists, as a new scan would compare it again.
     *
     * @param unchanged whether a file is still as listed; the disk's answer by default.
     */
    fun returned(
        report: DuplicateReport,
        gone: Set<String>,
        unchanged: (DuplicateFile) -> Boolean = DuplicateSelection::isUnchanged,
    ): Set<String> {
        val listed = (report.groups.flatMap(DuplicateGroup::files) + report.largest).associateBy(DuplicateFile::path)
        return gone.filterTo(HashSet()) { path -> listed[path]?.let(unchanged) == true }
    }
}
