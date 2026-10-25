package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The two themes of the Files Tech portfolio (Agenda Tech, App Manager Tech, SMS Tech), in harmony with the
// checkerboard of the logo (Damier). The 3.0 departs on purpose from the 2.15.x's palettes, which the port first
// reproduced role by role: Patrice asked for the app to look like the other apps (2026-10-25).
// Contrasts (WCAG 2.x, text 4.5:1) measured for each pair of text and background written below; the brand pairs
// are checked by BrandColorsTest.

/** The portfolio's blue, the light theme's primary (Agenda Tech, App Manager Tech). White on it: 6.3:1. */
private val PortfolioBlue = Color(0xFF2460AB)

/**
 * Pale blue-grey for the light theme's container surfaces — menus, dialogs, sheets, the bottom bar — as Agenda
 * Tech and App Manager Tech pin them: left unset, Material 3 derives a lavender wash that matches nothing of the
 * brand. A touch off the page, so a menu still reads as a raised panel.
 */
private val LightContainer = Color(0xFFF0F5FB)

/**
 * Light: the palette of Agenda Tech and App Manager Tech for the primary, the page and the containers; the roles
 * they share with the 2.15.x's (secondary, tertiary, error, surface variant, outlines) kept as they were.
 * Primary on the page 6.2:1, on a container 5.7:1; onSurface 16.8:1 and 15.7:1; onSurfaceVariant 9.1:1 and
 * 8.5:1; onPrimaryContainer on its container 13.4:1.
 */
internal val LightColors = lightColorScheme(
    primary = PortfolioBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001A40),
    inversePrimary = Color(0xFFA9C7FF),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF3E4758),
    tertiary = Color(0xFF6F5575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF9D8FD),
    onTertiaryContainer = Color(0xFF563E5C),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceTint = PortfolioBlue,
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFDFCFF),
    surfaceContainer = LightContainer,
    surfaceContainerHigh = LightContainer,
    surfaceContainerHighest = LightContainer,
    surfaceContainerLow = LightContainer,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD9D9E0),
)

// Dark: the "GitHub dark" palette of the 2.15.x, which Agenda Tech and App Manager Tech took from it.
private val GithubBackground = Color(0xFF0D1117)
private val GithubSurface = Color(0xFF161B22)
private val GithubSurface2 = Color(0xFF21262D)
private val GithubBorder = Color(0xFF30363D)
private val GithubText = Color(0xFFE6EDF3)

/** The text on the secondary and tertiary containers (Agenda Tech): 9.9:1 on [GithubSurface2]. */
private val GithubTextMuted = Color(0xFFC9D1D9)

/** `textSec` of the 2.15.x, the secondary text: 5.6:1 on the surface, 6.2:1 on the page, 4.95:1 on [GithubSurface2]. */
private val GithubTextSecondary = Color(0xFF8B949E)
private val GithubBlue = Color(0xFF58A6FF)
private val GithubBlueContainer = Color(0xFF1F6FEB)
private val GithubError = Color(0xFFF85149)

/**
 * The roles the 2.15.x's theme left unset first took what Flutter's `ColorScheme.dark` falls back to; the 3.0
 * gives them Agenda Tech's values instead. On the blue of [GithubBlue] the text is the page's near-black, 7.5:1 —
 * white, the 2.15.x's, read 2.5:1 there. Two deliberate equivalences:
 * - `background` is the Flutter `scaffoldBackgroundColor`, because a Compose Scaffold paints
 *   `background` where a Flutter Scaffold painted that colour;
 * - `outlineVariant` is the border colour, because Compose draws dividers with it, and the Flutter
 *   theme gave its dividers that colour (`dividerTheme`).
 */
internal val DarkColors = darkColorScheme(
    primary = GithubBlue,
    onPrimary = GithubBackground,
    primaryContainer = GithubBlueContainer,
    // 4.6:1: the version's pill in About.
    onPrimaryContainer = Color.White,
    inversePrimary = GithubBlueContainer,
    secondary = GithubBlue,
    onSecondary = GithubBackground,
    secondaryContainer = GithubSurface2,
    onSecondaryContainer = GithubTextMuted,
    tertiary = GithubBlue,
    onTertiary = GithubBackground,
    tertiaryContainer = GithubSurface2,
    onTertiaryContainer = GithubTextMuted,
    background = GithubBackground,
    onBackground = GithubText,
    surface = GithubSurface,
    onSurface = GithubText,
    surfaceVariant = GithubSurface2,
    onSurfaceVariant = GithubTextSecondary,
    surfaceTint = GithubBlue,
    inverseSurface = GithubText,
    inverseOnSurface = GithubSurface,
    error = GithubError,
    onError = Color.Black,
    // 7.2:1, as Agenda Tech and App Manager Tech.
    errorContainer = Color(0xFF8E1519),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = GithubBorder,
    outlineVariant = GithubBorder,
    scrim = Color.Black,
    surfaceBright = GithubSurface,
    surfaceContainer = GithubSurface,
    surfaceContainerHigh = GithubSurface,
    surfaceContainerHighest = GithubSurface2,
    surfaceContainerLow = GithubSurface,
    surfaceContainerLowest = GithubSurface,
    surfaceDim = GithubSurface,
)
