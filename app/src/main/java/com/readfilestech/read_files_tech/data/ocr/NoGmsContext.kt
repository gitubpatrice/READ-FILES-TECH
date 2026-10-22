package com.readfilestech.read_files_tech.data.ocr

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * The application as ML Kit sees it: the app's own in everything, but a binding to Google Play services is
 * refused. ML Kit's vision libraries report each use — the method, its status, its times; never the image
 * nor the text — through Play services' client telemetry: a binding to `com.google.android.gms`, which uploads
 * it. That needs no permission of ours and declares no component: the manifest's removals and the CI's guards
 * cannot see it (Claude review R25; measured on a Galaxy S9 and an emulator, the published 2.15.3 included:
 * `telemetry.service.START` bound right after each recognition). The recognition itself runs on the model
 * bundled in the APK and binds nothing.
 *
 * Handed to ML Kit at its initialisation ([OcrEngine]), its own provider removed from the manifest: it keeps
 * `getApplicationContext()`, so it must answer with itself.
 */
internal class NoGmsContext(application: Context) : ContextWrapper(application) {
    override fun getApplicationContext(): Context = this

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
        !toPlayServices(service) && super.bindService(service, conn, flags)

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun bindService(service: Intent, flags: Int, executor: Executor, conn: ServiceConnection): Boolean =
        !toPlayServices(service) && super.bindService(service, flags, executor, conn)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun bindService(service: Intent, conn: ServiceConnection, flags: BindServiceFlags): Boolean =
        !toPlayServices(service) && super.bindService(service, conn, flags)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun bindService(service: Intent, flags: BindServiceFlags, executor: Executor, conn: ServiceConnection): Boolean =
        !toPlayServices(service) && super.bindService(service, flags, executor, conn)

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun bindIsolatedService(
        service: Intent,
        flags: Int,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection,
    ): Boolean = !toPlayServices(service) && super.bindIsolatedService(service, flags, instanceName, executor, conn)

    override fun startService(service: Intent) = if (toPlayServices(service)) null else super.startService(service)

    internal companion object {
        const val PLAY_SERVICES = "com.google.android.gms"

        /** Whether [intent] goes to Google Play services, by its package or its component. */
        fun toPlayServices(intent: Intent): Boolean = (intent.`package` ?: intent.component?.packageName) == PLAY_SERVICES
    }
}
