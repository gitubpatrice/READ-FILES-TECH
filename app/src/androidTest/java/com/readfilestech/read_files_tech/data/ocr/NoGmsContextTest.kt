package com.readfilestech.read_files_tech.data.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfService
import org.junit.Test

/**
 * ML Kit's usage reports go through a binding to Google Play services (Claude review R25): the OCR starts ML
 * Kit on a context that refuses it. Checked on the device, where Android's own ContextWrapper and ML Kit run.
 */
class NoGmsContextTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    /** Its provider removed from the manifest, ML Kit starts with the OCR — on the context that refuses. */
    @Test
    fun mlKitIsStartedOnTheContextThatRefusesPlayServices() {
        OcrEngine(app).close()
        val context = MlKitContext.getInstance().applicationContext
        assertThat(context).isInstanceOf(NoGmsContext::class.java)
        assertThat(context.applicationContext).isSameInstanceAs(context)
    }

    @Test
    fun aBindingToPlayServicesIsRefused() {
        val wrapped = NoGmsContext(app)
        val telemetry = Intent("com.google.android.gms.common.telemetry.service.START").setPackage(NoGmsContext.PLAY_SERVICES)
        assertThat(wrapped.bindService(telemetry, connection, Context.BIND_AUTO_CREATE)).isFalse()
        val service = ComponentName(NoGmsContext.PLAY_SERVICES, "com.google.android.gms.chimera.PersistentApiService")
        val byComponent = Intent().setComponent(service)
        assertThat(wrapped.bindService(byComponent, connection, Context.BIND_AUTO_CREATE)).isFalse()
    }

    /** Anything else goes through as from the application: here the app's own PDF service. */
    @Test
    fun anotherBindingGoesThrough() {
        val wrapped = NoGmsContext(app)
        assertThat(wrapped.bindService(Intent(app, PdfService::class.java), connection, Context.BIND_AUTO_CREATE)).isTrue()
        wrapped.unbindService(connection)
    }
}
