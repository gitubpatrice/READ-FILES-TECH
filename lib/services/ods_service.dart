/// Lecture des classeurs OpenDocument (`.ods`).
///
/// **Pourquoi ce fichier existe.** L'application route le `.ods` vers la
/// visionneuse de tableur depuis toujours, mais aucune bibliothèque employée ne
/// l'a jamais lu : `excel` 4.0.6 comme son fork `excel_community` refusent tout
/// ce qui n'est pas `.xlsx`. Le 2026-08-10, le message « Fichier illisible » a
/// été remplacé par un refus explicite ; ce fichier lève le refus.
///
/// Aucune dépendance nouvelle : un `.ods` est un ZIP contenant un `content.xml`
/// ODF, exactement comme le `.odt` que `text_extraction_service` sait déjà
/// parcourir. On réutilise son balayage linéaire et la garde anti-bombe de
/// `archive_safe`.
///
/// Tout ici est **top-level et pur** : pas de `State`, pas de `BuildContext`,
/// rien qui capture `this`. C'est la condition pour passer par `Isolate.run` —
/// une fermeture qui capturait une constante de classe avait entièrement cassé
/// l'extraction d'archive le 2026-08-09.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:archive/archive.dart';

import '../utils/archive_safe.dart';
import '../utils/file_caps.dart';
import 'text_extraction_service.dart';

/// Issue d'une lecture de classeur : exactement un de [sheets] / [error].
class OdsReadResult {
  /// Feuilles dans l'ordre du document : nom → lignes[colonnes], texte résolu.
  final Map<String, List<List<String>>>? sheets;

  /// Message destiné à l'utilisateur, déjà rédigé.
  final String? error;

  const OdsReadResult({this.sheets, this.error})
    : assert(
        (sheets == null) != (error == null),
        'exactement un de sheets / error',
      );
}

/// Plafond du nombre de lignes retenues par feuille.
const kOdsMaxRows = 5000;

/// Plafond du nombre de colonnes retenues par ligne.
///
/// Aligné sur les bornes de `xlsx_viewer_screen`, pour que les deux formats se
/// comportent pareil : les vrais classeurs métier les dépassent rarement, et
/// au-delà l'affichage n'est de toute façon plus exploitable.
const kOdsMaxCols = 200;

/// Décompose le `content.xml` d'un `.ods` en feuilles.
///
/// Pure et synchrone : c'est la fonction que les tests attaquent directement.
Map<String, List<List<String>>> odsXmlToSheets(
  String xml, {
  int maxRows = kOdsMaxRows,
  int maxCols = kOdsMaxCols,
}) {
  final sheets = <String, List<List<String>>>{};

  for (final table in tagElements(xml, const ['table:table'])) {
    if (table.selfClosing) continue;
    final rawName = table.attribute('table:name');
    final name = rawName == null || rawName.isEmpty
        ? 'Feuille ${sheets.length + 1}'
        : decodeXmlEntities(rawName);

    final rows = <List<String>>[];
    for (final row in tagElements(table.inner, const ['table:table-row'])) {
      if (rows.length >= maxRows) break;

      final cells = _rowCells(row.inner, maxCols);

      // ODF compresse les lignes identiques consécutives. Une feuille se
      // termine typiquement par une ligne vide répétée un million de fois,
      // pour matérialiser la grille : la déplier telle quelle produirait un
      // million de lignes vides et ferait tomber l'application. On la déplie
      // donc, mais bornée — et jamais si elle est vide, auquel cas elle
      // n'apporte rien.
      final repeat = _repeatCount(row, 'table:number-rows-repeated');
      final isBlank = cells.every((c) => c.isEmpty);
      final times = isBlank ? 1 : repeat;

      for (var i = 0; i < times && rows.length < maxRows; i++) {
        rows.add(cells);
      }
    }

    // Les lignes vides de queue ne portent aucune information et fausseraient
    // le compteur affiché à l'utilisateur.
    while (rows.isNotEmpty && rows.last.every((c) => c.isEmpty)) {
      rows.removeLast();
    }

    // Un `.ods` peut nommer deux feuilles pareil ; sans cela l'une écraserait
    // silencieusement l'autre.
    var unique = name;
    var suffix = 2;
    while (sheets.containsKey(unique)) {
      unique = '$name ($suffix)';
      suffix++;
    }
    sheets[unique] = rows;
  }

  return sheets;
}

