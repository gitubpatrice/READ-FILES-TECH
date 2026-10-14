package com.readfilestech.read_files_tech.data.editor

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The CSV editor's side of the oracle `tools/oracles/csv_editor_oracle.py`: each CSV made by real software
 * (Excel through COM, PowerShell, Python's `csv` — `tools/corpus/csv/`, `tools/corpus/csv-editor/`)
 * saved without a change, then with one cell changed, into `build/csv-editor-oracle/`. The script checks
 * them with tools of its own: `sha256sum`, and Python's `csv` module for the table. Run from `app/`.
 */
class CsvEditsOracleTest {
    @Test
    fun `the CSVs of real software, saved without a change and with one cell changed`() {
        val inputs = listOf(File("../tools/corpus/csv"), File("../tools/corpus/csv-editor")).flatMap { folder ->
            folder.listFiles { file -> file.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
        }
        assertThat(inputs.size).isAtLeast(MIN_INPUTS)
        val out = File("build/csv-editor-oracle").apply {
            deleteRecursively()
            mkdirs()
        }
        val manifest = inputs.map { input ->
            val edits = runBlocking { CsvEdits.read(StorageFileSource(input)) }
            val same = File(out, "${input.name}.same").apply { outputStream().use(edits::writeTo) }
            assertThat(same.readBytes()).isEqualTo(input.readBytes())
            if (edits.rowCount < 2) return@map "${input.name}\t-\t-\tEMPTY"
            if (edits.readOnly != null) return@map "${input.name}\t-\t-\tREAD_ONLY"
            // A file that does not say its encoding: the one it is read in, Windows-1252 for one of ASCII.
            edits.chosenEncoding = edits.encodingChoices.lastOrNull()
            val position = minOf(2, edits.rowCount - 1)
            val column = minOf(1, edits.columnCount - 1)
            val change = edits.set(position, column, VALUE)
            assertThat(change).isEqualTo(CellChange.CHANGED)
            File(out, "${input.name}.cell").outputStream().use(edits::writeTo)
            "${input.name}\t$position\t$column\t$change"
        }
        File(out, "manifest.tsv").writeText(manifest.joinToString("\n", postfix = "\n"))
    }

    private companion object {
        /** A separator of each kind and a quote: the new text must be quoted, its quotes doubled. */
        const val VALUE = "Édité; x, \"q\""
        const val MIN_INPUTS = 17
    }
}
