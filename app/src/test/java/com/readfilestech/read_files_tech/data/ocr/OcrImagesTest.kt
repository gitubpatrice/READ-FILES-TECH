package com.readfilestech.read_files_tech.data.ocr

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import org.junit.jupiter.api.Test

class OcrImagesTest {
    private fun decodedPixels(width: Int, height: Int): Long {
        val sample = OcrImages.sampleFor(width, height)
        return ((width + sample - 1) / sample).toLong() * ((height + sample - 1) / sample)
    }

    @Test
    fun `a photo of 12 megapixels is read whole, every pixel of its characters kept`() {
        // The Galaxy S9's sensor, upright or not.
        assertThat(OcrImages.sampleFor(4032, 3024)).isEqualTo(1)
        assertThat(OcrImages.sampleFor(3024, 4032)).isEqualTo(1)
        // A screenshot, and the largest picture of 16 Mpx.
        assertThat(OcrImages.sampleFor(1080, 2400)).isEqualTo(1)
        assertThat(OcrImages.sampleFor(4000, 4000)).isEqualTo(1)
    }

    @Test
    fun `a larger picture is halved until it holds 16 megapixels at most, never below`() {
        // 48 Mpx and 50 Mpx sensors at full resolution: halved, to 12 and 12.5 Mpx.
        assertThat(OcrImages.sampleFor(8000, 6000)).isEqualTo(2)
        assertThat(OcrImages.sampleFor(8160, 6120)).isEqualTo(2)
        // One pixel over the budget is enough to halve.
        assertThat(OcrImages.sampleFor(4000, 4001)).isEqualTo(2)
        // A long screenshot keeps its text half its size, not an eighth.
        assertThat(OcrImages.sampleFor(1080, 20_000)).isEqualTo(2)
        // The largest pictures the viewer's bounds let through (64 Mpx): 8000 x 8000 halved to 16 Mpx exactly;
        // 12 000 x 5333 halved would hold 16 002 000 pixels, divided by four.
        assertThat(OcrImages.sampleFor(8000, 8000)).isEqualTo(2)
        assertThat(OcrImages.sampleFor(12_000, 5333)).isEqualTo(4)
        for ((width, height) in listOf(8000 to 6000, 4000 to 4001, 1080 to 20_000, 8000 to 8000, 12_000 to 5333, 7999 to 7999)) {
            assertThat(decodedPixels(width, height)).isAtMost(OcrImages.MAX_PIXELS)
            // The smallest power of two that does: half of it would not.
            val sample = OcrImages.sampleFor(width, height)
            val half = sample / 2
            assertThat(((width + half - 1) / half).toLong() * ((height + half - 1) / half)).isGreaterThan(OcrImages.MAX_PIXELS)
        }
    }

    @Test
    fun `whatever the viewer accepts decodes within the budget, the extremes of its bounds included`() {
        assertThat(width64Mpx().let { (w, h) -> decodedPixels(w, h) }).isAtMost(OcrImages.MAX_PIXELS)
        assertThat(decodedPixels(12_000, 1)).isAtMost(OcrImages.MAX_PIXELS)
        assertThat(OcrImages.sampleFor(1, 1)).isEqualTo(1)
    }

    /** A picture of exactly [FileCaps.IMAGE_PIXELS], 12 000 pixels wide. */
    private fun width64Mpx(): Pair<Int, Int> = 12_000 to (FileCaps.IMAGE_PIXELS / 12_000).toInt()
}
