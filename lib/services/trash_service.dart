import 'dart:convert';
import 'dart:io';

import 'package:files_tech_core/files_tech_core.dart';
import 'package:path_provider/path_provider.dart';

import '../utils/atomic_write.dart';

/// Une entrée de la corbeille : la charge utile déplacée + ses métadonnées.
class TrashEntry {
  /// Identifiant unique dans la corbeille (nom du dossier de charge utile).
  final String id;

  /// Racine de la corbeille qui contient cette entrée (`<volume>/.RFT_Corbeille`).
  final String trashRoot;

  /// Chemin d'origine, cible d'une restauration.
  final String originalPath;

  /// Nom d'origine (basename), conservé tel quel dans la corbeille.
  final String name;

  final bool isDir;

  /// Taille en octets, `-1` si inconnue (dossiers).
  final int size;

  final DateTime deletedAt;

  const TrashEntry({
    required this.id,
    required this.trashRoot,
    required this.originalPath,
    required this.name,
    required this.isDir,
    required this.size,
    required this.deletedAt,
  });

  /// Emplacement réel de l'élément dans la corbeille.
  String get payloadPath => '$trashRoot/${TrashService.itemsDir}/$id/$name';

  /// Emplacement du fichier de métadonnées associé.
  String get metaPath => '$trashRoot/${TrashService.metaDir}/$id.json';

  /// Dossier conteneur de la charge utile (supprimé avec l'entrée).
  String get itemDirPath => '$trashRoot/${TrashService.itemsDir}/$id';

  Map<String, dynamic> toJson() => {
    'id': id,
    'originalPath': originalPath,
    'name': name,
    'isDir': isDir,
    'size': size,
    'deletedAt': deletedAt.toIso8601String(),
  };

  /// Format d'identifiant produit par `TrashService._newId` : uniquement des
  /// chiffres, éventuellement suffixés `_n`. Tout écart est rejeté.
  static final RegExp _idPattern = RegExp(r'^\d+(_\d+)?$');

  /// `true` si [s] est un segment de chemin sûr (pas de séparateur, pas de
  /// remontée `..`, pas d'octet nul).
  static bool isSafeSegment(String s) =>
      s.isNotEmpty &&
      s != '.' &&
      s != '..' &&
      !s.contains('/') &&
      !s.contains('\\') &&
      !s.contains('\x00');

  /// `true` si [p] peut être une cible de restauration : chemin absolu, sans
  /// remontée `..`, sans octet nul.
  static bool isPlausibleOriginalPath(String p) {
    if (p.length < 2 || p.contains('\x00')) return false;
    if (p.split(RegExp(r'[/\\]')).contains('..')) return false;
    return p.startsWith('/') || RegExp(r'^[A-Za-z]:[/\\]').hasMatch(p);
  }

  /// Retourne `null` si le JSON est illisible, incomplet — ou falsifié.
  ///
  /// Les métadonnées vivent en clair sur le stockage partagé : une autre app
  /// disposant d'un accès large, ou un outil de sauvegarde tiers, peut les
  /// réécrire. `id`, `name` et `originalPath` alimentant directement des
  /// chemins de fichiers, ils sont validés ici — sinon une entrée forgée
  /// (`"id": "../.."`) ferait porter « Restaurer » ou « Supprimer
  /// définitivement » sur un fichier arbitraire atteint par traversée.
  static TrashEntry? fromJson(Map<String, dynamic> j, String trashRoot) {
    final id = j['id'];
    final originalPath = j['originalPath'];
    final name = j['name'];
    final deletedAt = DateTime.tryParse(j['deletedAt'] as String? ?? '');
    if (id is! String ||
        originalPath is! String ||
        name is! String ||
        deletedAt == null ||
        !_idPattern.hasMatch(id) ||
        !isSafeSegment(name) ||
        !isPlausibleOriginalPath(originalPath)) {
      return null;
    }
    return TrashEntry(
      id: id,
      trashRoot: trashRoot,
      originalPath: originalPath,
      name: name,
      isDir: j['isDir'] == true,
      size: (j['size'] as num?)?.toInt() ?? -1,
      deletedAt: deletedAt,
    );
  }
}

