import 'package:flutter/services.dart';
import '../file_type_helpers.dart';

/// Wrapper minimal autour du MethodChannel `com.readfilestech/open_file`.
/// Ne touche à aucun BuildContext — l'appelant gère snackbars et navigation.
class NativeOpenService {
  static const _ch = MethodChannel('com.readfilestech/open_file');

  /// Lance l'intent ACTION_VIEW (ou chooser) sur le fichier. Throws PlatformException.
  Future<void> openFile(String path, String ext, {bool chooser = false}) {
    return _ch.invokeMethod('openFile', {
      'path': path,
      'mime': mimeOf(ext) ?? '*/*',
      'chooser': chooser,
    });
  }

  /// Envoie le fichier via SEND vers un package particulier (kDrive, Proton…).
  Future<void> sendToPackage(String path, String pkg) {
    return _ch.invokeMethod('sendToPackage', {
      'path': path,
      'mime': mimeOf(fileExt(path)) ?? '*/*',
      'package': pkg,
    });
  }

  /// Ouvre un fichier dans un package précis (PDF Tech, etc.).
  Future<void> openWithPackage(String path, String pkg, String mime) {
    return _ch.invokeMethod('openWithPackage', {
      'path': path,
      'mime': mime,
      'package': pkg,
    });
  }

  /// `true` si l'utilisateur a accordé l'autorisation Android "Installer
  /// des applis inconnues" pour Read Files Tech (Android 8+ — sinon `true`
  /// par défaut sur les versions plus anciennes).
  Future<bool> canInstallApks() async {
    final ok = await _ch.invokeMethod<bool>('canInstallApks');
    return ok ?? false;
  }

  /// Ouvre l'écran Réglages → "Apps installant des applis inconnues" filtré
  /// sur notre package, pour que l'utilisateur active l'autorisation.
  Future<void> openInstallPermissionSettings() {
    return _ch.invokeMethod('openInstallPermissionSettings');
  }

  /// Extrait l'icône d'une APK (sans installation). Retourne les bytes PNG
  /// rasterisés à `size` px maxi. `null` si l'APK est illisible.
  Future<Uint8List?> getApkIcon(String path, {int size = 96}) async {
    try {
      final bytes = await _ch.invokeMethod<Uint8List>('getApkIcon', {
        'path': path,
        'size': size,
      });
      return bytes;
    } on PlatformException {
      return null;
    }
  }

  /// Déclenche le PackageInstaller système pour le .apk indiqué.
  /// Throws `PlatformException` :
  /// - `PERM_DENIED` si l'utilisateur n'a pas accordé l'autorisation
  /// - `FORBIDDEN` si le path est hors zone autorisée (anti symlink-pivot)
  /// - `NOT_APK` si le fichier ne se termine pas par .apk
  /// - `INSTALL_ERROR` pour toute autre erreur système
  Future<void> installApk(String path) {
    return _ch.invokeMethod('installApk', {'path': path});
  }
}
