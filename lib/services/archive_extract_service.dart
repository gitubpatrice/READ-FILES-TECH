/// Extraction d'archives ZIP, **hors thread principal**.
///
/// **Pourquoi ce fichier existe.** L'extraction vivait dans
/// `zip_viewer_screen.dart`, dans une méthode privée d'un `State`. Deux
/// conséquences, l'une constatée sur appareil et l'autre gênante depuis le
/// début :
///
/// 1. **Elle bloquait l'interface.** Constaté sur Galaxy S9 (Android 10, 4 Go)
///    le 2026-08-09 : ouvrir une bombe zip de 306 Ko annonçant 1 Ko et
///    produisant 300 Mo, puis lancer « Extraire tout », déclenchait un ANR —
///    Android affichait « Application Not Responding ». La garde faisait son
///    travail (l'entrée était refusée, et le refus s'affichait), mais pour
///    atteindre le plafond de 200 Mo l'inflater devait d'abord produire 200 Mo
///    en mémoire, sur le thread de l'interface. Le même essai sur un S24
///    (Android 16, 8 Go) passait sans gel visible : c'est exactement le genre
///    de défaut qu'un seul appareil de test laisse passer.
/// 2. **Elle n'était pas testable.** Méthode privée d'un `State`, donc aucun
///    test unitaire ne couvrait le câblage — seulement `safeEntryBytes` en
///    dessous. Les commits du 2026-08-09 le reconnaissaient comme une limite
///    assumée ; ce fichier la lève.
///
/// Tout ici est **top-level et pur** : pas d'accès au `State`, pas de
/// `BuildContext`, rien qui capture `this`. C'est la condition pour passer par
/// `Isolate.run`, qui sérialise arguments et résultat.
library;

import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import '../utils/archive_safe.dart';

/// Issue d'une extraction complète.
class ArchiveExtractResult {
  /// Dossier où les fichiers ont été écrits.
  final String outDirPath;

  /// Entrées écrites avec succès.
  final int written;

  /// Entrées écartées : chemin dangereux, lien symbolique, ou contenu
  /// dépassant le plafond malgré ce que déclarait son en-tête.
  final int skipped;

  /// Octets réellement produits, tous fichiers confondus. Compté sur ce qui
  /// sort de l'inflater, jamais sur ce que l'en-tête annonce.
  final int bytesWritten;

  const ArchiveExtractResult({
    required this.outDirPath,
    required this.written,
    required this.skipped,
    required this.bytesWritten,
  });
}

/// Levée quand le total décompressé dépasse [maxTotalBytes].
class ArchiveTotalTooLargeException implements Exception {
  final int maxTotalBytes;
  const ArchiveTotalTooLargeException(this.maxTotalBytes);

  @override
  String toString() =>
      'Archive trop volumineuse : plus de '
      '${maxTotalBytes ~/ (1024 * 1024 * 1024)} Go une fois décompressée.';
}

/// Vérifie **textuellement** qu'un nom d'entrée ne sort pas de [baseDir].
///
/// Rejette : chemin vide, chemin absolu, `..`, `.`, lettre de lecteur Windows,
/// octet NUL. Rend le chemin reconstruit, ou `null` si l'entrée est refusée.
///
/// **Ne résout pas les liens symboliques** — il ne peut pas, les dossiers
/// n'existent pas encore à cet instant. C'est [extractArchive] qui s'en charge,
/// après création du parent et juste avant l'écriture. Les deux contrôles sont
/// nécessaires : celui-ci écarte l'entrée avant toute décompression, l'autre
/// couvre ce que le système de fichiers peut avoir sous les pieds.
String? safeJoin(String baseDir, String entryName) {
  final normalized = entryName.replaceAll('\\', '/');
  if (normalized.isEmpty || normalized.startsWith('/')) return null;
  final segments = normalized.split('/');
  if (segments.any((s) => s == '..' || s == '.')) return null;
  if (RegExp(r'^[a-zA-Z]:').hasMatch(normalized)) return null;
  if (normalized.contains('\x00')) return null;

  final candidate = '$baseDir/$normalized';
  // La comparaison porte sur des séparateurs normalisés. `File.absolute.path`
  // conserve les séparateurs qu'on lui a donnés : sous Windows, une base
  // rendue `J:\base` se comparait à un candidat `J:\base/a/b.txt`, et le
  // `startsWith` échouait — la fonction refusait alors des chemins parfaitement
  // légitimes. Le défaut ne se voyait pas en production (Android n'utilise que
  // `/`) mais il rendait la fonction intestable, ce qui revient à ne pas
  // pouvoir prouver qu'elle protège.
  if (!_within(File(baseDir).absolute.path, File(candidate).absolute.path)) {
    return null;
  }
  return candidate;
}

/// `true` si [child] est [parent] lui-même ou vit dessous.
bool _within(String parent, String child) {
  String norm(String p) {
    final s = p.replaceAll('\\', '/');
    return s.endsWith('/') ? s.substring(0, s.length - 1) : s;
  }

  final p = norm(parent);
  final c = norm(child);
  return c == p || c.startsWith('$p/');
}

