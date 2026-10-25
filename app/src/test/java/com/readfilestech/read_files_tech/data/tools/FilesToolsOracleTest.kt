package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.browse.BulkRename
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.RenameRule
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.search.ContentEvent
import com.readfilestech.read_files_tech.data.search.ContentSearch
import com.readfilestech.read_files_tech.data.search.SearchPattern
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The four tools on the folders `tools/oracles/files_tools_oracle.py prepare` makes, what they find or make
 * left in `build/oracles/files/out/` for its `check`: `sha256sum` groups the duplicates, `grep -r` finds the
 * texts, 7-Zip and Python's `zipfile` test the archive member by member, Python's `re` renames. Skipped when
 * the folders are not there (an ordinary test run): the script runs this class itself.
 */
class FilesToolsOracleTest {
    private val base = File(System.getProperty("user.dir"), "build/oracles/files")
    private val input = File(base, "input")
    private val out = File(base, "out")

    private fun relative(root: File, path: String) = File(path).relativeTo(root).invariantSeparatorsPath

    @Test
    fun `the tools on the oracle's folders`() {
        assumeTrue(File(input, "ready").exists(), "run tools/oracles/files_tools_oracle.py")
        out.mkdirs()
        duplicates()
        search()
        zip()
        rename()
    }

    private fun duplicates() = runBlocking {
        val root = File(input, "dup")
        val report = DuplicateFinder.find(root)
        val groups = JSONArray(report.groups.map { group -> JSONArray(group.files.map { relative(root, it.path) }.sorted()) })
        File(out, "duplicates.json").writeText(groups.toString(1))
    }

    private fun search() = runBlocking {
        val root = File(input, "search")
        val queries = JSONArray(File(input, "search-queries.json").readText())
        val found = JSONObject()
        for (i in 0 until queries.length()) {
            val query = queries.getJSONObject(i)
            val text = query.getString("text")
            val matchCase = query.getBoolean("matchCase")
            val pattern = if (query.getBoolean("regex")) SearchPattern.regex(text, matchCase) else SearchPattern.literal(text, matchCase)
            val hits = ContentSearch.run(root, pattern).toList().filterIsInstance<ContentEvent.Found>().flatMap { it.hits }
            found.put(query.getString("id"), JSONArray(hits.map { relative(root, it.path) }.sorted()))
        }
        File(out, "search.json").writeText(found.toString(1))
    }

    private fun zip() {
        val root = File(input, "zip")
        val files = root.walkTopDown().filter(File::isFile).sortedBy { it.invariantSeparatorsPath }.toList()
        File(out, "archive.zip").outputStream().use { ZipCreation.write(files, it, FileCaps.ZIP_CREATE_TOTAL, {}) {} }
        val manifest = JSONObject()
        ZipCreation.entryNames(
            files.map(File::getName)
        ).zip(files).forEach { (name, file) -> manifest.put(name, relative(root, file.path)) }
        File(out, "zip-manifest.json").writeText(manifest.toString(1))
    }

    private fun rename() {
        val cases = JSONArray(File(input, "rename-cases.json").readText())
        val results = JSONArray()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val names = case.getJSONArray("names").let { array -> List(array.length()) { array.getString(it) } }
            val entries = names.map { FileEntry("/dir/$it", it, isDirectory = false, size = 0, modifiedMs = 0, isSymlink = false) }
            val rule = when (case.getString("mode")) {
                "numbering" -> RenameRule.Numbering(case.getString("base"), case.getInt("start"), case.getInt("digits"))
                "affixes" -> RenameRule.Affixes(case.getString("prefix"), case.getString("suffix"))
                else -> RenameRule.Replace(SearchPattern.regex(case.getString("pattern"), matchCase = true), case.getString("replacement"))
            }
            val proposals = BulkRename.preview(entries, rule, case.getBoolean("keepExtension"), { names.toSet() })
            results.put(
                JSONArray(proposals.map { JSONObject().put("name", it.newName).put("problem", it.problem?.name ?: JSONObject.NULL) })
            )
        }
        File(out, "rename.json").writeText(results.toString(1))
    }
}
