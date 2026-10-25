package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * Material's type scale, its titles of the top bars (`titleLarge`, 22 sp) semi-bold as in Agenda Tech and App
 * Manager Tech. Only the weight changes: Material's sizes, line heights and spacing stay.
 */
internal val AppTypography: Typography = Typography().let { base ->
    base.copy(titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold))
}