/// Corbeille locale : la suppression « douce » déplace l'élément dans un
/// dossier caché à la racine de SON volume (`/storage/emulated/0/.RFT_Corbeille`,
/// carte SD, ou dossier de l'app en dernier recours).
///
/// Rester sur le même volume permet un `rename()` atomique et instantané, quelle
/// que soit la taille du fichier ; une copie octet-à-octet n'est utilisée qu'en
/// repli si le `rename()` échoue (volumes différents).
///
/// Rien n'est purgé automatiquement : seul l'utilisateur vide la corbeille.
class TrashService {
  /// Dossier caché — invisible par défaut dans l'explorateur (`_showHidden`).
  static const trashDirName = '.RFT_Corbeille';
  static const itemsDir = 'items';
  static const metaDir = 'meta';

  /// Base de repli lorsqu'aucun volume Android n'est identifiable dans le
  /// chemin (et unique base utilisée par les tests, qui n'ont pas de plugin
  /// `path_provider`). `null` en production → dossier documents de l'app.
  final String? fallbackBase;

  TrashService({this.fallbackBase});

  // ---------- Emplacements ----------

  /// Racine du volume contenant [path], ou `null` si non déterminable.
  static String? _volumeRootOf(String path) {
    if (path.startsWith('/storage/emulated/0')) return '/storage/emulated/0';
    if (path.startsWith('/sdcard')) return '/sdcard';
    final m = RegExp(r'^(/storage/[^/]+)').firstMatch(path);
    if (m != null) {
      final root = m.group(1)!;
      if (root != '/storage/self' && root != '/storage/emulated') return root;
    }
    return null;
  }

  Future<String> _fallbackBase() async =>
      fallbackBase ?? (await getApplicationDocumentsDirectory()).path;

  /// Racine de corbeille à utiliser pour [path] (créée si absente).
  Future<String> _trashRootFor(String path) async {
    final volume = _volumeRootOf(path);
    final base = volume ?? await _fallbackBase();
    final root = '$base/$trashDirName';
    await Directory('$root/$itemsDir').create(recursive: true);
    await Directory('$root/$metaDir').create(recursive: true);
    return root;
  }

  /// Toutes les racines de corbeille existantes, dédupliquées par chemin réel
  /// (`/sdcard` est un lien symbolique vers `/storage/emulated/0`).
  Future<List<String>> _existingTrashRoots() async {
    final bases = <String>{};
    if (Platform.isAndroid) {
      bases.addAll(['/storage/emulated/0', '/sdcard']);
      try {
        for (final e in Directory('/storage').listSync(followLinks: false)) {
          final name = e.path.split('/').last;
          if (name == 'self' || name == 'emulated') continue;
          bases.add(e.path);
        }
      } catch (_) {
        // /storage non listable (pas de permission) — on garde les bases connues.
      }
    }
    try {
      bases.add(await _fallbackBase());
    } catch (_) {}

    final seenReal = <String>{};
    final roots = <String>[];
    for (final b in bases) {
      final root = '$b/$trashDirName';
      final dir = Directory(root);
      if (!dir.existsSync()) continue;
      String real;
      try {
        real = dir.resolveSymbolicLinksSync();
      } catch (_) {
        real = root;
      }
      if (seenReal.add(real)) roots.add(root);
    }
    return roots;
  }

  /// True si [path] est la corbeille elle-même ou l'un de ses parents — cas où
  /// la mise à la corbeille se mordrait la queue.
  static bool _isTrashRelated(String path) {
    final normalized = path.endsWith('/')
        ? path.substring(0, path.length - 1)
        : path;
    if (normalized.split('/').contains(trashDirName)) return true;
    final volume = _volumeRootOf(normalized);
    if (volume != null && normalized == volume) return true;
    return false;
  }

