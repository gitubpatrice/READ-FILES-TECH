package com.readfilestech.read_files_tech.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.readfilestech.read_files_tech.data.storage.StorageModel

/** How to ask for storage access on this device: a dialog (Android ≤ 10) or the settings (11+). */
class StorageSettings(val model: StorageModel, val request: () -> Unit)

val LocalStorageSettings = staticCompositionLocalOf<StorageSettings> { error("AppRoot provides the storage settings") }
