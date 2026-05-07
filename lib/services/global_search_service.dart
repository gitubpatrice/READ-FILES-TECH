import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

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
    _receive = ReceivePort();
    final cancelPort = ReceivePort();
    _cancelPort = cancelPort.sendPort;
    Isolate.spawn<_StartArgs>(
          _entry,
          _StartArgs(_receive!.sendPort, cancelPort.sendPort, q),
        )
        .then((iso) {
          _isolate = iso;
        })
        .catchError((e) {
          controller.addError(e);
          controller.close();
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

  void cancel() {
    try {
      _cancelPort?.send('cancel');
    } catch (_) {}
    _cleanup();
  }

  void _cleanup() {
    _isolate?.kill(priority: Isolate.immediate);
    _isolate = null;
    _receive?.close();
    _receive = null;
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
      await for (final entity in root.list(
        recursive: true,
        followLinks: false,
      )) {
        if (cancelled || hits >= q.maxResults) break;
        if (entity is! File) continue;
        final name = entity.path.split(RegExp(r'[/\\]')).last;
        if (name.startsWith('.')) continue; // cache

        final lower = name.toLowerCase();
        final ext = lower.contains('.') ? lower.split('.').last : '';
        if (q.extensions.isNotEmpty && !q.extensions.contains(ext)) continue;

        scanned++;
        if (scanned % 200 == 0) {
          out.send(_Msg('progress', scanned));
        }

        // Filtre nom (si défini)
        final nameMatches = namePat == null || lower.contains(namePat);
        if (!nameMatches && contentPat == null) continue;

        // Filtre contenu (uniquement si demandé ET extension texte ET taille OK)
        String? snippet;
        if (contentPat != null) {
          if (!_textExts.contains(ext)) {
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
