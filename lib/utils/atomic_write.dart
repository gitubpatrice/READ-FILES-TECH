import 'dart:io';
import 'dart:typed_data';

/// Écriture atomique : `tmp + flush + rename`.
///
/// Garantit qu'un kill OS / OOM en milieu d'écriture ne laisse pas un fichier
/// final tronqué. Le `rename()` POSIX est atomique sur même filesystem.
///
/// En cas d'erreur, le `.tmp` résiduel est supprimé best-effort.
Future<void> atomicWriteBytes(String path, List<int> bytes) async {
  final tmpPath = '$path.tmp';
  final tmp = File(tmpPath);
  try {
    await tmp.writeAsBytes(bytes, flush: true);
    await tmp.rename(path);
  } catch (e) {
    try {
      if (await tmp.exists()) await tmp.delete();
    } catch (_) {}
    rethrow;
  }
}

/// Variante string. UTF-8 par défaut.
Future<void> atomicWriteString(String path, String content) async {
  final tmpPath = '$path.tmp';
  final tmp = File(tmpPath);
  try {
    await tmp.writeAsString(content, flush: true);
    await tmp.rename(path);
  } catch (e) {
    try {
      if (await tmp.exists()) await tmp.delete();
    } catch (_) {}
    rethrow;
  }
}

/// Variante prenant un `Uint8List` typé (évite cast inutile).
Future<void> atomicWriteUint8(String path, Uint8List bytes) =>
    atomicWriteBytes(path, bytes);
