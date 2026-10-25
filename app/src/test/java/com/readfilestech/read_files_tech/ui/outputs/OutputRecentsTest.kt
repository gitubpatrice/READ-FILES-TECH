package com.readfilestech.read_files_tech.ui.outputs

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.recents.RecentFiles
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OutputRecentsTest {
    @TempDir
    lateinit var tmp: File

    private val recents = RecentFiles(FlutterPreferences(InMemorySharedPreferences())) { true }

    /** Shown again (a rotation, a second look), an output is not opened again: its date stays. */
    @Test
    fun `an output is recorded once per run`() {
        val scan = File(tmp, "scan.pdf").apply { writeText("pdf") }
        val outputs = OutputRecents(recents)
        outputs.register(scan.path)
        val first = recents.load().single().lastOpened
        Thread.sleep(5)
        outputs.register(scan.path)
        assertThat(recents.load().single().lastOpened).isEqualTo(first)
    }
}
