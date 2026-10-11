package com.readfilestech.read_files_tech

import android.app.Application
import com.readfilestech.read_files_tech.startup.StartupCleanup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Hilt's entry point for the whole process. */
@HiltAndroidApp
class RftApplication : Application() {
    @Inject
    lateinit var startupCleanup: StartupCleanup

    override fun onCreate() {
        super.onCreate()
        // In the background: the first start may have a large cache to empty.
        startupCleanup.start()
    }
}
