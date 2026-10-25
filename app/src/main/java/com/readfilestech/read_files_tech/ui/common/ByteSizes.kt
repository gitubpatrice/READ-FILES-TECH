package com.readfilestech.read_files_tech.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.readfilestech.read_files_tech.R
import java.util.Locale

/** The unit names of a language: "B, KB, MB, GB" in English, "o, Ko, Mo, Go" in French. */
data class ByteUnits(val bytes: String, val kilo: String, val mega: String, val giga: String)

/**
 * A size as the 2.15.x showed it (`FormatUtils.bytesStorage`, binary units: KB with one decimal, MB with
 * none, GB with one) — but in the user's language: the 2.15.x wrote "1.5 KB" on a French screen.
 */
object ByteSizes {
    private const val K = 1024L
    private const val M = K * K
    private const val G = M * K

    fun format(bytes: Long, units: ByteUnits, locale: Locale): String = when {
        bytes <= 0 -> "0 ${units.bytes}"
        bytes < K -> "$bytes ${units.bytes}"
        bytes < M -> "${String.format(locale, "%.1f", bytes.toDouble() / K)} ${units.kilo}"
        bytes < G -> "${String.format(locale, "%.0f", bytes.toDouble() / M)} ${units.mega}"
        else -> "${String.format(locale, "%.1f", bytes.toDouble() / G)} ${units.giga}"
    }
}

/** [ByteSizes.format] in the language of the screen. */
@Composable
fun formatBytes(bytes: Long): String {
    val units = ByteUnits(
        bytes = stringResource(R.string.unit_bytes),
        kilo = stringResource(R.string.unit_kilobytes),
        mega = stringResource(R.string.unit_megabytes),
        giga = stringResource(R.string.unit_gigabytes),
    )
    return ByteSizes.format(bytes, units, LocalConfiguration.current.locales[0])
}
