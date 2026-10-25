package com.readfilestech.read_files_tech.data.tools

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A colour of the screen: red, green and blue from 0 to 255. */
data class Rgb(val red: Int, val green: Int, val blue: Int) {
    init {
        require(red in 0..MAX && green in 0..MAX && blue in 0..MAX) { "component out of 0..255" }
    }

    /** `#RRGGBB`, upper case (`ColorPickerScreen._hex` of the 2.15.3). */
    val hex: String get() = String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue)

    /** `rgb(r, g, b)`. */
    val rgbCode: String get() = "rgb($red, $green, $blue)"

    /** `hsl(h, s%, l%)`, each rounded to a whole number, half up, as the 2.15.3's `toStringAsFixed(0)`. */
    val hslCode: String get() {
        val hsl = toHsl()
        // 359.5° rounds to 360, the same hue as 0.
        val hue = hsl.hue.roundHalfUp() % FULL_TURN
        return "hsl($hue, ${(hsl.saturation * PERCENT).roundHalfUp()}%, ${(hsl.lightness * PERCENT).roundHalfUp()}%)"
    }

    /** Hue, saturation and lightness, as CSS and Python's `colorsys.rgb_to_hls` compute them. */
    fun toHsl(): Hsl {
        val (max, min, hue) = hueOf()
        val lightness = (max + min) / 2
        val saturation = if (max == min) 0.0 else (max - min) / (1 - abs(2 * lightness - 1))
        return Hsl(hue, saturation, lightness)
    }

    /** Hue, saturation and value — the picker's coordinates (Python's `colorsys.rgb_to_hsv`). */
    fun toHsv(): Hsv {
        val (max, min, hue) = hueOf()
        return Hsv(hue, if (max == 0.0) 0.0 else (max - min) / max, max)
    }

    /** The largest and the smallest component, from 0 to 1, and the hue in degrees (0 for a grey). */
    private fun hueOf(): Triple<Double, Double, Double> {
        val r = red.toDouble() / MAX
        val g = green.toDouble() / MAX
        val b = blue.toDouble() / MAX
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val sector = when {
            delta == 0.0 -> 0.0
            max == r -> ((g - b) / delta).mod(SECTORS)
            max == g -> (b - r) / delta + GREEN_SECTOR
            else -> (r - g) / delta + BLUE_SECTOR
        }
        return Triple(max, min, sector * DEGREES_PER_SECTOR)
    }

    companion object {
        const val MAX = 255

        /** `#RRGGBB` or `RRGGBB`, either case; null for anything else. */
        fun parseHex(text: String): Rgb? {
            val digits = text.removePrefix("#")
            if (digits.length != HEX_LENGTH || digits.any { Character.digit(it, HEX) < 0 || it.code >= ASCII_END }) return null
            val value = digits.toInt(HEX)
            return Rgb(value shr RED_SHIFT and MAX, value shr GREEN_SHIFT and MAX, value and MAX)
        }

        /** From components from 0 to 1, each rounded to the nearest of the 256 steps. */
        internal fun of(r: Double, g: Double, b: Double): Rgb = Rgb(step(r), step(g), step(b))

        private fun step(component: Double): Int = (component.coerceIn(0.0, 1.0) * MAX).roundToInt()

        private const val HEX = 16
        private const val HEX_LENGTH = 6
        private const val ASCII_END = 0x80
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
    }
}

/** Hue in degrees, from 0 (included) to 360; saturation and lightness from 0 to 1. */
data class Hsl(val hue: Double, val saturation: Double, val lightness: Double) {
    fun toRgb(): Rgb {
        val chroma = (1 - abs(2 * lightness - 1)) * saturation
        return fromChroma(hue, chroma, lightness - chroma / 2)
    }
}

/** Hue in degrees, from 0 (included) to 360; saturation and value from 0 to 1. */
data class Hsv(val hue: Double, val saturation: Double, val value: Double) {
    fun toRgb(): Rgb {
        val chroma = value * saturation
        return fromChroma(hue, chroma, value - chroma)
    }
}

/** The colour of [hue] at [chroma], lifted by [lightest] (the smallest component): CSS Color 4's formula. */
private fun fromChroma(hue: Double, chroma: Double, lightest: Double): Rgb {
    val sector = hue.mod(FULL_TURN.toDouble()) / DEGREES_PER_SECTOR
    val parts = doubleArrayOf(chroma, chroma * (1 - abs(sector.mod(2.0) - 1)), 0.0)
    val order = SECTOR_PARTS[minOf(sector.toInt(), SECTOR_PARTS.lastIndex)]
    return Rgb.of(parts[order[0]] + lightest, parts[order[1]] + lightest, parts[order[2]] + lightest)
}

/**
 * For each sixth of the circle from red, which part red, green and blue take: 0 the chroma, 1 the
 * second largest, 2 nothing — red, then yellow (red and green), green, cyan, blue, magenta.
 */
private val SECTOR_PARTS = arrayOf(
    intArrayOf(0, 1, 2),
    intArrayOf(1, 0, 2),
    intArrayOf(2, 0, 1),
    intArrayOf(2, 1, 0),
    intArrayOf(1, 2, 0),
    intArrayOf(0, 2, 1),
)

/** Where the hue's sector counts from when green, or blue, is the largest component. */
private const val GREEN_SECTOR = 2.0
private const val BLUE_SECTOR = 4.0

/** Half up, as Dart's `toStringAsFixed(0)` for the positive values here (Kotlin's `roundToInt` too). */
private fun Double.roundHalfUp(): Int = roundToInt()

private const val FULL_TURN = 360
private const val SECTORS = 6.0
private const val DEGREES_PER_SECTOR = 60.0
private const val PERCENT = 100
