import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'package:crypto/crypto.dart';
import 'package:files_tech_core/files_tech_core.dart';

/// Sous-dossiers système Android à ignorer (caches, miniatures, corbeille).
/// Match par substring sur le path complet — anchoré entre `/` pour éviter
/// les faux positifs.
const _skipDirSubstrings = <String>['/.thumbnails/', '/.trash/', '/cache/'];

class DuplicateSet {
  final String hash;
  final List<FileEntry> files;
  int get totalBytes => files.fold(0, (s, f) => s + f.size);
  int get wastedBytes => totalBytes - (files.isEmpty ? 0 : files.first.size);
  DuplicateSet(this.hash, this.files);
}

class FileEntry {
  final String path;
  final int size;
  final DateTime modified;
  const FileEntry({
    required this.path,
    required this.size,
    required this.modified,
  });
}

class FinderResult {
  final List<DuplicateSet> duplicates;
  final List<FileEntry> largest;
  final int filesScanned;
  const FinderResult(this.duplicates, this.largest, this.filesScanned);
}

class _Args {
  final SendPort out;
  final String root;
  final int topN;
  final int minSize;
  _Args(this.out, this.root, this.topN, this.minSize);
}

/// Trouve les doublons et les plus gros fichiers d'un dossier.
///
/// Approche 2-passes pour rester rapide sur 50k fichiers :
/// - Pass 1 : `stat()` → groupe par taille (cheap), dégage le top N par taille.
///   Toute taille unique est forcément un fichier sans doublon.
/// - Pass 2 : sur chaque bucket de taille ≥2, calcule un **partial hash**
///   (premier+dernier 64 KB) pour pré-filtrer.
/// - Pass 3 : sur les sous-buckets restants, calcule SHA-256 complet en
///   streaming (jamais readAsBytes).
class DuplicateFinderService {
  Isolate? _iso;
  ReceivePort? _recv;

  /// Identité de la recherche en cours.
  ///
  /// Même mécanisme que `global_search_service`, et pour la même raison — il
  /// y manquait ici. `Isolate.spawn` rend une `Future` : l'isolate peut avoir
  /// déjà répondu, et donc `cancel()` avoir déjà tourné, avant que cette
  /// `Future` ne se résolve. Sur un dossier vide, c'est même le cas courant.
  ///
  /// Sans jeton, le `await` affectait alors à `_iso` un isolate **vivant que
  /// plus personne ne tuerait** : un scan fantôme parcourant le stockage et
  /// calculant des empreintes jusqu'à la mort du process.
  ///
  /// Le défaut avait été corrigé dans le service jumeau le 2026-08-02, et
  /// jamais reporté ici.
  Object? _runToken;

  /// Résultat de la recherche en cours, tant qu'elle n'a pas abouti.
  ///
  /// Retenu pour pouvoir le **conclure** si la recherche est annulée ou
  /// supplantée. Sans cela, `cancel()` tuait bien l'isolate mais laissait le
  /// `Completer` en suspens : l'appelant attendait indéfiniment un résultat
  /// qui ne viendrait jamais. Le symptôme côté écran est le pire qui soit —
  /// un indicateur de progression qui tourne sans fin, sans erreur.
  Completer<FinderResult>? _pending;

  /// Rend une `Future` qui se conclut **toujours** : resultat, erreur, ou
  /// annulation. Jamais suspendue.
  ///
  /// Volontairement NON `async`. Une version `async` restait bloquee sur
  /// `await Isolate.spawn(...)` et ne rendait donc `completer.future` qu'apres
  /// coup : si la recherche etait annulee entre-temps, l'erreur etait posee sur
  /// une future que PERSONNE n'ecoutait encore, et Dart la signalait comme
  /// erreur non traitee — un plantage, la ou on voulait eviter une attente
  /// infinie. Ici la future part immediatement a l'appelant.
  Future<FinderResult> find({
    required String root,
    int topN = 50,
    int minSize = 4096,
  }) {
    // Une recherche déjà en vol est arrêtée avant d'en lancer une autre.
    //
    // Sans cela, un second appel écrasait `_iso` et `_recv` : le premier
    // isolate perdait sa référence et continuait de hacher des fichiers, son
    // port restait ouvert, et son `Completer` ne se complétait **jamais** —
    // l'appelant attendait indéfiniment.
    if (_runToken != null) cancel();

    final token = Object();
    _runToken = token;

    final recv = ReceivePort();
    _recv = recv;
    final completer = Completer<FinderResult>();
    _pending = completer;

    void terminer(void Function() action) {
      // Un message tardif d'un run précédent ne doit ni compléter ce
      // `Completer`, ni déclencher un `cancel()` qui tuerait le run courant.
      if (_runToken != token) return;
      // Detache le `Completer` avant de completer : sinon `cancel()`, appele
      // juste apres, tenterait de le conclure une SECONDE fois — en erreur,
      // par-dessus un resultat pourtant valide.
      _pending = null;
      action();
      cancel();
    }

    recv.listen((msg) {
      if (completer.isCompleted) return;
      if (msg is FinderResult) {
        terminer(() => completer.complete(msg));
      } else if (msg is String) {
        terminer(() => completer.completeError(msg));
      } else {
        // Garde-fou : tout autre type signale un bug logique côté isolate.
        // Ne pas laisser le Completer pendre indéfiniment.
        terminer(
          () => completer.completeError(
            StateError('Message Isolate inattendu : ${msg.runtimeType}'),
          ),
        );
      }
    });

    Isolate.spawn(_entry, _Args(recv.sendPort, root, topN, minSize))
        .then((iso) {
          if (_runToken != token) {
            // Termine ou annule pendant le `spawn` : cet isolate n'a plus de
            // proprietaire, personne ne le tuera. On s'en charge ici.
            iso.kill(priority: Isolate.immediate);
          } else {
            _iso = iso;
          }
        })
        .catchError((Object e) {
          // Echec du spawn lui-meme (memoire, limite systeme). Sans ceci, la
          // future restait suspendue pour toujours.
          if (_runToken != token) return;
          _pending = null;
          if (!completer.isCompleted) completer.completeError(e);
          cancel();
        });

    return completer.future;
  }

