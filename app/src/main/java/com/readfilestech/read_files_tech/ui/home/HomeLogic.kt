package com.readfilestech.read_files_tech.ui.home

import com.readfilestech.read_files_tech.data.storage.CommonFolders
import com.readfilestech.read_files_tech.data.storage.StorageSpace
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The home's three tabs, in the order of its bar. */
enum class HomeTab { HOME, TOOLS, EXPLORER }

/** The quick accesses of the home, which the screen names and draws. */
enum class QuickAccessKind { CAMERA, SCREENSHOTS, DOCUMENTS, VIDEOS, DOWNLOADS, APKS, GALLERY }

/** A folder opened in the explorer, under its own filter for videos, APKs and pictures. */
data class QuickAccess(val kind: QuickAccessKind, val dir: File, val extensions: Set<String>? = null)

/** How the home dates a recent file. */
sealed interface RecentDate {
    data object Today : RecentDate

    data object Yesterday : RecentDate

    data class DaysAgo(val days: Int) : RecentDate

    data class On(val date: LocalDate) : RecentDate
}

/** How full the primary volume is: the bar turns orange, then red, as in the 2.15.x. */
enum class SpaceLevel { NORMAL, HIGH, CRITICAL }

object HomeLogic {
    private val VIDEOS = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp")
    private val PICTURES = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
    private const val WEEK = 7
    private const val HIGH = 0.75
    private const val CRITICAL = 0.9

    /**
     * The quick accesses of the 2.15.x (`HomeScreen`), relative to the user's primary volume — it wrote
     * `/storage/emulated/0`, another user's storage in a work profile — and only those this phone has
     * (inventory F30, F33): screenshots where this phone keeps them.
     */
    fun quickAccess(primary: File): List<QuickAccess> {
        val screenshots = CommonFolders.screenshots(primary)
        return listOfNotNull(
            QuickAccess(QuickAccessKind.CAMERA, File(primary, "DCIM/Camera")),
            screenshots?.let { QuickAccess(QuickAccessKind.SCREENSHOTS, it) },
            QuickAccess(QuickAccessKind.DOCUMENTS, File(primary, "Documents")),
            QuickAccess(QuickAccessKind.VIDEOS, File(primary, "Movies"), VIDEOS),
            QuickAccess(QuickAccessKind.DOWNLOADS, File(primary, "Download")),
            QuickAccess(QuickAccessKind.APKS, File(primary, "Download"), setOf("apk")),
            QuickAccess(QuickAccessKind.GALLERY, File(primary, "DCIM"), PICTURES),
        ).filter { it.dir.isDirectory }
    }

    /**
     * Calendar days, not 24-hour windows: the 2.15.x said "today" of yesterday 23:00 seen at 08:00, and
     * "-1 days ago" of a date ahead of the clock (inventory F28) — a future date is shown as a date.
     */
    fun recentDate(opened: LocalDate, today: LocalDate): RecentDate {
        val days = ChronoUnit.DAYS.between(opened, today)
        return when {
            days == 0L -> RecentDate.Today
            days == 1L -> RecentDate.Yesterday
            days in 2 until WEEK -> RecentDate.DaysAgo(days.toInt())
            else -> RecentDate.On(opened)
        }
    }

    fun spaceLevel(space: StorageSpace): SpaceLevel {
        val ratio = space.used.toDouble() / space.total
        return when {
            ratio > CRITICAL -> SpaceLevel.CRITICAL
            ratio > HIGH -> SpaceLevel.HIGH
            else -> SpaceLevel.NORMAL
        }
    }
}
