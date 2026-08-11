import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/foundation.dart';

/// Critères de recherche globale.
class SearchQuery {
  final String rootPath;
  final String?
  namePattern; // sous-chaîne casse-insensitive ; null = pas de filtre
  final String?
  contentPattern; // sous-chaîne casse-insensitive ; null = pas de recherche contenu
  final Set<String> extensions; // si vide → tout
  final int maxContentBytes; // ne lit pas le contenu au-delà de cette taille
  final int maxResults; // arrêt après ce nombre de hits
  const SearchQuery({
    required this.rootPath,
    this.namePattern,
    this.contentPattern,
    this.extensions = const {},
    this.maxContentBytes = 2 * 1024 * 1024,
    this.maxResults = 1000,
  });
}

/// Un résultat individuel.
class SearchHit {
  final String path;
  final int size;
  final DateTime modified;
  final String? snippet; // ligne de contenu si contenu matché
  const SearchHit({
    required this.path,
    required this.size,
    required this.modified,
    this.snippet,
  });
}

class _Msg {
  final String type; // 'hit' | 'progress' | 'done' | 'error'
  final dynamic data;
  const _Msg(this.type, this.data);
}

class _StartArgs {
  final SendPort outPort;
  final SendPort cancelAck;
  final SearchQuery query;
  _StartArgs(this.outPort, this.cancelAck, this.query);
}

/// Recherche globale stream. Tourne dans un Isolate pour ne pas figer l'UI.
/// Annulable via [cancel].
class GlobalSearchService {
  Isolate? _isolate;
  ReceivePort? _receive;

  /// Port sur lequel le worker renvoie son propre port d'annulation. Retenu
  /// pour pouvoir le FERMER : sans cela il fuyait a chaque recherche.
  ReceivePort? _cancelReceive;

  /// Port d'annulation du worker, connu seulement une fois qu'il l'a renvoye.
  SendPort? _cancelPort;

  /// Lance une recherche. Retourne deux streams :
  /// - [hits] : flux de SearchHit (batchés, ~20 par tick)
  /// - [progress] : nombre de fichiers scannés (informationnel)
  /// La méthode retourne quand l'isolate est terminé OU annulé.
  Stream<dynamic> search(SearchQuery q) {
    final controller =
        StreamController<
          dynamic
        >(); // dynamic = SearchHit | int progress | 'done'
    // Une recherche deja en vol est arretee AVANT d'en lancer une autre.
    //
    // Sans cela, un second appel ecrasait `_isolate`, `_receive` et
    // `_runToken` : le premier isolate perdait sa reference et continuait de
    // parcourir tout le stockage jusqu'a la mort du process, et le premier
    // `StreamController` ne se fermait jamais. L'ecran appelant annule bien son
    // abonnement avant de relancer, donc le cas n'etait pas atteignable — mais
    // une API qui fuit quand on l'emploie de la maniere la plus naturelle est
    // un piege pour l'appelant suivant.
    if (_runToken != null) cancel();

    _receive = ReceivePort();
    // Ce port recoit le VRAI port d'annulation, que le worker cree de son cote
    // et renvoie ici. `_cancelPort` pointait auparavant sur `cancelPort`
    // lui-meme, c'est-a-dire sur notre propre port de reception : `cancel()`
    // envoyait donc « cancel » dans un port que personne n'ecoutait, et le
    // drapeau `cancelled` du worker ne pouvait JAMAIS passer a `true`.
    //
    // L'annulation semblait fonctionner parce que `_cleanup()` tue l'isolate
    // juste apres. Mais le worker n'avait aucun moyen de s'arreter proprement,
    // et le mecanisme cooperatif — commente en detail plus bas — etait du code
    // mort.
    final cancelPort = ReceivePort();
    _cancelReceive = cancelPort;
    cancelPort.listen((msg) {
      if (msg is SendPort) _cancelPort = msg;
    });
    // V-M9 (audit 2026-08-02) — course entre `spawn` et `cancel`.
    //
    // `Isolate.spawn` est asynchrone : si l'utilisateur annule (ou quitte
    // l'écran) avant que le `.then` ne s'exécute, `_cleanup()` tourne alors
    // que `_isolate` est encore null, puis le `.then` affecte l'isolate
    // FRAÎCHEMENT SPAWNÉ à un champ que plus personne ne tuera. Résultat : un
    // scan fantôme qui continue de parcourir le stockage, intuable, jusqu'à
    // la mort du process.
    //
    // Le jeton capture l'identité de CETTE recherche. Si `_cleanup` est passé
    // entre-temps, il ne correspond plus et l'isolate est tué sur place.
    final token = Object();
    _runToken = token;
    Isolate.spawn<_StartArgs>(
          _entry,
          _StartArgs(_receive!.sendPort, cancelPort.sendPort, q),
        )
        .then((iso) {
          if (_runToken != token) {
            // Annulé pendant le spawn : cet isolate n'a plus de propriétaire.
            iso.kill(priority: Isolate.immediate);
            return;
          }
          _isolate = iso;
        })
        .catchError((e) {
          if (!controller.isClosed) {
            controller.addError(e);
            controller.close();
          }
        });
    _receive!.listen((msg) {
      if (msg is! _Msg) return;
      switch (msg.type) {
        case 'hit':
          for (final h in (msg.data as List<SearchHit>)) {
            if (!controller.isClosed) controller.add(h);
          }
          break;
        case 'progress':
          if (!controller.isClosed) controller.add(msg.data as int);
          break;
        case 'done':
          if (!controller.isClosed) controller.close();
          _cleanup();
          break;
        case 'error':
          if (!controller.isClosed) controller.addError(msg.data);
          break;
      }
    });
    controller.onCancel = () {
      cancel();
    };
    return controller.stream;
  }

