package com.readfilestech.read_files_tech.data.viewer.text

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * What colouring a text at the cap costs on the JVM: the scan, the colours of the last row, the heap the
 * checkpoints keep — and, for the engine's choice, colouring rows of real code. Run by hand —
 * `RFT_MEASURE=1 ./gradlew :app:testDebugUnitTest --tests '*CodeColouringMeasureTest*' -i` — the numbers go
 * to the port's report; the gate does not wait for it. The files are those `tools/corpus/gen_text.py` makes.
 */
@EnabledIfEnvironmentVariable(named = "RFT_MEASURE", matches = "1")
class CodeColouringMeasureTest {
    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun atCap(chunk: String): ByteArray {
        val piece = chunk.toByteArray()
        return ByteArray(FileCaps.TEXT_VIEWER.toInt()) { piece[it % piece.size] }
    }

    private fun measure(label: String, bytes: ByteArray, language: CodeLanguage) = runBlocking {
        val document = TextDocument.of(bytes)
        val before = usedHeap()
        val colouring = CodeColouring(document, language)
        var start = System.nanoTime()
        colouring.scan()
        val scanned = (System.nanoTime() - start) / 1_000_000
        val kept = usedHeap() - before
        start = System.nanoTime()
        val last = colouring.spans(document.rowCount - 1)
        val lastRow = (System.nanoTime() - start) / 1_000_000
        println(
            "MEASURE %s: %d rows, scan %d ms, %d checkpoints (%d KB kept), last row %d ms, %d spans"
                .format(label, document.rowCount, scanned, colouring.checkpoints, kept shr 10, lastRow, last.size),
        )
        assertThat(colouring.checkpoints).isGreaterThan(1)
    }

    @Test
    fun `50 MiB in one line of code, of line feeds, of comment openers`() {
        measure("one line of JavaScript", atCap("var a=1;"), CodeLanguage.JAVASCRIPT)
        measure("line feeds", atCap("\n"), CodeLanguage.JAVASCRIPT)
        measure("comment openers, Dart", atCap("/*"), CodeLanguage.DART)
        measure("lines of PHP", atCap("<p class=\"a\"><?php echo \$b; // c ?></p>\n"), CodeLanguage.PHP)
    }

    /** As the measure of dev.snipme:highlights 1.1.0 (the report's table): one row, then 64, 1 000 and 10 000 lines. */
    @Test
    fun `rows of real code, as the engines were measured`() = runBlocking {
        val line = "function f(a, b) { var c = \"some string\"; /* comment */ return a + b * 2 + 0x1F; } // end\n"
        for ((label, text) in listOf(
            "one row of 4096 characters" to line.trimEnd().repeat(ROW_REPEATS).take(TextDocument.ROW_BYTES),
            "64 rows" to line.repeat(64),
            "1000 lines" to line.repeat(1000),
            "10000 lines" to line.repeat(10_000),
        )) {
            val document = TextDocument.of(text.toByteArray())
            repeat(2) { colourAll(document) }
            val start = System.nanoTime()
            val spans = colourAll(document)
            println("MEASURE own tokenizer, %s: %d spans, %.1f ms".format(label, spans, (System.nanoTime() - start) / 1e6))
        }
    }

    /** Every row coloured, as a scroll from the top to the bottom would. */
    private suspend fun colourAll(document: TextDocument): Int {
        val colouring = CodeColouring(document, CodeLanguage.JAVASCRIPT)
        colouring.scan()
        return (0 until document.rowCount).sumOf { colouring.spans(it).size }
    }

    private companion object {
        const val ROW_REPEATS = 50
    }
}
