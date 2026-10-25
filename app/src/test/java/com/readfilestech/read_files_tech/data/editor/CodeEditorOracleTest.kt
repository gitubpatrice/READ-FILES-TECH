package com.readfilestech.read_files_tech.data.editor

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.history.EditorHistory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The code editor's side of the oracle `tools/oracles/editor_oracle.py`: each text of
 * `tools/corpus/editor/` — written by Python's codecs in UTF-8 with and without BOM, UTF-16 LE and BE
 * with BOM, Windows-1252; LF, CRLF, CR, mixed, no final line break — opened by the editor's real path,
 * saved without a change, then with its second line changed, into `build/editor-oracle/`, with the
 * history copy each save made. The script checks them with tools of its own: `sha256sum`, and `difflib`
 * on the lines decoded by the codec each file was made with. Run from `app/`.
 */
class CodeEditorOracleTest {
    @Test
    fun `the corpus saved without a change and with one line changed`() {
        val inputs = File("../tools/corpus/editor").listFiles().orEmpty().filter(File::isFile).sortedBy { it.name }
        assertThat(inputs.size).isAtLeast(MIN_INPUTS)
        val out = File("build/editor-oracle").apply {
            deleteRecursively()
            mkdirs()
        }
        val history = EditorHistory(File(out, "legacy"), File(out, "history"))
        for (input in inputs) {
            val work = input.copyTo(File(out, input.name))
            val opened = runBlocking { EditedFile.open(work) }
            val same = runBlocking { opened.save(opened.text, history) } as SaveOutcome.Saved
            work.copyTo(File(out, "${input.name}.same"))
            assertThat(work.readBytes()).isEqualTo(input.readBytes())
            newest(history, work).copyTo(File(out, "${input.name}.history1"))

            val lines = same.file.text.split('\n').toMutableList()
            lines[1] = VALUE
            val changed = runBlocking { same.file.save(lines.joinToString("\n"), history) }
            assertThat(changed).isInstanceOf(SaveOutcome.Saved::class.java)
            work.copyTo(File(out, "${input.name}.line"))
            newest(history, work).copyTo(File(out, "${input.name}.history2"))
        }
    }

    private fun newest(history: EditorHistory, file: File): File = history.versions(file.absolutePath).first().file

    private companion object {
        /** `VALUE` of the script: every character of it is in Windows-1252. */
        const val VALUE = "Ligne modifiée — 1 €"
        const val MIN_INPUTS = 10
    }
}
