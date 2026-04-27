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

        // Listing natif : Dart's Directory.list() peut être filtré par
        // Samsung DefEx (APKs invisibles dans /sdcard malgré MANAGE_EXTERNAL_STORAGE).
        // File.listFiles() côté Kotlin retourne tous les fichiers réels.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.readfilestech/list_dir")
            .setMethodCallHandler { call, result ->
                if (call.method == "listDir") {
                    val path = call.argument<String>("path")
                    if (path == null) {
                        result.error("NO_PATH", "path manquant", null); return@setMethodCallHandler
                    }
                    try {
                        val dir = File(path)
                        if (!dir.exists() || !dir.isDirectory) {
                            result.error("NOT_DIR", "Pas un dossier : $path", null)
                            return@setMethodCallHandler
                        }
                        val files = dir.listFiles() ?: emptyArray()
                        val out = files.map { f ->
                            mapOf(
                                "path"      to f.absolutePath,
                                "name"      to f.name,
                                "isDir"     to f.isDirectory,
                                "size"      to (if (f.isFile) f.length() else 0L),
                                "modified"  to f.lastModified()
                            )
                        }
                        result.success(out)
                    } catch (e: Exception) {
                        result.error("LIST_ERROR", e.message, null)
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
