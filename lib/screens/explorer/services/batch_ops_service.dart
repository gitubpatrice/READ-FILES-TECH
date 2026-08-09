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
  ///
  /// V-M1 (audit 2026-08-02) — un homonyme présent dans [destDir] était écrasé
  /// sans un mot. En mode `move`, la source était ensuite supprimée : les deux
  /// versions du fichier disparaissaient en une opération, sans confirmation
  /// et sans annulation possible. Pire cas atteignable en trois taps : copier
  /// vers un dossier contenant déjà un fichier du même nom.
  ///
  /// La destination est désormais un nom libre (`rapport (1).pdf`), même
  /// convention que la restauration depuis la corbeille — l'utilisateur voit
  /// les deux fichiers et tranche lui-même. Rien n'est jamais écrasé.
  ///
  /// Deux gardes viennent avec, parce que la première seule ne suffit pas :
  /// - copier un fichier **sur lui-même** (`destDir` = son propre dossier)
  ///   ouvrait la destination en écriture avant de lire la source ; comme
  ///   c'est le même inode, le fichier était vidé, puis supprimé en mode
  ///   `move`. Le nom libre évite désormais la collision, mais le cas est
  ///   testé explicitement ;
  /// - `move` ne supprime la source qu'après avoir vérifié que la copie fait
  ///   la même taille. Une copie tronquée (disque plein, carte SD retirée)
  ///   suivie d'un `delete()` était une perte de données silencieuse.
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
        final dest = uniqueDestination(destDir, name);
        final src = File(p);
        final srcLen = await src.length();
        await src.copy(dest);
        if (await File(dest).length() != srcLen) {
          throw FileSystemException('Copie incomplète', dest);
        }
        if (move) await src.delete();
        ok++;
      } catch (_) {
        fail++;
      }
    }
    return BatchResult(ok, fail);
  }
}

/// Renvoie un chemin libre dans [dir] pour [name] : `name`, puis `name (1)`,
/// `name (2)`… L'extension est préservée (`photo (1).jpg`, pas `photo.jpg (1)`).
///
/// Même convention que `TrashService._uniquePath`, volontairement : c'est ce
/// que l'utilisateur a déjà vu en restaurant depuis la corbeille.
String uniqueDestination(String dir, String name) {
  bool free(String p) =>
      FileSystemEntity.typeSync(p, followLinks: false) ==
      FileSystemEntityType.notFound;
  var candidate = '$dir/$name';
  if (free(candidate)) return candidate;
  final dot = name.lastIndexOf('.');
  // `dot > 0` : un fichier caché `.bashrc` n'a pas d'extension, tout est nom.
  final base = dot > 0 ? name.substring(0, dot) : name;
  final ext = dot > 0 ? name.substring(dot) : '';
  for (var i = 1; i < 1000; i++) {
    candidate = '$dir/$base ($i)$ext';
    if (free(candidate)) return candidate;
  }
  throw FileSystemException('Aucun nom libre dans le dossier', '$dir/$name');
}