/// Cellules d'une ligne, répétitions dépliées et bornées à [maxCols].
List<String> _rowCells(String rowXml, int maxCols) {
  final cells = <String>[];
  for (final cell in tagElements(rowXml, const [
    'table:table-cell',
    'table:covered-table-cell',
  ])) {
    if (cells.length >= maxCols) break;

    final text = cell.selfClosing ? '' : _cellText(cell);
    final repeat = _repeatCount(cell, 'table:number-columns-repeated');

    // Même raison que pour les lignes : la dernière cellule d'une ligne porte
    // souvent une répétition énorme pour compléter la grille. Vide, elle
    // n'apporte rien ; pleine, elle est dépliée dans la limite du plafond.
    final times = text.isEmpty ? 1 : repeat;
    for (var i = 0; i < times && cells.length < maxCols; i++) {
      cells.add(text);
    }
  }

  while (cells.isNotEmpty && cells.last.isEmpty) {
    cells.removeLast();
  }
  return cells;
}

/// Texte d'une cellule : ses paragraphes joints par un saut de ligne.
///
/// La valeur affichée est celle que l'utilisateur voit dans son tableur, et non
/// `office:value` : pour une date ou un pourcentage, l'attribut porte la valeur
/// brute (`0.42`) là où le paragraphe porte la valeur formatée (`42 %`).
String _cellText(XmlElement cell) {
  final parts = <String>[];
  for (final p in tagContents(cell.inner, const ['text:p'])) {
    final clean = p.replaceAll(RegExp(r'<[^>]+>'), '');
    parts.add(decodeXmlEntities(clean));
  }
  return parts.join('\n').trim();
}

/// Lit `table:number-…-repeated`, borné et défensif.
///
/// **C'est un vecteur de bombe, pas un détail de format.** Rien n'empêche un
/// fichier de quelques kilo-octets de déclarer
/// `table:number-columns-repeated="1000000000"` : déplier naïvement allouerait
/// un milliard de chaînes. Le plafond de sortie borne déjà les boucles, mais
/// une valeur absurde est ici ramenée à 1 plutôt que propagée.
int _repeatCount(XmlElement e, String attribute) {
  final raw = e.attribute(attribute);
  if (raw == null) return 1;
  final n = int.tryParse(raw);
  if (n == null || n < 1) return 1;
  // Au-delà du plafond d'affichage, la valeur ne peut de toute façon rien
  // produire de visible : on la tronque au lieu de faire confiance au fichier.
  const hardCap = kOdsMaxRows > kOdsMaxCols ? kOdsMaxRows : kOdsMaxCols;
  return n > hardCap ? hardCap : n;
}

/// Lit un `.ods` déjà en mémoire.
///
/// Même contrat que `extractOdtText` : exactement un de `sheets` / `error`.
OdsReadResult readOdsBytes(List<int> bytes) {
  // `archive` 4 ne lève plus sur des octets qui ne sont pas une archive : il
  // rend une archive vide. Sans ce contrôle, un fichier corrompu produirait
  // « content.xml introuvable », qui accuse le contenu au lieu du format.
  if (!looksLikeZip(bytes)) {
    return const OdsReadResult(
      error: 'Le fichier n\'est pas un .ods valide (archive ZIP illisible).',
    );
  }

  Archive archive;
  try {
    archive = ZipDecoder().decodeBytes(bytes);
  } catch (_) {
    return const OdsReadResult(
      error: 'Le fichier n\'est pas un .ods valide (archive ZIP illisible).',
    );
  }

  if (archive.files.isEmpty && zipDeclaresEntries(bytes)) {
    return const OdsReadResult(
      error: "Le fichier n'est pas un .ods valide (archive corrompue).",
    );
  }

  final entry = archive.findFile('content.xml');
  if (entry == null) {
    return const OdsReadResult(
      error: '`content.xml` introuvable dans l\'archive — fichier corrompu.',
    );
  }

  final List<int> raw;
  try {
    // `entry.size` vient de l'en-tête du ZIP et se falsifie : `safeEntryBytes`
    // compte ce qui sort réellement de l'inflater.
    raw = safeEntryBytes(entry, 'content.xml', FileCaps.zipEntryDecompressed);
  } on ArchiveTooLargeException catch (e) {
    return OdsReadResult(error: e.toString());
  }

  final sheets = odsXmlToSheets(utf8.decode(raw, allowMalformed: true));
  if (sheets.isEmpty) {
    return const OdsReadResult(
      error: 'Aucune feuille trouvée — le classeur est vide ou illisible.',
    );
  }
  return OdsReadResult(sheets: sheets);
}

/// Lit [path] dans un isolate.
///
/// Top-level, et les paramètres sont recopiés dans des locales avant la
/// fermeture : celle-ci ne voit que des primitives, donc il n'y a pas de `this`
/// à capturer. Voir `archive_extract_service.dart` pour ce que coûte l'oubli.
Future<OdsReadResult> readOdsFileIsolate(String path) {
  final p = path;
  return Isolate.run(() => readOdsBytes(File(p).readAsBytesSync()));
}
