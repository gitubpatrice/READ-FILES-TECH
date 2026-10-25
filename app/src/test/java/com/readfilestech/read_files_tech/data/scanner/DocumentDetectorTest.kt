package com.readfilestech.read_files_tech.data.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * The detection, against two oracles that are not the code: the bench's own detector (the 300-pixel
 * images it worked on, saved losslessly, and the corners it found there — RAPPORT-SCANNER §5), and scenes
 * rendered here whose corners are known exactly. A change to any threshold shows here.
 *
 * Every test is bounded: a broken border tracer must fail a test, not hang the build.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DocumentDetectorTest {
    /**
     * The bench's retained detector (`pure+oc30+fb4+refine` then, `fb4` since dropped; tools/scanner-bench, scratch export of
     * 2026-10-12) on its synthetic photos syn000 (text on wood), syn012 (a page that is mostly a photo, on a
     * white desk: the base detector picked the photo, IoU 0.36), syn022 (a corner out of the frame), syn010
     * (a finger over a corner on a white desk: nothing). Same image, same corners, to the pixel.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "syn000;101,6 230,15 235,223 86,216",
            "syn012;81,92 201,114 174,291 45,260",
            "syn022;43,9 171,0 204,179 69,197",
            "syn010;none",
        ],
    )
    fun `the bench's photos give the bench's corners`(id: String, expected: String) {
        val found = DocumentDetector.detect(ScanTestImages.resource("bench-$id-work.png"))
        if (expected == "none") {
            assertThat(found).isNull()
        } else {
            val corners = expected.split(" ").map { p -> p.split(",").let { Point(it[0].toDouble(), it[1].toDouble()) } }
            assertThat(found).isEqualTo(corners)
        }
    }

    private val landscape = listOf(Point(310.0, 140.0), Point(1270.0, 205.0), Point(1330.0, 1060.0), Point(255.0, 1010.0))

    @Test
    fun `a page in perspective on wood is found, its corners within a pixel or two of the truth`() {
        val photo = ScanTestImages.scene(1600, 1200, landscape, ScanTestImages.textPage(), ScanTestImages.wood())
        val found = requireNotNull(DocumentDetector.locate(photo))
        val inPixels = found.map { Point(it.x * 1600, it.y * 1200) }
        // Top-left, top-right, bottom-right, bottom-left: the order the crop screen and the straightening rely on.
        assertThat(ScanTestImages.cornerError(inPixels, landscape)).isLessThan(0.002)
    }

    @Test
    fun `the refinement removes most of the 300-pixel quantisation`() {
        val photo = ScanTestImages.scene(1600, 1200, landscape, ScanTestImages.textPage(), ScanTestImages.dark())
        val work = ScanImages.downscale(photo, DocumentDetector.WORK_SIDE)
        val coarse = requireNotNull(DocumentDetector.detect(work)).map {
            Point(it.x * 1600 / work.width, it.y * 1200 / work.height)
        }
        val refined = requireNotNull(DocumentDetector.locate(photo)).map { Point(it.x * 1600, it.y * 1200) }
        val before = ScanTestImages.cornerError(coarse, landscape)
        val after = ScanTestImages.cornerError(refined, landscape)
        assertThat(after).isLessThan(0.0015)
        assertThat(after).isLessThan(before / 2)
    }

    /** RAPPORT-SCANNER §5, `outer`: an inner picture outranks the page by its square angles; the page wins. */
    @Test
    fun `a page that is mostly a picture is framed whole, not by its picture`() {
        val photo = ScanTestImages.scene(1600, 1200, landscape, ScanTestImages.picturePage(), ScanTestImages.dark())
        val found = requireNotNull(DocumentDetector.locate(photo)).map { Point(it.x * 1600, it.y * 1200) }
        assertThat(ScanTestImages.cornerError(found, landscape)).isLessThan(0.005)
    }

    /**
     * Under 10 % of the photo nothing is a document (the bench's `fb4` pass dropped: on real S9 photos it only
     * ever chose a label inside a document filling the photo). A receipt far away is left to the user.
     */
    @Test
    fun `a small receipt far away is left to the user`() {
        // 260 × 440 pixels of 1600 × 1200: 6 % of the photo.
        val small = rotated(Point(800.0, 600.0), 260.0, 440.0, 0.12)
        val photo = ScanTestImages.scene(1600, 1200, small, ScanTestImages.textPage(), ScanTestImages.dark())
        assertThat(DocumentDetector.locate(photo)).isNull()
    }

    /**
     * A small, perfectly square card would outrank a page in perspective (the angle outweighs the area): under
     * 10 % of the photo it is never a candidate.
     */
    @Test
    fun `a large page wins over a small square card beside it`() {
        // 300 × 380 pixels: 6 % of the photo, square to the frame; the page leans and takes half of it.
        val card = listOf(Point(1250.0, 700.0), Point(1550.0, 700.0), Point(1550.0, 1080.0), Point(1250.0, 1080.0))
        val page = listOf(Point(120.0, 160.0), Point(1050.0, 90.0), Point(1150.0, 1080.0), Point(60.0, 1120.0))
        val pageThenCard: (Int, Int) -> Int = { x, y ->
            if (x in 1250 until 1550 && y in 700 until 1080) ScanTestImages.rgb(240, 240, 235) else ScanTestImages.rgb(32, 32, 34)
        }
        val photo = ScanTestImages.scene(1600, 1200, page, ScanTestImages.textPage(), pageThenCard)
        val found = requireNotNull(DocumentDetector.locate(photo)).map { Point(it.x * 1600, it.y * 1200) }
        assertThat(ScanTestImages.cornerError(found, page)).isLessThan(0.005)
        assertThat(ScanTestImages.cornerError(found, card)).isGreaterThan(0.2)
    }

    /** Upstream refuses a candidate of 92 % of the frame or more: a page filling the photo is left to the user. */
    @Test
    fun `a page filling the whole photo is not taken for a document`() {
        val filling = listOf(Point(15.0, 12.0), Point(1588.0, 10.0), Point(1590.0, 1190.0), Point(12.0, 1188.0))
        val photo = ScanTestImages.scene(1600, 1200, filling, ScanTestImages.textPage(), ScanTestImages.dark())
        assertThat(DocumentDetector.locate(photo)).isNull()
    }

    /** Upstream's right-angle criterion: max |cos| of the corners below 0.4 (here 0.64: a 50-degree corner). */
    @Test
    fun `a sheared shape is not a document`() {
        // 500 across for 600 down: |cos| = 500 / 781 = 0.64, corners of 50 and 130 degrees.
        val sheared = listOf(Point(300.0, 300.0), Point(900.0, 300.0), Point(1400.0, 900.0), Point(800.0, 900.0))
        val photo = ScanTestImages.scene(1600, 1200, sheared, ScanTestImages.textPage(), ScanTestImages.dark())
        assertThat(DocumentDetector.locate(photo)).isNull()
    }

    @Test
    fun `nothing but a background, nothing found`() {
        val photo = ScanTestImages.scene(
            1600,
            1200,
            listOf(Point(-10.0, -10.0), Point(-5.0, -10.0), Point(-5.0, -5.0), Point(-10.0, -5.0)),
            ScanTestImages.textPage(),
            ScanTestImages.wood()
        )
        assertThat(DocumentDetector.locate(photo)).isNull()
    }

    /** The bench's sample page (a hand's shadow across it), seen in perspective on wood. */
    @Test
    fun `the bench's shadowed page is found`() {
        val sample = ScanTestImages.resource("bench-syn011-page.jpg")
        val texture: (Double, Double) -> Int = { u, v ->
            sample.pixels[(v * (sample.height - 1)).toInt() * sample.width + (u * (sample.width - 1)).toInt()]
        }
        val quad = listOf(Point(420.0, 90.0), Point(1150.0, 140.0), Point(1230.0, 1110.0), Point(340.0, 1080.0))
        val photo = ScanTestImages.scene(1600, 1200, quad, texture, ScanTestImages.wood(seed = 7))
        val found = requireNotNull(DocumentDetector.locate(photo)).map { Point(it.x * 1600, it.y * 1200) }
        assertThat(ScanTestImages.cornerError(found, quad)).isLessThan(0.003)
    }

    @Test
    fun `a photo smaller than the working size still works`() {
        val quad = landscape.map { Point(it.x / 8, it.y / 8) }
        val photo = ScanTestImages.scene(200, 150, quad, ScanTestImages.textPage(), ScanTestImages.dark())
        val found = requireNotNull(DocumentDetector.locate(photo)).map { Point(it.x * 200, it.y * 150) }
        assertThat(ScanTestImages.cornerError(found, quad)).isLessThan(0.02)
    }

    /** Leaving the crop screen stops a detection between two passes. */
    @Test
    fun `a cancellation thrown at a checkpoint stops the detection`() {
        val photo = ScanTestImages.scene(1600, 1200, landscape, ScanTestImages.textPage(), ScanTestImages.wood())
        var passes = 0
        assertThrows<IllegalStateException> {
            DocumentDetector.locate(photo) {
                passes++
                check(passes < 3) { "cancelled" }
            }
        }
        assertThat(passes).isEqualTo(3)
    }

    private fun rotated(centre: Point, w: Double, h: Double, angle: Double): List<Point> =
        listOf(Point(-w / 2, -h / 2), Point(w / 2, -h / 2), Point(w / 2, h / 2), Point(-w / 2, h / 2)).map {
            Point(centre.x + it.x * cos(angle) - it.y * sin(angle), centre.y + it.x * sin(angle) + it.y * cos(angle))
        }
}
