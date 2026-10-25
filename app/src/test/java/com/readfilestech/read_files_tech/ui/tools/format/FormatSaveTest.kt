package com.readfilestech.read_files_tech.ui.tools.format

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.tools.CodeFormat
import com.readfilestech.read_files_tech.data.tools.FormatAction
import com.readfilestech.read_files_tech.data.tools.Formattable
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CancellationException

/** "Save" of the format tool: the result whole in `Conversions/`, nothing at all when the screen is left meanwhile. */
class FormatSaveTest {
    @TempDir
    lateinit var tmp: File

    private fun storage() = OutputStorage(
        FlutterPreferences(InMemorySharedPreferences()),
        { File(tmp, "emulated0") },
        { null },
        File(tmp, "app_flutter"),
    ) { LocalDateTime.of(2026, 10, 22, 9, 5, 7) }

    private val conversions get() = File(tmp, "emulated0/Files Tech/Conversions")

    @Test
    fun `the result goes whole to Conversions, under the 2_15_3's name, in UTF-8`() {
        val json = """{"été":[1,2]}""".encodeToByteArray()
        val ready = runBlocking { CodeFormat.prepare(FormatAction.JSON_MINIFIED, json, json.size) } as Formattable.Ready
        val file = runBlocking { storage().saveFormatted(ready, FormatAction.JSON_MINIFIED) }
        assertThat(file).isEqualTo(File(conversions, "result_20261022_090507.json"))
        assertThat(file.readText(Charsets.UTF_8)).isEqualTo("""{"été":[1,2]}""")
    }

    /** It wrote and published the result: nothing checked for a stop between its last piece and the commit. */
    @Test
    fun `a save stopped once its last piece is written publishes nothing`() {
        val storage = storage()
        assertThrows<CancellationException> {
            runBlocking {
                val job = coroutineContext.job
                // The whole result written, its last checkpoint passed — then the screen is left.
                val ready = Formattable.Ready { out, _ ->
                    out.append("x".repeat(100_000))
                    job.cancel()
                }
                storage.saveFormatted(ready, FormatAction.JSON_INDENTED)
            }
        }
        assertThat(conversions.list().orEmpty().toList()).isEmpty()
    }
}
