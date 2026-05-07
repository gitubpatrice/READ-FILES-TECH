import 'dart:io';
import 'package:files_tech_core/files_tech_core.dart';

class BatchResult {
  final int ok;
  final int fail;
  const BatchResult(this.ok, this.fail);
}

/// Opérations batch pures (delete / copy / move). Ne touche ni au state UI ni
/// au context — l'appelant gère snackbars, dialogs et refresh.
class BatchOpsService {
  Future<BatchResult> deleteAll(Iterable<String> paths) async {
    int ok = 0, fail = 0;
    for (final p in paths) {
      try {
        final type = FileSystemEntity.typeSync(p);
        if (type == FileSystemEntityType.directory) {
          await Directory(p).delete(recursive: true);
        } else {
          await File(p).delete();
        }
        ok++;
      } catch (_) {
        fail++;
      }
    }
    return BatchResult(ok, fail);
  }

  /// Copie tous les fichiers (dossiers ignorés → comptés en fail) vers [destDir].
  /// Si [move] est true, l'original est supprimé après la copie.
  Future<BatchResult> copyAll(
    Iterable<String> paths,
    String destDir, {
    required bool move,
  }) async {
    int ok = 0, fail = 0;
    for (final p in paths) {
      try {
        final type = FileSystemEntity.typeSync(p);
        if (type != FileSystemEntityType.file) {
          fail++;
          continue;
        }
        final name = PathSafe.basename(p);
        await File(p).copy('$destDir/$name');
        if (move) await File(p).delete();
        ok++;
      } catch (_) {
        fail++;
      }
    }
    return BatchResult(ok, fail);
  }
}
