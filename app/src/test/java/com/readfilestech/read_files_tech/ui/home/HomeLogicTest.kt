package com.readfilestech.read_files_tech.ui.home

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.storage.StorageSpace
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class HomeLogicTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `quick accesses are the folders this phone has`() {
        listOf("DCIM/Camera", "Pictures/Screenshots", "Download").forEach { File(tmp, it).mkdirs() }
        val shown = HomeLogic.quickAccess(tmp)
        assertThat(shown.map { it.kind }).containsExactly(
            QuickAccessKind.CAMERA,
            QuickAccessKind.SCREENSHOTS,
            QuickAccessKind.DOWNLOADS,
            QuickAccessKind.APKS,
            QuickAccessKind.GALLERY,
        ).inOrder()
        // The Pixel's folder when it is the only one; Samsung's DCIM one first otherwise (inventory F30).
        assertThat(shown.single { it.kind == QuickAccessKind.SCREENSHOTS }.dir).isEqualTo(File(tmp, "Pictures/Screenshots"))
        File(tmp, "DCIM/Screenshots").mkdirs()
        assertThat(
            HomeLogic.quickAccess(tmp).single { it.kind == QuickAccessKind.SCREENSHOTS }.dir
        ).isEqualTo(File(tmp, "DCIM/Screenshots"))
        assertThat(shown.single { it.kind == QuickAccessKind.APKS }.extensions).containsExactly("apk")
    }

    /** Inventory F28: 24-hour windows called yesterday evening "today", and a future date "-1 days ago". */
    @Test
    fun `recent files are dated by calendar days`() {
        val today = LocalDate.of(2026, 10, 12)
        assertThat(HomeLogic.recentDate(today, today)).isEqualTo(RecentDate.Today)
        assertThat(HomeLogic.recentDate(today.minusDays(1), today)).isEqualTo(RecentDate.Yesterday)
        assertThat(HomeLogic.recentDate(today.minusDays(6), today)).isEqualTo(RecentDate.DaysAgo(6))
        assertThat(HomeLogic.recentDate(today.minusDays(7), today)).isEqualTo(RecentDate.On(today.minusDays(7)))
        assertThat(HomeLogic.recentDate(today.plusDays(1), today)).isEqualTo(RecentDate.On(today.plusDays(1)))
    }

    @Test
    fun `the bar turns orange past three quarters, red past nine tenths`() {
        assertThat(HomeLogic.spaceLevel(StorageSpace(total = 100, free = 25))).isEqualTo(SpaceLevel.NORMAL)
        assertThat(HomeLogic.spaceLevel(StorageSpace(total = 100, free = 24))).isEqualTo(SpaceLevel.HIGH)
        assertThat(HomeLogic.spaceLevel(StorageSpace(total = 100, free = 9))).isEqualTo(SpaceLevel.CRITICAL)
    }
}
