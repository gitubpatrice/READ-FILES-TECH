package com.readfilestech.read_files_tech

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Shortcuts

/**
 * The three quick-settings tiles (`QuickTiles.kt` of the 2.15.x). Each opens MainActivity with the
 * `shortcut` extra of its screen.
 *
 * ⚠️ Their FULL class names are the 2.15.x ones (`com.readfilestech.read_files_tech.ScannerTileService`…):
 * the quick-settings panel remembers a tile by component, so a renamed class would remove the tile the
 * user placed (docs D-012). Guarded by the manifest shape check (exported, BIND_QUICK_SETTINGS_TILE).
 */
private fun TileService.open(feature: Feature) {
    val intent = Intent(applicationContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(Shortcuts.EXTRA, Shortcuts.valueOf(feature))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Android 14+: only through a PendingIntent. Immutable, explicit, ours.
        val pending = PendingIntent.getActivity(
            applicationContext,
            feature.ordinal,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pending)
    } else {
        @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
        startActivityAndCollapse(intent)
    }
}

class ScannerTileService : TileService() {
    override fun onClick() {
        super.onClick()
        open(Feature.SCANNER)
    }
}

class OcrTileService : TileService() {
    override fun onClick() {
        super.onClick()
        open(Feature.OCR)
    }
}

class VaultTileService : TileService() {
    override fun onClick() {
        super.onClick()
        open(Feature.VAULT)
    }
}
