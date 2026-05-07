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
}
