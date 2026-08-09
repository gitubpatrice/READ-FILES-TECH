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

import '../utils/archive_safe.dart';
import '../utils/file_caps.dart';
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
  // La borne portait sur `entry.size`, une valeur lue dans l'en-tête du ZIP
  // et donc choisie par l'attaquant. `safeEntryBytes` compte les octets
  // réellement produits. La valeur en dur (200 Mo) rejoint au passage
  // `FileCaps.zipEntryDecompressed`, dont elle était une copie (C-C12).
  final List<int> raw;
  try {
    raw = safeEntryBytes(
      entry,
      'word/document.xml',
      FileCaps.zipEntryDecompressed,
    );
  } on ArchiveTooLargeException catch (e) {
    return DocxExtractResult(error: e.toString());
  }

  final xml = utf8.decode(raw, allowMalformed: true);
  final extracted = docxXmlToPlainText(xml);
  if (extracted.trim().isEmpty) {
    return const DocxExtractResult(
      error: 'Le document semble vide (aucun texte trouvé).',
    );
  }
  return DocxExtractResult(text: extracted);
}

/// Décompose le `content.xml` d'un OpenDocument (.odt / .odp) en texte brut.
///
/// Traite `<text:p>` (paragraphes) **et** `<text:h>` (titres), dans l'ordre du
/// document. La version qui vivait dans `docx_viewer_screen.dart` ne prenait
/// que `text:p` : un document structuré en titres perdait tous ses titres, et
/// un document qui n'en contenait que — un plan, un sommaire — ressortait
/// entièrement vide.
///
/// Elle ignorait aussi les entités numériques, si bien qu'un `.odt` produit par
/// LibreOffice affichait `&#233;` là où il fallait lire « é ».
String odtXmlToPlainText(String xml) {
  // `<text:line-break/>` et `<text:tab/>` sont des balises, pas du texte : le
  // nettoyage ci-dessous les effacerait purement et simplement. Converties en
  // amont, comme le fait déjà `docxXmlToPlainText` pour `<w:br>` / `<w:tab>`.
  var s = xml.replaceAll(RegExp(r'<text:line-break\b[^>]*/?>'), '\n');
  s = s.replaceAll(RegExp(r'<text:tab\b[^>]*/?>'), '\t');

  final buffer = StringBuffer();
  final reg = RegExp(r'<text:(p|h)(?:\s[^>]*)?>(.*?)</text:\1>', dotAll: true);
  for (final m in reg.allMatches(s)) {
    final inner = m.group(2) ?? '';
    // Les balises internes restantes (mise en forme, annotations, signets)
    // sont retirées ; seul leur contenu textuel compte.
    final clean = inner.replaceAll(RegExp(r'<[^>]+>'), '');
    // ODF encode aussi ses sauts de ligne en `&#xD;` (retour chariot). Le
    // décodage générique rend un `\r`, qu'on normalise ici plutôt que de
    // renoncer aux entités numériques pour ce cas particulier.
    final decoded = decodeXmlEntities(
      clean,
    ).replaceAll('\r\n', '\n').replaceAll('\r', '\n');
    if (decoded.trim().isNotEmpty) buffer.writeln(decoded);
  }
  return buffer.toString().trim();
}

/// Extrait le texte d'un `.odt` / `.odp` (ZIP) en mémoire.
///
/// Même contrat que [extractDocxText] : exactement un de `text` / `error`.
DocxExtractResult extractOdtText(Uint8List bytes) {
  Archive archive;
  try {
    archive = ZipDecoder().decodeBytes(bytes);
  } catch (_) {
    return const DocxExtractResult(
      error: 'Le fichier n\'est pas un OpenDocument valide (ZIP illisible).',
    );
  }
  final entry = archive.findFile('content.xml');
  if (entry == null) {
    return const DocxExtractResult(
      error: '`content.xml` introuvable dans l\'archive — fichier corrompu.',
    );
  }
  // Même raison que pour `.docx` : `entry.size` vient de l'en-tête du ZIP et
  // se falsifie. `safeEntryBytes` compte ce qui sort réellement.
  final List<int> raw;
  try {
    raw = safeEntryBytes(entry, 'content.xml', FileCaps.zipEntryDecompressed);
  } on ArchiveTooLargeException catch (e) {
    return DocxExtractResult(error: e.toString());
  }
  final text = odtXmlToPlainText(utf8.decode(raw, allowMalformed: true));
  if (text.trim().isEmpty) {
    return const DocxExtractResult(
      error: 'Le document semble vide (aucun texte trouvé).',
    );
  }
  return DocxExtractResult(text: text);
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
