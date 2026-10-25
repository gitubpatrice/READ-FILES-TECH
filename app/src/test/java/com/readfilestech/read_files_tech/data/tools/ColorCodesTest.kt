package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

/** The oracle is Python's colorsys, on a grid of 512 colours (`colors-oracle.json`), not the code. */
class ColorCodesTest {
    private val colours = JSONObject(resource("/tools/colors-oracle.json")).getJSONArray("colours").objects()

    private fun rgbOf(colour: JSONObject): Rgb {
        val rgb = colour.getJSONArray("rgb")
        return Rgb(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2))
    }

    @Test
    fun `HSL and HSV are colorsys's, the hue in degrees`() {
        for (colour in colours) {
            val rgb = rgbOf(colour)
            val hsl = colour.getJSONArray("hsl")
            val hsv = colour.getJSONArray("hsv")
            with(rgb.toHsl()) {
                assertThat(hue).isWithin(TOLERANCE).of(hsl.getDouble(0))
                assertThat(saturation).isWithin(TOLERANCE).of(hsl.getDouble(1))
                assertThat(lightness).isWithin(TOLERANCE).of(hsl.getDouble(2))
            }
            with(rgb.toHsv()) {
                assertThat(hue).isWithin(TOLERANCE).of(hsv.getDouble(0))
                assertThat(saturation).isWithin(TOLERANCE).of(hsv.getDouble(1))
                assertThat(value).isWithin(TOLERANCE).of(hsv.getDouble(2))
            }
        }
    }

    @Test
    fun `the codes shown are Python's, rounded half up`() {
        for (colour in colours) {
            val rgb = rgbOf(colour)
            assertThat(rgb.hex).isEqualTo(colour.getString("hex"))
            assertThat(rgb.hslCode).isEqualTo(colour.getString("hslCode"))
            assertThat(rgb.rgbCode).isEqualTo("rgb(${rgb.red}, ${rgb.green}, ${rgb.blue})")
        }
    }

    @Test
    fun `every round trip is exact`() {
        for (colour in colours) {
            val rgb = rgbOf(colour)
            assertThat(rgb.toHsl().toRgb()).isEqualTo(rgb)
            assertThat(rgb.toHsv().toRgb()).isEqualTo(rgb)
            assertThat(Rgb.parseHex(rgb.hex)).isEqualTo(rgb)
            assertThat(Rgb.parseHex(rgb.hex.lowercase().removePrefix("#"))).isEqualTo(rgb)
        }
    }

    @Test
    fun `what is not a hexadecimal colour is not read as one`() {
        for (text in listOf("", "#", "#12345", "#1234567", "#GG0000", "#１２３４５６", "12 34 56")) {
            assertThat(Rgb.parseHex(text)).isNull()
        }
    }

    @Test
    fun `a hue of 360 degrees is red, as 0 is`() {
        assertThat(Hsv(360.0, 1.0, 1.0).toRgb()).isEqualTo(Rgb(255, 0, 0))
        assertThat(Hsl(360.0, 1.0, 0.5).toRgb()).isEqualTo(Rgb(255, 0, 0))
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
