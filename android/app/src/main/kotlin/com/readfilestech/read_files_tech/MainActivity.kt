package com.readfilestech.read_files_tech

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {

    /// Racines autorisées pour list_dir / open_file. Le path passé par Dart
    /// est canonicalisé (suit les symlinks) puis comparé à ces racines.
    /// Empêche un path Dart compromis ou un symlink de viser /data/data/<other>.
    private val allowedRoots: List<File> by lazy {
        listOf(
            Environment.getExternalStorageDirectory().canonicalFile,
            File("/storage").canonicalFile,
            filesDir.canonicalFile,
            cacheDir.canonicalFile,
            // Le répertoire docs externe de l'app (extractions de zip, exports)
            getExternalFilesDir(null)?.canonicalFile
        ).filterNotNull()
    }

    /// Vérifie qu'un path est dans une racine autorisée. Utilise canonicalFile
    /// pour résoudre les symlinks (un symlink dans /sdcard pointant vers
    /// /data/data/<other> sera rejeté).
    private fun isAllowedPath(path: String): Boolean {
        return try {
            val canonical = File(path).canonicalFile
            allowedRoots.any { root ->
                canonical.absolutePath == root.absolutePath ||
                canonical.absolutePath.startsWith(root.absolutePath + File.separator)
            }
        } catch (_: Exception) {
            false
        }
    }

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

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.readfilestech/lifecycle")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "recreateActivity" -> {
                        runOnUiThread { recreate() }
                        result.success(null)
                    }
                    "openAllFilesAccess" -> {
                        // Ouvre EXPLICITEMENT la page "Autoriser l'accès à tous
                        // les fichiers" pour cette app (Android 11+).
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val i = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                startActivity(i)
                            } else {
                                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName"))
                                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                startActivity(i)
                            }
                            result.success(null)
                        } catch (e: Exception) {
                            try {
                                val i = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                startActivity(i)
                                result.success(null)
                            } catch (e2: Exception) {
                                result.error("OPEN_ERROR", e2.message, null)
                            }
                        }
                    }
                    else -> result.notImplemented()
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
                    if (!isAllowedPath(path)) {
                        result.error("FORBIDDEN", "Chemin hors zone autorisée", null)
                        return@setMethodCallHandler
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
                                "modified"  to f.lastModified(),
                                // Exposer si l'entrée est un symlink — Dart peut
                                // ainsi avertir avant édition / suppression.
                                "isSymlink" to try {
                                    f.canonicalPath != f.absolutePath
                                } catch (_: Exception) { false }
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
                    if (!isAllowedPath(path)) {
                        result.error("FORBIDDEN", "Chemin hors zone autorisée", null)
                        return@setMethodCallHandler
                    }
                    val chooser = call.argument<Boolean>("chooser") ?: false
                    try {
                        val file = File(path)
                        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                        } else {
                            Uri.fromFile(file)
                        }

                        // Cas spécial : APK → installateur de paquets Android.
                        if (mime == "application/vnd.android.package-archive") {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                && !packageManager.canRequestPackageInstalls()) {
                                val settings = Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                startActivity(settings)
                                result.error(
                                    "INSTALL_PERMISSION_REQUIRED",
                                    "Autorisez 'Installer apps inconnues' pour Read Files Tech, puis réessayez.",
                                    null
                                )
                                return@setMethodCallHandler
                            }
                            val install = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(install)
                            result.success(null)
                            return@setMethodCallHandler
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
