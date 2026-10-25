package com.readfilestech.read_files_tech.ui.viewer.image

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The viewer's pinch and drag: the 2.15.3's bounds, the point under the fingers, the edges. */
class ImageZoomTest {
    // A page of 1000 x 2000, a landscape image fitted in it at 1000 x 500.
    private val frame = ZoomFrame(boxWidth = 1000f, boxHeight = 2000f, imageWidth = 1000f, imageHeight = 500f)

    /** Where a point of the page shows the image, as a graphics layer scaled about the centre then moved. */
    private fun ImageZoom.imagePointUnder(px: Float, py: Float): Pair<Float, Float> =
        (500f + (px - 500f - x) / scale) to (1000f + (py - 1000f - y) / scale)

    @Test
    fun `a pinch keeps the point under the fingers where it was`() {
        val zoomed = ImageZoom.NONE.transformed(Offset(700f, 1000f), Offset(0f, 0f), 3f, frame)
        assertThat(zoomed.scale).isEqualTo(3f)
        val (x, y) = zoomed.imagePointUnder(700f, 1000f)
        assertThat(x).isWithin(0.01f).of(700f)
        assertThat(y).isWithin(0.01f).of(1000f)
        // A second pinch from there, elsewhere, holds its own point too.
        val again = zoomed.transformed(Offset(400f, 1000f), Offset(0f, 0f), 1.5f, frame)
        val before = zoomed.imagePointUnder(400f, 1000f)
        val after = again.imagePointUnder(400f, 1000f)
        assertThat(after.first).isWithin(0.01f).of(before.first)
        assertThat(after.second).isWithin(0.01f).of(before.second)
    }

    @Test
    fun `the scale stays between the 2_15_3's bounds, 0_5 and 6`() {
        assertThat(ImageZoom.NONE.transformed(Offset(500f, 1000f), Offset(0f, 0f), 100f, frame).scale).isEqualTo(6f)
        assertThat(ImageZoom.NONE.transformed(Offset(500f, 1000f), Offset(0f, 0f), 0.01f, frame).scale).isEqualTo(0.5f)
    }

    @Test
    fun `a drag never takes the image past its edges, and a smaller one stays centred`() {
        val zoomed = ImageZoom(scale = 2f)
        // 2000 wide in a page of 1000: 500 each way at most; 1000 high in 2000: not at all.
        val dragged = zoomed.transformed(Offset(500f, 1000f), Offset(5000f, 5000f), 1f, frame)
        assertThat(dragged.x).isEqualTo(500f)
        assertThat(dragged.y).isZero()
        assertThat(zoomed.transformed(Offset(500f, 1000f), Offset(-5000f, 0f), 1f, frame).x).isEqualTo(-500f)
        val small = ImageZoom.NONE.transformed(Offset(100f, 100f), Offset(300f, 300f), 0.5f, frame)
        assertThat(small.x).isZero()
        assertThat(small.y).isZero()
    }

    /** Zoomed, a drag is the image's; at its size, the gallery's — the pager's switch. */
    @Test
    fun `zoomed means larger than fitted`() {
        assertThat(ImageZoom.NONE.zoomed).isFalse()
        assertThat(ImageZoom(scale = 0.5f).zoomed).isFalse()
        assertThat(ImageZoom(scale = 1.005f).zoomed).isFalse()
        assertThat(ImageZoom(scale = 1.2f).zoomed).isTrue()
    }

    @Test
    fun `a double tap zooms on the point tapped, a second one comes back`() {
        val zoomed = ImageZoom.NONE.doubleTapped(Offset(800f, 1000f), frame)
        assertThat(zoomed.scale).isEqualTo(ImageZoom.DOUBLE_TAP_SCALE)
        val (x, _) = zoomed.imagePointUnder(800f, 1000f)
        assertThat(x).isWithin(0.01f).of(800f)
        assertThat(zoomed.doubleTapped(Offset(10f, 10f), frame)).isEqualTo(ImageZoom.NONE)
    }
}
