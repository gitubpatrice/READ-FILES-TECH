package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The text tools and the CSV tools on the corpus `tools/oracles/txt_csv_tools_oracle.py prepare` writes, what they
 * count and write left in `build/oracles/txt-csv/out/` for its `check`: Python's `len`, `split`, `count` and
 * `replace` on the texts decoded by its own codecs, Python's `csv` on the merges. Skipped when the corpus is not
 * there (an ordinary test run): the script runs this class itself.
 */
class TxtCsvToolsOracleTest {
    private val base = File(System.getProperty("user.dir"), "build/oracles/txt-csv")
    private val input = File(base, "input")
    private val out = File(base, "out")

    @Test
    fun `the tools on the oracle's corpus`() {
        assumeTrue(File(input, "ready").exists(), "run tools/oracles/txt_csv_tools_oracle.py")
        out.mkdirs()
        texts()
        merges()
    }

    private fun read(name: String): TextBytes {
        val source = StorageFileSource(File(input, "texts/$name"))
        return runBlocking { TextBytes.read(source) }
    }

    private fun texts() {
        val stats = JSONObject()
        for (name in JSONObject(File(input, "texts.json").readText()).keys()) {
            val counted = read(name).stats()
            stats.put(name, JSONArray(listOf(counted.characters, counted.words, counted.lines)))
        }
        File(out, "stats.json").writeText(stats.toString(1))
        val counts = JSONObject()
        val cases = JSONArray(File(input, "replace-cases.json").readText())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val bytes = read(case.getString("file"))
            val query = case.getString("query")
            counts.put(case.getString("id"), bytes.count(query))
            val copy = File(out, "replace/${case.getString("id")}.${File(case.getString("file")).extension}")
            copy.parentFile.mkdirs()
            copy.outputStream().use { bytes.replaceAll(query, case.getString("replacement"), it, Long.MAX_VALUE) }
        }
        File(out, "counts.json").writeText(counts.toString(1))
    }

    private fun merges() {
        val merges = JSONObject(File(input, "csv.json").readText()).getJSONObject("merges")
        val made = JSONObject()
        for (name in merges.keys()) {
            val names = merges.getJSONArray(name)
            val files = List(names.length()) { File(input, "csv/${names.getString(it)}") }
            val target = File(out, "merge/$name.csv")
            target.parentFile.mkdirs()
            val merged = target.outputStream().use { CsvMerge.write(files, it) }
            made.put(name, JSONObject().put("rows", merged.rows).put("files", merged.files))
        }
        File(out, "merges.json").writeText(made.toString(1))
    }
}