  /// Identité de la recherche en cours. Voir la course décrite dans [search].
  Object? _runToken;

  void cancel() {
    try {
      _cancelPort?.send('cancel');
    } catch (_) {}
    _cleanup();
  }

  void _cleanup() {
    // Invalide le jeton AVANT tout : un `spawn` encore en vol verra qu'il n'a
    // plus de propriétaire et se tuera lui-même.
    _runToken = null;
    _isolate?.kill(priority: Isolate.immediate);
    _isolate = null;
    _receive?.close();
    _receive = null;
    // Fermeture du port d'annulation, qui manquait : un `ReceivePort` restait
    // ouvert a CHAQUE recherche, et un port ouvert maintient l'isolate courant
    // en vie du point de vue de la VM.
    _cancelReceive?.close();
    _cancelReceive = null;
    _cancelPort = null;
  }

  // ── Isolate worker ──────────────────────────────────────────────────────────

  static const _textExts = {
    'txt',
    'md',
    'csv',
    'xml',
    'json',
    'html',
    'htm',
    'css',
    'js',
    'php',
    'dart',
    'yaml',
    'yml',
    'ini',
    'conf',
    'log',
    'tsv',
    'rst',
    'tex',
    'sh',
    'py',
    'java',
    'kt',
  };

  static Future<void> _entry(_StartArgs args) async {
    final out = args.outPort;
    bool cancelled = false;
    final cancelReceive = ReceivePort();
    args.cancelAck.send(cancelReceive.sendPort);
    cancelReceive.listen((m) {
      if (m == 'cancel') cancelled = true;
    });

    final q = args.query;
    final root = Directory(q.rootPath);
    if (!await root.exists()) {
      out.send(const _Msg('error', 'Dossier source introuvable'));
      out.send(const _Msg('done', null));
      return;
    }

    final namePat = q.namePattern?.toLowerCase();
    final contentPat = q.contentPattern?.toLowerCase();
    final batch = <SearchHit>[];
    var hits = 0;
    var scanned = 0;
    var lastFlush = DateTime.now();

    Future<void> flush() async {
      if (batch.isEmpty) return;
      out.send(_Msg('hit', List<SearchHit>.from(batch)));
      batch.clear();
      lastFlush = DateTime.now();
    }

    try {
      // `handleError` et non un `try` global : sur Android 11+,
      // `/storage/emulated/0/Android/data` et `/Android/obb` sont illisibles
      // MEME avec MANAGE_EXTERNAL_STORAGE. Une recherche lancee depuis la
      // racine du stockage les rencontre a coup sur.
      //
      // Sans ce filtre, la premiere `FileSystemException` remontait par
      // `await for` jusqu'au `try` global : la recherche s'arretait la, et
      // l'utilisateur recevait des resultats PARTIELS accompagnes d'une erreur,
      // sans moyen de savoir que le reste du stockage n'avait pas ete visite.
      //
      // Le flux continue apres l'erreur : seule l'entree fautive est perdue.
      await for (final entity
          in root
              .list(recursive: true, followLinks: false)
              .handleError((_) {}, test: (e) => e is FileSystemException)) {
        if (cancelled || hits >= q.maxResults) break;
        if (entity is! File) continue;
        final name = PathUtils.fileName(entity.path);
        // Le controle ne portait que sur le NOM DU FICHIER : un fichier
        // parfaitement visible situe dans un dossier cache passait. Or c'est
        // exactement le cas que ce filtre vise — `.thumbnails/` sous `DCIM`
        // contient une vignette par photo de l'appareil, et une recherche
        // large les faisait toutes remonter.
        //
        // Le chemin RELATIF a la racine est teste, pas le chemin absolu :
        // chercher dans un dossier lui-meme cache (`/…/.local/notes`) doit
        // rester possible si l'utilisateur l'a explicitement choisi comme
        // racine.
        final relatif = entity.path
            .substring(q.rootPath.length)
            .replaceAll(r'\', '/');
        if (relatif.split('/').any((seg) => seg.startsWith('.'))) continue;

        final lower = name.toLowerCase();
        final ext = lower.contains('.') ? PathUtils.fileExt(lower) : '';
        if (q.extensions.isNotEmpty && !q.extensions.contains(ext)) continue;

        scanned++;
        if (scanned % 200 == 0) {
          out.send(_Msg('progress', scanned));
        }

        // `nameMatches` valait `true` quand AUCUN motif de nom n'etait donne.
        // Combine au test `snippet == null && !nameMatches` plus bas, cela
        // rendait toute recherche par CONTENU seul equivalente a « tous les
        // fichiers » : chercher « mot de passe » remontait l'integralite du
        // stockage, la plupart des resultats sans extrait.
        //
        // On distingue desormais « le motif correspond » de « il n'y avait pas
        // de motif ». Un critere absent est NEUTRE, il ne vaut pas succes.
        final nameGiven = namePat != null;
        final nameMatches = nameGiven && lower.contains(namePat);
        // Sans motif de contenu, seul le nom peut retenir le fichier.
        if (contentPat == null && nameGiven && !nameMatches) continue;

        // Filtre contenu (uniquement si demandé ET extension texte ET taille OK)
        String? snippet;
        if (contentPat != null) {
          if (!_textExts.contains(ext)) {
            // Fichier binaire : le contenu ne sera pas lu. Il ne subsiste que
            // si le NOM correspond a un motif effectivement demande.
            if (!nameMatches) continue;
          } else {
            FileStat? stat;
            try {
              stat = await entity.stat();
            } catch (_) {
              continue;
            }
            if (stat.size > q.maxContentBytes) {
              if (!nameMatches) continue;
            } else {
              snippet = await _findSnippet(entity, contentPat);
              if (snippet == null && !nameMatches) continue;
            }
          }
        }

        FileStat stat;
        try {
          stat = await entity.stat();
        } catch (_) {
          continue;
        }
        batch.add(
          SearchHit(
            path: entity.path,
            size: stat.size,
            modified: stat.modified,
            snippet: snippet,
          ),
        );
        hits++;
        if (batch.length >= 20 ||
            DateTime.now().difference(lastFlush).inMilliseconds > 200) {
          await flush();
        }
      }
      await flush();
    } catch (e) {
      out.send(_Msg('error', e.toString()));
    } finally {
      out.send(const _Msg('done', null));
    }
  }

  /// Lit le fichier ligne par ligne et retourne la première qui contient
  /// [pattern] (déjà en minuscules). Renvoie null si aucune. Stream pour
  /// éviter l'OOM sur un fichier texte de 50 Mo.
  static Future<String?> _findSnippet(File f, String patternLower) async {
    try {
      final stream = f
          .openRead()
          .transform(utf8.decoder)
          .transform(const LineSplitter());
      await for (final line in stream) {
        if (line.toLowerCase().contains(patternLower)) {
          return line.length > 200 ? '${line.substring(0, 200)}…' : line;
        }
      }
    } catch (e) {
      // Lecture impossible (encodage non-UTF8, perm, fichier modifié pendant
      // le scan) — on ignore ce fichier mais on log en debug pour diagnose.
      if (kDebugMode) debugPrint('global_search snippet ${f.path}: $e');
    }
    return null;
  }
}
