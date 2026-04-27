package com.readfilestech.read_files_tech

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.readfilestech/storage")
            .setMethodCallHandler { call, result ->
                if (call.method == "getStorageInfo") {
                    try {
                        val stat = StatFs(Environment.getExternalStorageDirectory().path)
                        val total = stat.blockCountLong * stat.blockSizeLong
                        val free  = stat.availableBlocksLong * stat.blockSizeLong
                        result.success(mapOf("total" to total, "free" to free))
                    } catch (e: Exception) {
                        result.error("STORAGE_ERROR", e.message, null)
                    }
                } else {
                    result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.readfilestech/open_file")
            .setMethodCallHandler { call, result ->
                if (call.method == "openFile") {
                    val path = call.argument<String>("path")
                    val mime = call.argument<String>("mime") ?: "*/*"
                    if (path == null) { result.error("NO_PATH", "path manquant", null); return@setMethodCallHandler }
                    val chooser = call.argument<Boolean>("chooser") ?: false
                    try {
                        val file = File(path)
                        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                        } else {
                            Uri.fromFile(file)
                        }
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val intent = if (chooser)
                            Intent.createChooser(view, "Ouvrir avec").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        else view
                        startActivity(intent)
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("OPEN_ERROR", e.message, null)
                    }
                } else {
                    result.notImplemented()
                }
            }
    }
}
