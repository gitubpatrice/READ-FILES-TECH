package com.readfilestech.read_files_tech

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * docs D-012: the launcher and the quick-settings panel remember components by their FULL class name.
 * Moving MainActivity to another package, or renaming it, would make the pinned icon disappear at the
 * update from the Flutter app — and nothing else would notice.
 */
class MainActivityNameTest {
    @Test
    fun `the launcher activity keeps the full name of the Flutter app`() {
        assertThat(MainActivity::class.java.name).isEqualTo("com.readfilestech.read_files_tech.MainActivity")
    }

    @Test
    fun `the R class lives in the namespace of the Flutter app`() {
        assertThat(R::class.java.packageName).isEqualTo("com.readfilestech.read_files_tech")
    }
}
