package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The checkerboard of the Files Tech logo, on the icon of every app of the portfolio, this one's included: two
 * tones only, read on the artwork (Notes Tech's SemanticColors) and on this app's launcher icon.
 */
object Damier {
    /**
     * The blue: the app's signature on its filled buttons, its floating buttons and its confirmations, in both
     * themes. White on it: 6.0:1 (BrandColorsTest).
     */
    val Blue = Color(0xFF0B60C5)

    /**
     * The red of the logo. NOT the colour of danger: an irreversible action says it with [DangerRed], the red
     * the 2.15.x and the other apps give it, and the logo's red never stands for a warning.
     */
    val Red = Color(0xFFCA331F)

    /** Text and icons on [Blue]. */
    val OnBlue = Color.White
}

/**
 * Every filled button of the app that destroys nothing — "Open a file", "Unlock", "Check for updates"…: white on
 * the checkerboard's blue in both themes (Patrice for Notes Tech, 2026-10-10, and for this app, 2026-10-25). The
 * label 6.0:1; the button against the page 5.9:1 light, 3.15:1 dark (WCAG 1.4.11 asks 3); on the dark surface
 * of a card or a dialog 2.9:1, where the white label, at 6:1, is what identifies it. Disabled, Material's grey.
 */
@Composable
fun brandButtonColors(): ButtonColors = ButtonDefaults.buttonColors(containerColor = Damier.Blue, contentColor = Damier.OnBlue)
