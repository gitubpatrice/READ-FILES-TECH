package com.readfilestech.read_files_tech.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.readfilestech.read_files_tech.data.history.HistoryCopy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The date and time of an editor's history copies, in the app's language: one formatter for both editors. */
@Composable
fun rememberHistoryDateFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM).withLocale(locale)
    }
}

/** When [copy] was WRITTEN: its name orders the copies, and runs ahead after a clock change (R21). */
fun DateTimeFormatter.date(copy: HistoryCopy): String =
    format(Instant.ofEpochMilli(copy.writtenAtMs).atZone(ZoneId.systemDefault()))
