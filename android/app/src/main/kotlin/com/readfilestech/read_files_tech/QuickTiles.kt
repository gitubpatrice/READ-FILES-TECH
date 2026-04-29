package com.readfilestech.read_files_tech

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Tuiles "Quick Settings" Android (volet de notification).
 * Chaque tuile ouvre MainActivity avec un extra `shortcut` que Dart lit au boot
 * pour naviguer directement vers l'écran demandé.
 *
 * Disponibles à partir de Android N (API 24) — minSdk 21 → la fonctionnalité
 * est ignorée silencieusement sur 21-23.
 */
@RequiresApi(Build.VERSION_CODES.N)
private fun TileService.launch(shortcut: String) {
    val intent = Intent(applicationContext, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra("shortcut", shortcut)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Android 14+ : startActivityAndCollapse(PendingIntent) requis.
        val pi = android.app.PendingIntent.getActivity(
            applicationContext,
            shortcut.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pi)
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

@RequiresApi(Build.VERSION_CODES.N)
class ScannerTileService : TileService() {
    override fun onClick() { super.onClick(); launch("scanner") }
}

@RequiresApi(Build.VERSION_CODES.N)
class OcrTileService : TileService() {
    override fun onClick() { super.onClick(); launch("ocr") }
}

@RequiresApi(Build.VERSION_CODES.N)
class VaultTileService : TileService() {
    override fun onClick() { super.onClick(); launch("vault") }
}