  void cancel() {
    _runToken = null;
    _iso?.kill(priority: Isolate.immediate);
    _iso = null;
    _recv?.close();
    _recv = null;
    // Conclure une recherche encore en attente. Une recherche annulée n'a pas
    // de résultat, mais elle doit **finir** : un `Completer` abandonné laisse
    // son appelant suspendu pour toujours.
    final pending = _pending;
    _pending = null;
    if (pending != null && !pending.isCompleted) {
      // Differe d'une microtache, et ce n'est pas un detail. `cancel()` est
      // appele SYNCHRONEMENT au debut de `find()` : completer l'erreur sur le
      // champ la delivrerait avant que l'appelant du premier `find()` ait eu
      // l'occasion d'attacher son `catchError`. Dart la classerait alors en
      // erreur NON TRAITEE — un plantage, la ou on voulait justement eviter
      // une attente infinie.
      scheduleMicrotask(() {
        if (!pending.isCompleted) {
          pending.completeError(StateError('Recherche de doublons annulée'));
        }
      });
    }
  }

  static Future<void> _entry(_Args a) async {
    try {
      final root = Directory(a.root);
      if (!await root.exists()) {
        a.out.send('Dossier introuvable');
        return;
      }
      // ── Pass 1 : collecte stat()
      final allFiles = <FileEntry>[];
      final bySize = <int, List<FileEntry>>{};
      await for (final e in root.list(recursive: true, followLinks: false)) {
        if (e is! File) continue;
        final name = PathUtils.fileName(e.path);
        if (name.startsWith('.')) continue;
        // Skip dossiers système Android (substring match anchored entre `/`).
        if (_skipDirSubstrings.any(e.path.contains)) continue;
        FileStat stat;
        try {
          stat = await e.stat();
        } catch (_) {
          continue;
        }
        if (stat.size < a.minSize) continue;
        final entry = FileEntry(
          path: e.path,
          size: stat.size,
          modified: stat.modified,
        );
        allFiles.add(entry);
        bySize.putIfAbsent(stat.size, () => []).add(entry);
      }

      // Top N par taille (free side-product de la pass 1)
      allFiles.sort((x, y) => y.size.compareTo(x.size));
      final largest = allFiles.take(a.topN).toList();

      // ── Pass 2 : partial hash sur buckets de taille ≥ 2
      final byPartial = <String, List<FileEntry>>{};
      for (final bucket in bySize.values) {
        if (bucket.length < 2) continue;
        for (final f in bucket) {
          try {
            final ph = await _partialHash(File(f.path), f.size);
            // Préfixe avec la taille pour ne jamais collisionner entre buckets
            byPartial.putIfAbsent('${f.size}:$ph', () => []).add(f);
          } catch (_) {}
        }
      }

      // ── Pass 3 : SHA-256 complet streamé sur sous-buckets ≥ 2
      final duplicates = <DuplicateSet>[];
      for (final bucket in byPartial.values) {
        if (bucket.length < 2) continue;
        final byFull = <String, List<FileEntry>>{};
        for (final f in bucket) {
          try {
            final fh = await _fullHash(File(f.path));
            byFull.putIfAbsent(fh, () => []).add(f);
          } catch (_) {}
        }
        for (final entry in byFull.entries) {
          if (entry.value.length >= 2) {
            duplicates.add(DuplicateSet(entry.key, entry.value));
          }
        }
      }

      // Tri : plus gros gaspillage en premier
      duplicates.sort((x, y) => y.wastedBytes.compareTo(x.wastedBytes));

      a.out.send(FinderResult(duplicates, largest, allFiles.length));
    } catch (e) {
      a.out.send('$e');
    }
  }

  /// SHA-256 sur les 64 KB de tête + 64 KB de queue (rapide, suffisant pour
  /// pré-filtrer). On préfixe avec la taille via la clé du bucket parent.
  /// Avant v2.9.x : SHA-1, déprécié pour usage cryptographique. SHA-256
  /// retire le risque de collision malicieuse (clé de bucket Map) sans coût
  /// perceptible (128 Ko hashés ≈ identique CPU sur ARMv8).
  static Future<String> _partialHash(File f, int size) async {
    const head = 64 * 1024;
    final raf = await f.open();
    try {
      final headBytes = await raf.read(size < head ? size : head);
      List<int> tailBytes = const [];
      if (size > head * 2) {
        await raf.setPosition(size - head);
        tailBytes = await raf.read(head);
      }
      final all = [...headBytes, ...tailBytes];
      return sha256.convert(all).toString();
    } finally {
      await raf.close();
    }
  }

  /// SHA-256 streamé : jamais readAsBytes (OOM sur vidéo 2 Go).
  static Future<String> _fullHash(File f) async {
    final digest = await sha256.bind(f.openRead()).first;
    return digest.toString();
  }
}
