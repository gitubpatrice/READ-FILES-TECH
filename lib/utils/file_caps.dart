import 'dart:io';

/// Caps de taille appliqués aux viewers et outils, factorisés ici pour
/// homogénéité.
///
/// Justification : un fichier piégé (texte/binaire mal classé, zip-bomb
/// déguisé en docx/xlsx, EPUB chapitre 2 Go) peut crasher l'app par OOM
/// sur low-end (Redmi 9C 3GB). Ces caps refusent en amont avant tout
/// `readAsBytes` / `readAsString`.
abstract final class FileCaps {
  FileCaps._();

  /// Texte (txt, log, code source standalone). 50 MiB suffit pour 99% des
  /// usages légitimes ; au-delà = quasi systématiquement fichier piégé.
  static const int textViewer = 50 * 1024 * 1024;

  /// Documents zippés (DOCX, ODT, ODP). 50 MiB sur le fichier source ;
  /// l'entry XML décompressé est cap séparément à `zipEntryDecompressed`.
  static const int docZipped = 50 * 1024 * 1024;

  /// Tableurs (XLSX, ODS). 50 MiB. Au-delà, l'utilisateur pro doit
  /// utiliser un vrai tableur.
  static const int spreadsheet = 50 * 1024 * 1024;

  /// EPUB. 100 MiB sur le fichier ; cap par chapitre 50 MiB.
  static const int epubFile = 100 * 1024 * 1024;
  static const int epubChapter = 50 * 1024 * 1024;

  /// Entry décompressée d'un ZIP / DOCX / ODT (anti zip-bomb XML).
  static const int zipEntryDecompressed = 200 * 1024 * 1024;

  /// CSV (parsing main thread = freeze UI > 100 Mo). Cap strict.
  static const int csvFile = 50 * 1024 * 1024;

  /// Image source pour outils (compress, convert, exif). 100 MiB raw.
  /// Bornes additionnelles sur dimensions via `ImageBounds`.
  static const int imageFile = 100 * 1024 * 1024;

  /// Total cumulé pour conversion images→PDF (anti épuisement RAM).
  static const int imagesToPdfTotal = 200 * 1024 * 1024;

  /// Backup .rftvault (restore). Borné pour éviter OOM ; un coffre légitime
  /// dépasse rarement 100 Mo.
  static const int vaultBackup = 100 * 1024 * 1024;

  /// v2.13.2 (#5) — viewers ZIP / HTML : caps centralisés ici au lieu de
  /// constants inline (anti-divergence si un autre écran ouvre du ZIP/HTML).
  ///
  /// ZIP viewer : 500 Mo (archives système, logs zippés, projets).
  static const int zipViewer = 500 * 1024 * 1024;

  /// HTML viewer : 20 Mo (page web standalone ; au-delà = quasi-systématiquement
  /// dump piégé). WebView Android cale au-delà sur low-end.
  static const int htmlViewer = 20 * 1024 * 1024;
}

/// Vérifie qu'un fichier ne dépasse pas un cap. Retourne `null` si OK,
/// sinon un message d'erreur lisible (à afficher en UI).
///
/// Usage :
/// ```dart
/// final err = await checkFileCap(file, FileCaps.textViewer);
/// if (err != null) { showErrorSnack(context, err); return; }
/// ```
Future<String?> checkFileCap(File f, int maxBytes) async {
  final size = await f.length();
  if (size > maxBytes) {
    final mb = (maxBytes / (1024 * 1024)).round();
    return 'Fichier trop volumineux (max $mb Mo).';
  }
  return null;
}

/// Variante synchrone (existsSync + lengthSync) pour les chemins critiques
/// déjà sync.
String? checkFileCapSync(File f, int maxBytes) {
  final size = f.lengthSync();
  if (size > maxBytes) {
    final mb = (maxBytes / (1024 * 1024)).round();
    return 'Fichier trop volumineux (max $mb Mo).';
  }
  return null;
}