/// Lance [extractArchive] dans un isolate.
///
/// **Cette fonction est top-level, et ce n'est pas un détail.** L'écran
/// appelait d'abord `Isolate.run` depuis une méthode d'instance :
///
/// ```dart
/// await Isolate.run(() => extractArchive(..., maxEntryBytes: _maxEntryBytes));
/// ```
///
/// `_maxEntryBytes` était une `static const` de la classe `State`. Suffisant,
/// pourtant, pour que la fermeture capture `this` — et avec lui l'élément, le
/// binding Flutter, et un `_AsyncCompleter` qui n'est pas transmissible. À
/// l'exécution, sur appareil :
///
/// ```
/// Invalid argument(s): Illegal argument in isolate message:
/// object is unsendable - Library:'dart:async' Class: _AsyncCompleter
///  <- Instance of 'WidgetsFlutterBinding'
///  <- Instance of 'StatefulElement'
/// ```
///
/// `flutter analyze` ne voyait rien, et les tests non plus : ils appelaient
/// [extractArchive] directement, jamais le câblage. L'extraction était
/// **entièrement cassée** et seul le Galaxy S9 l'a dit, le 2026-08-09.
///
/// Ici, il n'y a pas de `this` à capturer. Les paramètres sont recopiés dans
/// des locales avant la fermeture, qui ne voit donc que des primitives. Le
/// mode de panne devient structurellement impossible, au lieu d'être évité
/// par discipline.
Future<ArchiveExtractResult> extractArchiveIsolate({
  required String zipPath,
  required String outDirPath,
  required int maxEntryBytes,
  required int maxTotalBytes,
}) {
  final z = zipPath;
  final o = outDirPath;
  final me = maxEntryBytes;
  final mt = maxTotalBytes;
  return Isolate.run(
    () => extractArchive(
      zipPath: z,
      outDirPath: o,
      maxEntryBytes: me,
      maxTotalBytes: mt,
    ),
  );
}

/// Lance [extractSingleEntry] dans un isolate. Même raison d'être top-level
/// que [extractArchiveIsolate].
Future<String> extractSingleEntryIsolate({
  required String zipPath,
  required String entryName,
  required String outPath,
  required int maxEntryBytes,
}) {
  final z = zipPath;
  final e = entryName;
  final o = outPath;
  final m = maxEntryBytes;
  return Isolate.run(
    () => extractSingleEntry(
      zipPath: z,
      entryName: e,
      outPath: o,
      maxEntryBytes: m,
    ),
  );
}

/// Extrait toutes les entrées de [zipPath] dans [outDirPath].
///
/// Synchrone et pure. Passer par [extractArchiveIsolate] pour ne pas bloquer
/// le thread de l'interface.
///
/// Lève [ArchiveTotalTooLargeException] si le cumul dépasse [maxTotalBytes].
/// Les entrées individuelles refusées ne lèvent pas : elles sont comptées dans
/// `skipped`, et l'extraction continue.
ArchiveExtractResult extractArchive({
  required String zipPath,
  required String outDirPath,
  required int maxEntryBytes,
  required int maxTotalBytes,
}) {
  final archive = ZipDecoder().decodeBytes(File(zipPath).readAsBytesSync());

  final outDir = Directory(outDirPath)..createSync(recursive: true);
  // Résolu une fois : c'est la racine réelle à laquelle chaque parent créé
  // sera comparé, liens symboliques suivis.
  final resolvedBase = outDir.resolveSymbolicLinksSync();

  var written = 0;
  var skipped = 0;
  var total = 0;

  for (final entry in archive.files) {
    if (!entry.isFile) continue;

    // Anti zip-slip d'abord : inutile de dépenser le moindre octet à
    // décompresser une entrée qu'on refusera d'écrire de toute façon.
    final target = safeJoin(outDirPath, entry.name);
    if (target == null) {
      skipped++;
      continue;
    }

    // Le budget restant borne l'entrée courante. Sans cela, la dernière entrée
    // pouvait à elle seule dépasser le plafond cumulé, celui-ci n'étant
    // vérifié qu'avant de l'écrire.
    final remaining = maxTotalBytes - total;
    if (remaining <= 0) throw ArchiveTotalTooLargeException(maxTotalBytes);
    final cap = remaining < maxEntryBytes ? remaining : maxEntryBytes;

    // `entry.size` et `entry.content` sont tous deux à la merci de l'en-tête :
    // le premier annonce ce qu'il veut, le second décompresse sans borne.
    final Uint8List bytes;
    try {
      bytes = safeEntryBytes(entry, entry.name, cap);
    } on ArchiveTooLargeException {
      skipped++;
      continue;
    }

    final outFile = File(target);
    outFile.parent.createSync(recursive: true);

    // `safeJoin` ne juge que le texte. Si le dossier de destination contient
    // déjà un lien symbolique pointant ailleurs, un chemin textuellement
    // correct peut résoudre hors de `outDir`.
    final resolvedParent = outFile.parent.resolveSymbolicLinksSync();
    if (!_within(resolvedBase, resolvedParent)) {
      skipped++;
      continue;
    }
    // Et si la CIBLE elle-même est déjà un lien, son parent reste pourtant
    // dans `outDir` : `writeAsBytes` suivrait le lien.
    if (FileSystemEntity.isLinkSync(target)) {
      skipped++;
      continue;
    }

    outFile.writeAsBytesSync(bytes);
    // Compté sur ce qui a réellement été produit, jamais sur le déclaré.
    total += bytes.length;
    written++;
  }

  return ArchiveExtractResult(
    outDirPath: outDirPath,
    written: written,
    skipped: skipped,
    bytesWritten: total,
  );
}

/// Décompresse une entrée unique vers un fichier temporaire, bornée.
///
/// Même contrat isolate-safe que [extractArchive]. Rend le chemin écrit.
String extractSingleEntry({
  required String zipPath,
  required String entryName,
  required String outPath,
  required int maxEntryBytes,
}) {
  final archive = ZipDecoder().decodeBytes(File(zipPath).readAsBytesSync());
  final entry = archive.findFile(entryName);
  if (entry == null) {
    throw FileSystemException('Entrée introuvable dans l\'archive', entryName);
  }
  final bytes = safeEntryBytes(entry, entryName, maxEntryBytes);
  File(outPath).writeAsBytesSync(bytes);
  return outPath;
}
