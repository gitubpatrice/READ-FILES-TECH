package com.readfilestech.read_files_tech

import android.app.Application
import com.readfilestech.read_files_tech.startup.AppProcess
import com.readfilestech.read_files_tech.startup.StartupCleanup
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Hilt's entry point for the whole process. */
@HiltAndroidApp
class RftApplication : Application() {
    // Lazy: a worker process (`:pdf`, docs D-029) never builds it, nor the vault and preferences it needs.
    @Inject
    lateinit var startupCleanup: Lazy<StartupCleanup>

    override fun onCreate() {
        super.onCreate()
        // Before any activity, provider call or service touches the cache: see StartupCleanup. In the
        // main process only (AppProcess).
        if (AppProcess.isMain(AppProcess.currentName(), packageName)) startupCleanup.get().runOnce()
    }
}
