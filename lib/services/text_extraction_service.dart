/// Extraction de texte depuis PDF et DOCX, prête à être exécutée hors UI
/// thread via `compute()` (`package:flutter/foundation.dart`).
///
/// Toutes les fonctions de ce fichier sont **top-level** et **pures** : elles
/// ne touchent ni au state d'écran, ni au stockage, ni à la base. C'est la
/// condition pour qu'elles puissent être passées à `compute()`, qui sérialise
/// args et résultat entre isolates.
///
/// L'écran qui les appelle s'occupe du picker, de la lecture disque, de
/// l'écriture du `.txt` final et de l'affichage des erreurs. Ici on ne fait
/// que **transformer des bytes/du XML en texte**, c'est tout.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:syncfusion_flutter_pdf/pdf.dart';

/// Décompose le XML d'un `word/document.xml` (.docx) en texte brut.
///
/// Rapide même sur les très gros documents : la regex est en `dotAll` linéaire.
/// Sentinel `` (SOH) pour marquer les fins de paragraphe avant le split,
/// car `split('')` (chaîne vide) découperait par caractère individuel.
String docxXmlToPlainText(String xml) {
  var s = xml;
  // Sauts de ligne et tabulations (variantes auto-fermantes). On les
  // convertit en `<w:t>` synthétiques plutôt qu'en caractère brut : le
  // pipeline d'extraction ci-dessous ne garde que le contenu des `<w:t>`,
  // donc un `\n` injecté hors de ces balises serait perdu.
  s = s.replaceAll(RegExp(r'<w:br\b[^/>]*/?>'), '<w:t>\n</w:t>');
  s = s.replaceAll(RegExp(r'<w:tab\b[^/>]*/?>'), '<w:t>\t</w:t>');

  // Sentinel ASCII unique pour matérialiser la fin de paragraphe avant split.
  const sentinel = '';
  s = s.replaceAll(RegExp(r'</w:p>'), sentinel);

  final runRe = RegExp(r'<w:t(?:\s[^>]*)?>(.*?)</w:t>', dotAll: true);
  final paragraphs = s.split(sentinel);
  final out = StringBuffer();
  for (final p in paragraphs) {
    final pieces = runRe
        .allMatches(p)
        .map((m) => decodeXmlEntities(m.group(1) ?? ''))
        .join();
    out
      ..write(pieces)
      ..writeln();
  }
  return out.toString().replaceAll(RegExp(r'\n{3,}'), '\n\n').trimRight();
}

/// Décode les entités XML standard et numériques.
///
/// Gère : `&lt; &gt; &quot; &apos; &amp;` + `&#NNN; &#xHH;`. L'ordre importe :
/// `&amp;` est traité **en dernier** pour préserver `&amp;lt;` → `&lt;`.
String decodeXmlEntities(String s) {
  return s
      .replaceAllMapped(RegExp(r'&#x([0-9a-fA-F]+);'), (m) {
        final code = int.tryParse(m.group(1)!, radix: 16);
        return (code != null && code >= 0 && code <= 0x10FFFF)
            ? String.fromCharCode(code)
            : m.group(0)!;
      })
      .replaceAllMapped(RegExp(r'&#(\d+);'), (m) {
        final code = int.tryParse(m.group(1)!);
        return (code != null && code >= 0 && code <= 0x10FFFF)
            ? String.fromCharCode(code)
            : m.group(0)!;
      })
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll('&apos;', "'")
      .replaceAll('&amp;', '&');
}

