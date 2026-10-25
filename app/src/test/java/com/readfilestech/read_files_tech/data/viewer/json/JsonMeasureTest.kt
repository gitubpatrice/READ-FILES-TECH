package com.readfilestech.read_files_tech.data.viewer.json

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * What a JSON at the cap costs on the JVM: the heap the tree keeps, with the document's value opened, and the
 * indented lines. Run by hand — `RFT_MEASURE=1 ./gradlew :app:testDebugUnitTest --tests '*JsonMeasureTest*' -i`
 * — the numbers go to the port's report; the gate does not wait for it. The file is the one
 * `tools/corpus/gen_markdown_json.py` makes for the device (`json-at-cap-values.json`).
 */
@EnabledIfEnvironmentVariable(named = "RFT_MEASURE", matches = "1")
class JsonMeasureTest {
    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** 50 MiB: one array of strings, as many as the values' cap allows, as long as the size cap then allows. */
    private fun atCap(): ByteArray {
        val bytes = ByteArray(FileCaps.TEXT_VIEWER.toInt()) { ' '.code.toByte() }
        val count = FileCaps.JSON_VALUES - 1
        val value = ("\"" + "a".repeat((bytes.size - 2) / count - 3) + "\",").toByteArray()
        bytes[0] = '['.code.toByte()
        for (i in 0 until count) value.copyInto(bytes, 1 + i * value.size)
        // The last comma closes the array instead.
        bytes[count * value.size] = ']'.code.toByte()
        return bytes
    }

    @Test
    fun `a JSON of 50 MiB at the values' cap`() = runBlocking {
        val before = usedHeap()
        var bytes: ByteArray? = atCap()
        var start = System.nanoTime()
        val content = JsonFile.of(checkNotNull(bytes)) as JsonContent.Tree
        val parsed = (System.nanoTime() - start) / 1_000_000
        bytes = null
        start = System.nanoTime()
        val tree = JsonTree(content.document)
        val opened = (System.nanoTime() - start) / 1_000_000
        val kept = usedHeap() - before
        start = System.nanoTime()
        val pretty = JsonPretty(content.document)
        val indented = (System.nanoTime() - start) / 1_000_000
        val withPretty = usedHeap() - before
        println(
            "MEASURE values %d, parse %d ms, tree %d ms, pretty %d ms, heap kept %d MB, with the indented text %d MB"
                .format(content.document.valueCount, parsed, opened, indented, kept shr 20, withPretty shr 20),
        )
        assertThat(tree.rowCount).isEqualTo(content.document.valueCount)
        assertThat(pretty.rowCount).isEqualTo(content.document.valueCount + 1)
    }
}