  // ---------- Opérations ----------

  /// Déplace [entity] dans la corbeille de son volume. Lève en cas d'échec.
  Future<TrashEntry> moveToTrash(FileSystemEntity entity) async {
    final path = entity.path;
    if (_isTrashRelated(path)) {
      throw const FileSystemException(
        'Cet élément ne peut pas être mis à la corbeille',
      );
    }
    final name = PathSafe.basename(path);
    final type = FileSystemEntity.typeSync(path, followLinks: false);
    if (type == FileSystemEntityType.notFound) {
      throw FileSystemException('Introuvable', path);
    }
    final isDir = type == FileSystemEntityType.directory;

    final root = await _trashRootFor(path);
    final id = _newId(root);
    final itemDir = Directory('$root/$itemsDir/$id');
    await itemDir.create(recursive: true);

    int size = -1;
    if (!isDir) {
      try {
        size = await File(path).length();
      } catch (_) {}
    }

    final dest = '${itemDir.path}/$name';
    try {
      await _move(path, dest, isDir: isDir);
    } catch (_) {
      await _deleteQuietly(itemDir.path);
      rethrow;
    }

    final entry = TrashEntry(
      id: id,
      trashRoot: root,
      originalPath: path,
      name: name,
      isDir: isDir,
      size: size,
      deletedAt: DateTime.now(),
    );
    try {
      await atomicWriteString(
        '$root/$metaDir/$id.json',
        jsonEncode(entry.toJson()),
      );
    } catch (_) {
      // Sans métadonnées l'entrée serait irrécupérable : on remet l'élément
      // à sa place plutôt que de laisser un orphelin dans la corbeille.
      try {
        await _move(dest, path, isDir: isDir);
        await _deleteQuietly(itemDir.path);
      } catch (_) {}
      rethrow;
    }
    return entry;
  }

  /// Liste toutes les entrées valides, les plus récentes d'abord.
  /// Les métadonnées orphelines (charge utile disparue) sont nettoyées.
  Future<List<TrashEntry>> list() async {
    final out = <TrashEntry>[];
    for (final root in await _existingTrashRoots()) {
      final metas = Directory('$root/$metaDir');
      List<FileSystemEntity> files;
      try {
        files = metas.listSync(followLinks: false);
      } catch (_) {
        continue;
      }
      for (final f in files) {
        if (f is! File || !f.path.endsWith('.json')) continue;
        TrashEntry? entry;
        try {
          final raw = jsonDecode(await f.readAsString());
          if (raw is Map<String, dynamic>) {
            entry = TrashEntry.fromJson(raw, root);
          }
        } catch (_) {
          entry = null;
        }
        if (entry == null) continue;
        // Le nom du fichier de méta DOIT correspondre à l'id qu'il déclare :
        // sinon un fichier `meta/quelconque.json` déposé par un tiers ferait
        // surface dans la corbeille comme une entrée légitime.
        if (f.path.split(RegExp(r'[/\\]')).last != '${entry.id}.json') continue;
        if (FileSystemEntity.typeSync(entry.payloadPath, followLinks: false) ==
            FileSystemEntityType.notFound) {
          await _deleteQuietly(f.path);
          await _deleteQuietly(entry.itemDirPath);
          continue;
        }
        out.add(entry);
      }
    }
    out.sort((a, b) => b.deletedAt.compareTo(a.deletedAt));
    return out;
  }

