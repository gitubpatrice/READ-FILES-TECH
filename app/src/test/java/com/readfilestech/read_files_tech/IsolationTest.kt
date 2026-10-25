package com.readfilestech.read_files_tech

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * docs D-008: unless built with `-Prft.replaceFlutter=true`, the port carries the `.next` suffix, so
 * it installs NEXT TO the published app and cannot open its sandbox — the vault included.
 */
class IsolationTest {
    @Test
    fun `the applicationId is the Flutter one only when the build replaces it`() {
        val expected = if (BuildConfig.REPLACES_FLUTTER_APP) {
            BuildConfig.FLUTTER_APPLICATION_ID
        } else {
            BuildConfig.FLUTTER_APPLICATION_ID + ".next"
        }
        assertThat(BuildConfig.APPLICATION_ID).isEqualTo(expected)
    }

    @Test
    fun `the Flutter applicationId is the published one`() {
        assertThat(BuildConfig.FLUTTER_APPLICATION_ID).isEqualTo("com.readfilestech.read_files_tech")
    }
}
