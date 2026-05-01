package com.readfilestech.read_files_tech

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.WindowManager
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : FlutterFragmentActivity() {

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

    /// Channel partagé pour pousser un shortcut quand l'activity est déjà
    /// vivante (singleTop) — déclenché par onNewIntent.
    private var shortcutChannel: MethodChannel? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shortcut = intent.getStringExtra("shortcut")
        if (shortcut != null) {
            shortcutChannel?.invokeMethod("onShortcut", shortcut)
            intent.removeExtra("shortcut")
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Channel "shortcut" : Dart appelle getShortcut() au boot pour récupérer
        // l'éventuel raccourci qui a lancé l'app (Quick Tile ou autre intent).
        // Et reçoit `onShortcut` si l'app était déjà ouverte (onNewIntent).
        shortcutChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.readfilestech/shortcut")
        shortcutChannel!!.setMethodCallHandler { call, result ->
            if (call.method == "getShortcut") {
                val shortcut = intent?.getStringExtra("shortcut")
                intent?.removeExtra("shortcut")
                result.success(shortcut)
            } else {
                result.notImplemented()
            }
        }

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
                    "setSecure" -> {
                        // FLAG_SECURE bloque captures écran + masque l'aperçu
                        // dans Recent Apps. Activé quand le coffre est unlocked
                        // ou un fichier sensible est affiché ; retiré au lock.
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        runOnUiThread {
                            if (enabled) {
                                window.setFlags(
                                    WindowManager.LayoutParams.FLAG_SECURE,
                                    WindowManager.LayoutParams.FLAG_SECURE
                                )
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            }
                        }
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
                } else if (call.method == "openWithPackage") {
                    val path = call.argument<String>("path")
                    val mime = call.argument<String>("mime") ?: "*/*"
                    val pkg  = call.argument<String>("package")
                    if (path == null || pkg == null) {
                        result.error("NO_ARGS", "path/package manquant", null)
                        return@setMethodCallHandler
                    }
                    if (!isAllowedPath(path)) {
                        result.error("FORBIDDEN", "Chemin hors zone autorisée", null)
                        return@setMethodCallHandler
                    }
                    try {
                        // Vérifie l'app installée.
                        val installed = try {
                            packageManager.getPackageInfo(pkg, 0); true
                        } catch (_: Exception) { false }
                        if (!installed) {
                            result.error("NOT_INSTALLED", "Application non installée : $pkg", null)
                            return@setMethodCallHandler
                        }
                        val file = File(path)
                        val uri: Uri = FileProvider.getUriForFile(
                            this, "$packageName.fileprovider", file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            setPackage(pkg)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("OPEN_ERROR", e.message, null)
                    }
                } else if (call.method == "sendToPackage") {
                    // Envoie un fichier vers une app cible via ACTION_SEND
                    // (cloud, messagerie…). Plus universel que ACTION_VIEW.
                    val path = call.argument<String>("path")
                    val mime = call.argument<String>("mime") ?: "*/*"
                    val pkg  = call.argument<String>("package")
                    if (path == null || pkg == null) {
                        result.error("NO_ARGS", "path/package manquant", null)
                        return@setMethodCallHandler
                    }
                    if (!isAllowedPath(path)) {
                        result.error("FORBIDDEN", "Chemin hors zone autorisée", null)
                        return@setMethodCallHandler
                    }
                    try {
                        val installed = try {
                            packageManager.getPackageInfo(pkg, 0); true
                        } catch (_: Exception) { false }
                        if (!installed) {
                            result.error("NOT_INSTALLED", "Application non installée : $pkg", null)
                            return@setMethodCallHandler
                        }
                        val file = File(path)
                        val uri: Uri = FileProvider.getUriForFile(
                            this, "$packageName.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            setPackage(pkg)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("OPEN_ERROR", e.message, null)
                    }
                } else if (call.method == "isPackageInstalled") {
                    val pkg = call.argument<String>("package")
                    if (pkg == null) {
                        result.error("NO_PACKAGE", "package manquant", null)
                        return@setMethodCallHandler
                    }
                    val installed = try {
                        packageManager.getPackageInfo(pkg, 0); true
                    } catch (_: Exception) { false }
                    result.success(installed)
                } else {
                    result.notImplemented()
                }
            }

        // Crypto AES-256-GCM native (vault). Utilise l'accélération matérielle
        // ARMv8 via javax.crypto.Cipher — 10-50× plus rapide que PointyCastle
        // Dart pur sur fichiers >10 Mo. Appelé par VaultService côté Dart
        // au-delà d'un seuil de taille pour éviter de freezer même un Isolate.
        //
        // Format identique à _encryptV2 / _decryptAuto Dart : magic "RFT2"
        // (4 bytes) + nonce (12 bytes) + ciphertext + tag GCM (16 bytes).
        // L'AAD (= "rft-vault-v2|" + filename) est passé tel quel par Dart.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger,
            "com.readfilestech/vault_crypto")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "encrypt" -> {
                        val key   = call.argument<ByteArray>("key")
                        val nonce = call.argument<ByteArray>("nonce")
                        val aad   = call.argument<ByteArray>("aad")
                        val plain = call.argument<ByteArray>("plain")
                        if (key == null || nonce == null || aad == null || plain == null) {
                            result.error("NO_ARGS", "args", null)
                            return@setMethodCallHandler
                        }
                        if (key.size != 32) {
                            result.error("BAD_KEY", "key", null)
                            return@setMethodCallHandler
                        }
                        if (nonce.size != 12) {
                            result.error("BAD_NONCE", "nonce", null)
                            return@setMethodCallHandler
                        }
                        try {
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(
                                Cipher.ENCRYPT_MODE,
                                SecretKeySpec(key, "AES"),
                                GCMParameterSpec(128, nonce)
                            )
                            cipher.updateAAD(aad)
                            val ct = cipher.doFinal(plain)
                            result.success(ct)
                        } catch (_: Exception) {
                            // Message constant — ne JAMAIS exposer e.message
                            // (peut contenir du contenu sensible selon impl JCE).
                            result.error("ENCRYPT_ERROR", "crypto", null)
                        } finally {
                            // Zeroize la clé reçue côté JVM pour réduire la
                            // fenêtre d'extraction par memory forensics.
                            // Note : le SecretKeySpec a fait sa propre copie,
                            // mais on efface au moins la copie qu'on contrôle.
                            java.util.Arrays.fill(key, 0)
                        }
                    }
                    "decrypt" -> {
                        val key   = call.argument<ByteArray>("key")
                        val nonce = call.argument<ByteArray>("nonce")
                        val aad   = call.argument<ByteArray>("aad")
                        val blob  = call.argument<ByteArray>("blob") // ciphertext+tag
                        if (key == null || nonce == null || aad == null || blob == null) {
                            result.error("NO_ARGS", "args", null)
                            return@setMethodCallHandler
                        }
                        if (key.size != 32) {
                            result.error("BAD_KEY", "key", null)
                            return@setMethodCallHandler
                        }
                        if (nonce.size != 12) {
                            result.error("BAD_NONCE", "nonce", null)
                            return@setMethodCallHandler
                        }
                        try {
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(
                                Cipher.DECRYPT_MODE,
                                SecretKeySpec(key, "AES"),
                                GCMParameterSpec(128, nonce)
                            )
                            cipher.updateAAD(aad)
                            val plain = cipher.doFinal(blob)
                            result.success(plain)
                        } catch (_: javax.crypto.AEADBadTagException) {
                            // Tampering OU mauvaise clé OU mauvais nonce/AAD.
                            // Message constant — pas d'oracle d'erreur fin.
                            result.error("DECRYPT_ERROR", "auth", null)
                        } catch (_: Exception) {
                            result.error("DECRYPT_ERROR", "crypto", null)
                        } finally {
                            java.util.Arrays.fill(key, 0)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