  /// Restaure [entry] à son emplacement d'origine. Si un élément porte déjà ce
  /// nom, un suffixe ` (1)`, ` (2)`… est ajouté. Renvoie le chemin final.
  Future<String> restore(TrashEntry entry) async {
    // Défense en profondeur : `fromJson` valide déjà, mais une TrashEntry peut
    // aussi arriver directement de `moveToTrash` (chemin d'annulation).
    if (!TrashEntry.isPlausibleOriginalPath(entry.originalPath) ||
        !TrashEntry.isSafeSegment(entry.name)) {
      throw FileSystemException(
        'Chemin d\'origine invalide',
        entry.originalPath,
      );
    }
    final cut = entry.originalPath.lastIndexOf('/');
    if (cut <= 0) {
      throw FileSystemException(
        'Chemin d\'origine invalide',
        entry.originalPath,
      );
    }
    final parent = entry.originalPath.substring(0, cut);
    await Directory(parent).create(recursive: true);
    final dest = _uniquePath(parent, entry.name);
    await _move(entry.payloadPath, dest, isDir: entry.isDir);
    await _deleteQuietly(entry.metaPath);
    await _deleteQuietly(entry.itemDirPath);
    return dest;
  }

  /// Supprime définitivement [entry] (charge utile + métadonnées).
  Future<void> deleteForever(TrashEntry entry) async {
    if (entry.isDir) {
      final d = Directory(entry.payloadPath);
      if (d.existsSync()) await d.delete(recursive: true);
    } else {
      final f = File(entry.payloadPath);
      if (f.existsSync()) await f.delete();
    }
    await _deleteQuietly(entry.metaPath);
    await _deleteQuietly(entry.itemDirPath);
  }

  /// Vide la corbeille. Renvoie (supprimés, échecs).
  Future<({int ok, int fail})> emptyAll(List<TrashEntry> entries) async {
    int ok = 0, fail = 0;
    for (final e in entries) {
      try {
        await deleteForever(e);
        ok++;
      } catch (_) {
        fail++;
      }
    }
    return (ok: ok, fail: fail);
  }

  // ---------- Primitives ----------

  String _newId(String root) {
    final stamp = DateTime.now().millisecondsSinceEpoch;
    var id = '$stamp';
    var n = 1;
    while (Directory('$root/$itemsDir/$id').existsSync()) {
      id = '${stamp}_${n++}';
    }
    return id;
  }

  /// Déplacement : `rename()` d'abord (atomique, même volume), copie récursive
  /// + suppression en repli si le renommage échoue (volumes différents).
  Future<void> _move(String from, String to, {required bool isDir}) async {
    try {
      if (isDir) {
        await Directory(from).rename(to);
      } else {
        await File(from).rename(to);
      }
      return;
    } on FileSystemException {
      // Repli ci-dessous.
    }
    if (isDir) {
      await _copyDir(Directory(from), Directory(to));
      await Directory(from).delete(recursive: true);
    } else {
      await File(from).copy(to);
      await File(from).delete();
    }
  }

  /// Copie récursive. Les liens symboliques sont ignorés (jamais suivis) pour
  /// éviter les boucles et les écritures hors de l'arborescence copiée.
  Future<void> _copyDir(Directory from, Directory to) async {
    await to.create(recursive: true);
    await for (final e in from.list(followLinks: false)) {
      final name = e.path.split('/').last;
      if (e is Directory) {
        await _copyDir(e, Directory('${to.path}/$name'));
      } else if (e is File) {
        await e.copy('${to.path}/$name');
      }
    }
  }

  String _uniquePath(String dir, String name) {
    bool free(String p) =>
        FileSystemEntity.typeSync(p, followLinks: false) ==
        FileSystemEntityType.notFound;
    var candidate = '$dir/$name';
    if (free(candidate)) return candidate;
    final dot = name.lastIndexOf('.');
    final base = dot > 0 ? name.substring(0, dot) : name;
    final ext = dot > 0 ? name.substring(dot) : '';
    for (var i = 1; i < 1000; i++) {
      candidate = '$dir/$base ($i)$ext';
      if (free(candidate)) return candidate;
    }
    throw FileSystemException('Impossible de restaurer', '$dir/$name');
  }

  Future<void> _deleteQuietly(String path) async {
    try {
      final type = FileSystemEntity.typeSync(path, followLinks: false);
      if (type == FileSystemEntityType.directory) {
        await Directory(path).delete(recursive: true);
      } else if (type != FileSystemEntityType.notFound) {
        await File(path).delete();
      }
    } catch (_) {}
  }
}
