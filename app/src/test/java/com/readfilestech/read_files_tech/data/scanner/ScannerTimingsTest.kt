package com.readfilestech.read_files_tech.data.scanner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * Timings on the desktop JVM, run on request only (`RFT_SCANNER_MEASURE=1`): relative values, the phone
 * is several times slower. Written to `app/build/scanner-timings.txt`.
 */
@EnabledIfEnvironmentVariable(named = "RFT_SCANNER_MEASURE", matches = "1")
class ScannerTimingsTest {
    private fun bestOf(runs: Int, block: () -> Unit): Double {
        block() // warm-up
        return (1..runs).minOf {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1e6
        }
    }

    @Test
    fun `detection and filters`() {
        val quad = listOf(Point(780.0, 350.0), Point(3180.0, 510.0), Point(3330.0, 2650.0), Point(640.0, 2520.0))
        val photo = ScanTestImages.scene(4000, 3000, quad, ScanTestImages.textPage(), ScanTestImages.wood())
        // What the app decodes of a 12 Mpx photo for the detection: half its sides (ScanPages).
        val decoded = ScanImages.downscale(photo, 2000)
        val text = ScanTestImages.textPage()
        val page = RgbImage(2480, 3508, IntArray(2480 * 3508) { text((it % 2480) / 2480.0, (it / 2480) / 3508.0) })
        val rows = object : PixelRows {
            val pixels = page.pixels.copyOf()
            override val width = page.width
            override val height = page.height

            override fun read(y: Int, rows: Int, into: IntArray) = System.arraycopy(pixels, y * width, into, 0, rows * width)

            override fun write(y: Int, rows: Int, from: IntArray) = System.arraycopy(from, 0, pixels, y * width, rows * width)
        }
        val lines = listOf(
            "detection, 12 Mpx photo in memory (downscales included): %.0f ms".format(bestOf(5) { DocumentDetector.locate(photo) }),
            "detection, the 2000 x 1500 the app decodes of it: %.0f ms".format(bestOf(5) { DocumentDetector.locate(decoded) }),
        ) + PageFilter.entries.drop(1).map { filter ->
            "filter %s, 2480 x 3508 page: %.0f ms".format(filter, bestOf(3) { PageFilters.apply(filter, rows) })
        }
        File("build/scanner-timings.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        lines.forEach(::println)
    }
}
