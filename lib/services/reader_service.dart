import 'dart:convert';
import 'dart:io';
import 'package:archive/archive.dart';
import 'package:html/parser.dart' as html_parser;
import 'package:html/dom.dart' as dom;
import 'package:path/path.dart' as p;

/// Extraction de texte "Reader Mode" depuis HTML brut ou EPUB.
///
/// Approche : on prend le contenu de `<article>` ou `<main>` ou `<body>`,
/// on retire scripts/styles/iframes/forms, on garde paragraphes, titres et
/// listes. Sortie en blocs structurés pour rendu Flutter (sans WebView).
class ReaderBlock {
  final String type; // 'h1','h2','h3','p','li','quote'
  final String text;
  ReaderBlock(this.type, this.text);
}

class ReaderService {
  /// Convertit du HTML en blocs lecture.
  List<ReaderBlock> htmlToBlocks(String htmlSource) {
    final doc = html_parser.parse(htmlSource);
    // Préfère <article> > <main> > <body>
    dom.Element? root =
        doc.querySelector('article') ?? doc.querySelector('main') ?? doc.body;
    if (root == null) return [];
    // Retire éléments parasites
    for (final tag in const [
      'script',
      'style',
      'iframe',
      'form',
      'noscript',
      'nav',
      'aside',
      'footer',
      'header',
    ]) {
      for (final e in root.querySelectorAll(tag)) {
        e.remove();
      }
    }
    return _walk(root);
  }

  /// Lit un EPUB et retourne les chapitres (titre + blocs).
  /// EPUB = ZIP contenant un OPF qui liste les fichiers HTML/XHTML.
  /// Implémentation minimale : on lit `META-INF/container.xml` → chemin OPF →
  /// `<spine>` ordonne les chapitres → on lit chaque XHTML.
  Future<List<EpubChapter>> readEpub(File file) async {
    final bytes = await file.readAsBytes();
    final archive = ZipDecoder().decodeBytes(bytes);

    // 1. container.xml → trouve le rootfile (OPF)
    final container = archive.findFile('META-INF/container.xml');
    if (container == null)
      throw const FormatException('EPUB invalide : container.xml manquant');
    final containerXml = utf8.decode(
      container.content as List<int>,
      allowMalformed: true,
    );
    final opfMatch = RegExp(r'full-path="([^"]+)"').firstMatch(containerXml);
    if (opfMatch == null)
      throw const FormatException('EPUB invalide : OPF non trouvé');
    final opfPath = opfMatch.group(1)!;

    // 2. OPF → manifest (id → href) + spine (ordre des id)
    final opfFile = archive.findFile(opfPath);
    if (opfFile == null)
      throw const FormatException('EPUB invalide : OPF introuvable');
    final opfXml = utf8.decode(
      opfFile.content as List<int>,
      allowMalformed: true,
    );
    final basePath = p.dirname(opfPath);

    final manifest = <String, String>{};
    for (final m in RegExp(r'<item\s+([^>]+)/?>').allMatches(opfXml)) {
      final attrs = m.group(1)!;
      final id = RegExp(r'id="([^"]+)"').firstMatch(attrs)?.group(1);
      final href = RegExp(r'href="([^"]+)"').firstMatch(attrs)?.group(1);
      if (id != null && href != null) {
        manifest[id] = href;
      }
    }

    final spineIds = <String>[];
    for (final m in RegExp(r'<itemref\s+idref="([^"]+)"').allMatches(opfXml)) {
      spineIds.add(m.group(1)!);
    }

    // 3. Parcourt chaque chapitre
    final out = <EpubChapter>[];
    final basePrefix = basePath.isEmpty ? '' : '$basePath/';
    for (final id in spineIds) {
      final href = manifest[id];
      if (href == null) continue;
      // Garde-fou : un EPUB malveillant peut avoir un href avec `..` qui sort
      // du dossier OPF. p.normalize les résout — on rejette si on sort.
      final fullPath = p
          .normalize(p.join(basePath, href))
          .replaceAll('\\', '/');
      if (basePath.isNotEmpty &&
          !fullPath.startsWith(basePrefix) &&
          fullPath != basePath) {
        continue;
      }
      // Un href absolu (`/etc/passwd`) est aussi rejeté.
      if (href.startsWith('/') || href.contains('://')) continue;
      final entry = archive.findFile(fullPath);
      if (entry == null) continue;
      final xhtml = utf8.decode(
        entry.content as List<int>,
        allowMalformed: true,
      );
      final doc = html_parser.parse(xhtml);
      final root = doc.body;
      if (root == null) continue;
      for (final tag in const ['script', 'style']) {
        for (final e in root.querySelectorAll(tag)) {
          e.remove();
        }
      }
      final blocks = _walk(root);
      // Titre du chapitre : premier h1/h2 trouvé, sinon nom du fichier.
      final firstHeading = blocks.firstWhere(
        (b) => b.type == 'h1' || b.type == 'h2',
        orElse: () => ReaderBlock('p', p.basenameWithoutExtension(href)),
      );
      out.add(EpubChapter(title: firstHeading.text, blocks: blocks));
    }
    return out;
  }

  List<ReaderBlock> _walk(dom.Element root) {
    final out = <ReaderBlock>[];
    void visit(dom.Node n) {
      if (n is dom.Element) {
        final tag = n.localName?.toLowerCase() ?? '';
        switch (tag) {
          case 'h1':
            _addText(out, 'h1', n.text);
            return;
          case 'h2':
            _addText(out, 'h2', n.text);
            return;
          case 'h3':
          case 'h4':
            _addText(out, 'h3', n.text);
            return;
          case 'p':
            _addText(out, 'p', n.text);
            return;
          case 'blockquote':
            _addText(out, 'quote', n.text);
            return;
          case 'li':
            _addText(out, 'li', n.text);
            return;
          case 'br':
            return;
        }
      }
      for (final c in n.nodes) {
        visit(c);
      }
    }

    visit(root);
    return out;
  }

  void _addText(List<ReaderBlock> out, String type, String raw) {
    final clean = raw.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (clean.isNotEmpty) out.add(ReaderBlock(type, clean));
  }
}

class EpubChapter {
  final String title;
  final List<ReaderBlock> blocks;
  EpubChapter({required this.title, required this.blocks});
}