/// Extrait le texte d'un .docx (ZIP) en mémoire.
///
/// Renvoie un [DocxExtractResult] : soit `text` non null si succès, soit
/// `error` avec un message destiné à être affiché tel quel.
DocxExtractResult extractDocxText(Uint8List bytes) {
  // .doc binaire (signature OLE) : refus explicite.
  if (bytes.length >= 4 &&
      bytes[0] == 0xD0 &&
      bytes[1] == 0xCF &&
      bytes[2] == 0x11 &&
      bytes[3] == 0xE0) {
    return const DocxExtractResult(
      error:
          'Format .doc (Word 97-2003) non supporté. Réenregistrez en .docx '
          'depuis Word ou LibreOffice.',
    );
  }

  Archive archive;
  try {
    archive = ZipDecoder().decodeBytes(bytes);
  } catch (_) {
    return const DocxExtractResult(
      error: 'Le fichier n\'est pas un .docx valide (archive ZIP illisible).',
    );
  }

  final entry = archive.findFile('word/document.xml');
  if (entry == null) {
    return const DocxExtractResult(
      error:
          '`word/document.xml` introuvable dans l\'archive — fichier corrompu '
          'ou chiffré.',
    );
  }
  if (entry.size > 200 * 1024 * 1024) {
    return DocxExtractResult(
      error:
          'Le contenu décompressé est trop volumineux '
          '(${entry.size ~/ 1024 ~/ 1024} Mo). Fichier suspect.',
    );
  }

  final xml = utf8.decode(entry.content as List<int>, allowMalformed: true);
  final extracted = docxXmlToPlainText(xml);
  if (extracted.trim().isEmpty) {
    return const DocxExtractResult(
      error: 'Le document semble vide (aucun texte trouvé).',
    );
  }
  return DocxExtractResult(text: extracted);
}

/// Extrait le texte sélectionnable d'un PDF en mémoire.
///
/// Renvoie un [PdfExtractResult] avec le texte assemblé (séparé par
/// `--- Page N ---`) ou un `error` si :
/// - PDF illisible/chiffré
/// - Aucun texte significatif détecté (heuristique "scanné")
PdfExtractResult extractPdfText(Uint8List bytes) {
  PdfDocument? doc;
  try {
    doc = PdfDocument(inputBytes: bytes);
  } catch (_) {
    return const PdfExtractResult(
      error: 'PDF illisible (peut-être chiffré ou corrompu).',
    );
  }

  final buf = StringBuffer();
  try {
    final extractor = PdfTextExtractor(doc);
    final pageCount = doc.pages.count;
    for (var i = 0; i < pageCount; i++) {
      String pageText;
      try {
        pageText = extractor.extractText(startPageIndex: i, endPageIndex: i);
      } catch (_) {
        pageText = '';
      }
      if (i > 0) buf.writeln();
      buf
        ..writeln('--- Page ${i + 1} ---')
        ..write(pageText.trimRight());
    }
  } finally {
    doc.dispose();
  }

  final extracted = buf.toString().trim();
  // Heuristique "PDF scanné" recalibrée : on retire d'abord nos propres
  // marqueurs `--- Page N ---` pour ne pas les compter, puis seuil absolu.
  final usefulChars = extracted
      .replaceAll(RegExp(r'---\s*Page\s+\d+\s*---'), '')
      .replaceAll(RegExp(r'\s'), '')
      .length;
  if (usefulChars < 30) {
    return const PdfExtractResult(
      error:
          'Aucun texte sélectionnable détecté. Si le PDF est scanné, '
          'utilisez d\'abord l\'outil OCR.',
    );
  }
  return PdfExtractResult(text: extracted);
}

/// Résultat d'une extraction PDF — soit `text`, soit `error`, jamais les deux.
class PdfExtractResult {
  const PdfExtractResult({this.text, this.error})
    : assert(
        (text == null) != (error == null),
        'Exactement un de text/error doit être non null.',
      );

  final String? text;
  final String? error;
}

/// Résultat d'une extraction DOCX — soit `text`, soit `error`, jamais les deux.
class DocxExtractResult {
  const DocxExtractResult({this.text, this.error})
    : assert(
        (text == null) != (error == null),
        'Exactement un de text/error doit être non null.',
      );

  final String? text;
  final String? error;
}
