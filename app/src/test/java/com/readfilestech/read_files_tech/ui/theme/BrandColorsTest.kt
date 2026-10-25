package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * The colours of the alignment on the other apps (2026-10-25) keep their promise of contrast — measured here, not
 * supposed: a brand colour is chosen because it identifies, nothing makes it readable in advance. Text needs 4.5:1
 * (WCAG 1.4.3), a control against what surrounds it 3:1 (1.4.11). Modelled on Notes Tech's SemanticColorsTest.
 */
class BrandColorsTest {
    @Test
    fun `white reads on the checkerboard blue and on the danger red`() {
        // The filled buttons, the floating buttons and the messages; the error messages.
        assertThat(contrast(Damier.OnBlue, Damier.Blue)).isAtLeast(TEXT)
        assertThat(contrast(Color.White, DangerRed)).isAtLeast(TEXT)
    }

    @Test
    fun `the checkerboard's buttons stand out on both pages`() {
        assertThat(contrast(Damier.Blue, LightColors.background)).isAtLeast(NON_TEXT)
        assertThat(contrast(Damier.Blue, DarkColors.background)).isAtLeast(NON_TEXT)
    }

    @Test
    fun `the text of both palettes reads on what it is drawn on`() {
        for (scheme in listOf(LightColors, DarkColors)) {
            textPairs(scheme).forEach { (role, pair) ->
                assertWithRole(role, contrast(pair.first, pair.second))
            }
        }
    }

    @Test
    fun `the danger's red reads as text in both themes, on a page, a card and a dialog`() {
        for (scheme in listOf(LightColors, DarkColors)) {
            val red = dangerContentOn(scheme)
            assertThat(contrast(red, scheme.background)).isAtLeast(TEXT)
            assertThat(contrast(red, scheme.surface)).isAtLeast(TEXT)
            assertThat(contrast(red, scheme.surfaceContainerHigh)).isAtLeast(TEXT)
        }
        assertThat(dangerContentOn(LightColors)).isEqualTo(DangerRed)
    }

    /** Why the danger's red switches: the one of the filled buttons, written on the dark dialog, falls to 3.1:1. */
    @Test
    fun `the filled red alone would not read on the dark dialog`() {
        assertThat(contrast(DangerRed, DarkColors.surfaceContainerHigh)).isLessThan(TEXT)
    }

    /**
     * A dark surface takes a tool's light tone and the reverse — swapping the two would break no contrast assertion
     * that reads the tone the function returns, so the side is checked itself. On either card every tone stands out
     * as an icon: in light, amber, orange and cyan are darker than their Material 700, which read 1.8 to 2.9:1.
     */
    @Test
    fun `a tool's colour follows the surface it is drawn on`() {
        val tones = listOf(
            ToolColors.Pink, ToolColors.BlueGrey, ToolColors.Green, ToolColors.Indigo, ToolColors.Teal, ToolColors.Orange,
            ToolColors.Purple, ToolColors.Cyan, ToolColors.DeepOrange, ToolColors.DeepPurple, ToolColors.Blue,
            ToolColors.Amber, ToolColors.Red, ToolColors.Brown,
        )
        assertThat(isDarkSurface(DarkColors)).isTrue()
        assertThat(isDarkSurface(LightColors)).isFalse()
        tones.forEach { tone ->
            assertThat(tone.on(DarkColors).luminance()).isGreaterThan(tone.on(LightColors).luminance())
            assertThat(contrast(tone.on(DarkColors), DarkColors.surface)).isAtLeast(NON_TEXT)
            assertThat(contrast(tone.on(LightColors), LightColors.surface)).isAtLeast(NON_TEXT)
        }
    }

    /** A text field's outline and the icons tinted with it stand out (1.4.11): the dark border read 1.4:1 (3-axis audit). */
    @Test
    fun `the outline stands out on the surface and on the page of both themes`() {
        for (scheme in listOf(LightColors, DarkColors)) {
            assertThat(contrast(scheme.outline, scheme.surface)).isAtLeast(NON_TEXT)
            assertThat(contrast(scheme.outline, scheme.background)).isAtLeast(NON_TEXT)
        }
    }

    /** The witness: without it the tests above would pass as well if [contrast] returned a constant. */
    @Test
    fun `the contrast computation can say no`() {
        assertThat(contrast(Color(0xFF9E9E9E), Color(0xFFBDBDBD))).isLessThan(NON_TEXT)
    }

    /** The text roles and the backgrounds they are drawn on: pages, cards, menus and dialogs. */
    private fun textPairs(scheme: ColorScheme): List<Pair<String, Pair<Color, Color>>> = listOf(
        "onBackground" to (scheme.onBackground to scheme.background),
        "onSurface" to (scheme.onSurface to scheme.surface),
        "onSurface on a dialog" to (scheme.onSurface to scheme.surfaceContainerHigh),
        "onSurfaceVariant" to (scheme.onSurfaceVariant to scheme.surface),
        "onSurfaceVariant on a dialog" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
        "onSurfaceVariant on the highest container" to (scheme.onSurfaceVariant to scheme.surfaceContainerHighest),
        "primary" to (scheme.primary to scheme.surface),
        "primary on the page" to (scheme.primary to scheme.background),
        "primary on a dialog" to (scheme.primary to scheme.surfaceContainerHigh),
        "onPrimary" to (scheme.onPrimary to scheme.primary),
        "onPrimaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "onSecondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "onTertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
        "onErrorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
    )

    private fun assertWithRole(role: String, ratio: Float) {
        assertWithMessage(role).that(ratio).isAtLeast(TEXT)
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    private companion object {
        /** WCAG 2.1, 1.4.3: text of the usual size. */
        const val TEXT = 4.5f

        /** WCAG 2.1, 1.4.11: what is not text — a button against its background, an icon. */
        const val NON_TEXT = 3.0f
    }
}
