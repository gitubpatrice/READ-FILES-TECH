package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Below this luminance a surface counts as dark: 0.35 rather than the arithmetic middle, where perception turns
 * (Notes Tech's SemanticColors, whose threshold this is).
 */
private const val DARK_SURFACE_THRESHOLD = 0.35f

/**
 * Whether [scheme]'s surface is dark, measured on the surface itself rather than read from the theme's name: a
 * colour chosen here must contrast with what it is drawn on, whatever named it.
 */
internal fun isDarkSurface(scheme: ColorScheme): Boolean = scheme.surface.luminance() < DARK_SURFACE_THRESHOLD

/**
 * The red of a destructive action written as TEXT or drawn as an outline — a dialog's "Delete", a menu's "Delete
 * for good", the panic row: [DangerRed] on a light surface (5.5:1 on the page, 5.1:1 on a dialog), the dark
 * theme's error red on a dark one. [DangerRed] falls there to 3.1:1 on a dialog (#161B22), under the 4.5:1 of a
 * text; #F85149 reads 5.2:1. A FILLED red (the error message) keeps [DangerRed]: white on it is 5.6:1.
 */
fun dangerContentOn(scheme: ColorScheme): Color = if (isDarkSurface(scheme)) scheme.error else DangerRed

/** [dangerContentOn] the current theme. */
val dangerContent: Color
    @Composable @ReadOnlyComposable
    get() = dangerContentOn(MaterialTheme.colorScheme)

/** A colour in two tones: [light] drawn on a light surface, [dark] on a dark one ([isDarkSurface]). */
class TwoTone(private val light: Color, private val dark: Color) {
    fun on(scheme: ColorScheme): Color = if (isDarkSurface(scheme)) dark else light
}

/**
 * The tools' colours of the 2.15.3 (`_ToolsTab`, one per tool), in two tones: Material 700 on the light card,
 * 300 on the dark one, where 700 sank. Each draws an ICON only, beside its title in `onSurface`: the colour
 * identifies, the text says. Icon on its 14 % tint, measured (light / dark): pink 4.6 / 4.7, blue-grey 5.7 / 5.3,
 * green 3.4 / 6.5, indigo 7.0 / 4.2, teal 4.3 / 5.6, orange 3.5 / 7.4, purple 6.3 / 4.1, cyan 3.7 / 7.1, deep
 * orange 3.2 / 5.9, deep purple 7.1 / 3.9, blue 3.8 / 6.0, amber 3.5 / 8.7, red 3.9 / 4.8, brown 7.3 / 4.3.
 * Amber, orange and cyan are darker than their Material 700 in light (#B26A00, #C25E00, Cyan 800): at 700 they
 * stayed under the 3:1 a graphic needs (1.8, 2.3, 2.9), and Amber 900 still reads 2.4.
 */
object ToolColors {
    val Pink = TwoTone(Color(0xFFC2185B), Color(0xFFF06292))
    val BlueGrey = TwoTone(Color(0xFF455A64), Color(0xFF90A4AE))
    val Green = TwoTone(Color(0xFF388E3C), Color(0xFF81C784))
    val Indigo = TwoTone(Color(0xFF303F9F), Color(0xFF7986CB))
    val Teal = TwoTone(Color(0xFF00796B), Color(0xFF4DB6AC))
    val Orange = TwoTone(Color(0xFFC25E00), Color(0xFFFFB74D))
    val Purple = TwoTone(Color(0xFF7B1FA2), Color(0xFFBA68C8))
    val Cyan = TwoTone(Color(0xFF00838F), Color(0xFF4DD0E1))
    val DeepOrange = TwoTone(Color(0xFFE64A19), Color(0xFFFF8A65))
    val DeepPurple = TwoTone(Color(0xFF512DA8), Color(0xFF9575CD))
    val Blue = TwoTone(Color(0xFF1976D2), Color(0xFF64B5F6))
    val Amber = TwoTone(Color(0xFFB26A00), Color(0xFFFFD54F))
    val Red = TwoTone(Color(0xFFD32F2F), Color(0xFFE57373))
    val Brown = TwoTone(Color(0xFF5D4037), Color(0xFFA1887F))
}

/**
 * The pastilles of About's privacy card (Notes Tech's `CouleursDeBadge`, Agenda Tech's shield): fixed in both
 * themes, each tinting an icon, a 12 % background and a 30 % border — never the text, written in `onSurface`
 * (14:1 light, 12:1 dark at least). The icons, decorative beside their words, measure 2 to 6.5:1.
 */
object BadgeColors {
    /** Also the shield of the card. */
    val Green = Color(0xFF43A047)
    val Blue = Color(0xFF1976D2)
    val Orange = Color(0xFFFF7043)
    val Purple = Color(0xFF7B1FA2)
    val Teal = Color(0xFF00897B)
    val Slate = Color(0xFF546E7A)
}
