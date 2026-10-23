package com.readfilestech.read_files_tech.ui.common

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * `FLAG_SECURE` on the activity's window while this is in the composition: no screenshot, no screen
 * recording, a blank thumbnail among the recent apps — the 2.15.3's `SecureWindow`, for every screen of the
 * vault, locked ones included (the password typed must not show either), and the viewers of its files.
 */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        SecureWindows.acquire(window)
        onDispose { SecureWindows.release(window) }
    }
}

/**
 * The flag, COUNTED per window: two secure screens composed at once — the vault and the viewer of one of
 * its files, while one slides over the other — keep it until both have left. The 2.15.x counted too, and
 * an unbalanced `enable()` once left the whole app unable to take a screenshot: here each acquisition is
 * an effect's, released when it leaves. Main thread only, as compositions are.
 */
internal object SecureWindows {
    private val counts = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        val count = (counts[window] ?: 0) + 1
        counts[window] = count
        if (count == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val count = (counts[window] ?: return) - 1
        if (count > 0) {
            counts[window] = count
        } else {
            counts.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
