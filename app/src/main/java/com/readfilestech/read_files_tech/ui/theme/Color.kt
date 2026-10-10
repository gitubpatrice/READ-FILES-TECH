package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The two themes of the Flutter app (2.15.x, lib/main.dart), reproduced role by role so the update
// changes nothing the user sees.

/**
 * Light: Flutter's `ColorScheme.fromSeed(seedColor: 0xFF1565C0)`. Values computed with the library
 * Flutter uses for it (material_color_utilities 0.13.0, `SchemeTonalSpot`, contrast 0.0 — what Flutter
 * 3.47.2 calls), not approximated: Compose has no public seed-to-scheme function.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF405F90),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF274777),
    inversePrimary = Color(0xFFA9C7FF),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF3E4758),
    tertiary = Color(0xFF6F5575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF9D8FD),
    onTertiaryContainer = Color(0xFF563E5C),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceTint = Color(0xFF405F90),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD9D9E0),
)

// Dark: the "GitHub dark" palette of `_githubDarkTheme()`.
private val GithubBackground = Color(0xFF0D1117)
private val GithubSurface = Color(0xFF161B22)
private val GithubSurface2 = Color(0xFF21262D)
private val GithubBorder = Color(0xFF30363D)
private val GithubText = Color(0xFFE6EDF3)
private val GithubBlue = Color(0xFF58A6FF)
private val GithubBlueContainer = Color(0xFF1F6FEB)
private val GithubError = Color(0xFFF85149)
// Not carried yet: `textSec` 0xFF8B949E, the secondary text colour of a few Flutter screens. It comes
// back with the first screen that uses it.

/**
 * The roles the Flutter theme left unset take what Flutter's `ColorScheme.dark` falls back to (read in
 * Flutter 3.47.2: tertiary → secondary, surface containers → surface, onSurfaceVariant → onSurface…),
 * NOT Compose's own defaults, which are other colours. Two deliberate equivalences:
 * - `background` is the Flutter `scaffoldBackgroundColor`, because a Compose Scaffold paints
 *   `background` where a Flutter Scaffold painted that colour;
 * - `outlineVariant` is the border colour, because Compose draws dividers with it, and the Flutter
 *   theme gave its dividers that colour (`dividerTheme`).
 */
internal val DarkColors = darkColorScheme(
    primary = GithubBlue,
    onPrimary = Color.White,
    primaryContainer = GithubBlueContainer,
    onPrimaryContainer = Color.White,
    inversePrimary = Color.White,
    secondary = GithubBlue,
    onSecondary = Color.White,
    secondaryContainer = GithubBlue,
    onSecondaryContainer = Color.White,
    tertiary = GithubBlue,
    onTertiary = Color.White,
    tertiaryContainer = GithubBlue,
    onTertiaryContainer = Color.White,
    background = GithubBackground,
    onBackground = GithubText,
    surface = GithubSurface,
    onSurface = GithubText,
    surfaceVariant = GithubSurface,
    onSurfaceVariant = GithubText,
    surfaceTint = GithubBlue,
    inverseSurface = GithubText,
    inverseOnSurface = GithubSurface,
    error = GithubError,
    onError = Color.Black,
    errorContainer = GithubError,
    onErrorContainer = Color.Black,
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
