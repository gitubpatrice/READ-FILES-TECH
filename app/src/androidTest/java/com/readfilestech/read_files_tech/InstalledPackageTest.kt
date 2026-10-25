package com.readfilestech.read_files_tech

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the INSTALLED package exposes to the system, read back from the device's package manager rather
 * than from the build files — the same view the launcher and the permission controller have.
 *
 * ⚠️ Run on a device that does not carry the user's real Read Files Tech: connectedAndroidTest
 * uninstalls the app at the end. The `.next` suffix keeps the real app out of reach of a default build.
 */
class InstalledPackageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager

    @Test
    fun launcherResolvesTheComponentNameOfTheFlutterApp() {
        val launch = packageManager.getLaunchIntentForPackage(context.packageName)
        assertThat(launch).isNotNull()
        // docs D-012: the class name, not the package, is what a pinned icon points to.
        assertThat(launch!!.component!!.className).isEqualTo("com.readfilestech.read_files_tech.MainActivity")
    }

    @Test
    fun noNetworkPermissionIsRequested() {
        val info = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertThat(requested).containsNoneOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
    }

    @Test
    fun androidBackupIsDisabled() {
        val flags = context.applicationInfo.flags
        assertThat(flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    }
}
