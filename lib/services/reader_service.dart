import 'dart:convert';
import 'dart:io';
import 'package:archive/archive.dart';
import 'package:html/parser.dart' as html_parser;
import 'package:html/dom.dart' as dom;
import 'package:path/path.dart' as p;
import '../utils/file_caps.dart';

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
    final dom.Element? root =
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
    // F4 : cap EPUB anti-DoS OOM (fichier piégé multi-Go).
    final size = await file.length();
    if (size > FileCaps.epubFile) {
      throw const FormatException(
        'EPUB trop volumineux (max ${FileCaps.epubFile ~/ (1024 * 1024)} Mo).',
      );
    }
    final bytes = await file.readAsBytes();
    final archive = ZipDecoder().decodeBytes(bytes);

    // 1. container.xml → trouve le rootfile (OPF)
    final container = archive.findFile('META-INF/container.xml');
    if (container == null) {
      throw const FormatException('EPUB invalide : container.xml manquant');
    }
    final containerXml = utf8.decode(
      _entryBytes(container, 'META-INF/container.xml'),
      allowMalformed: true,
    );
    final opfMatch = RegExp(r'full-path="([^"]+)"').firstMatch(containerXml);
    if (opfMatch == null) {
      throw const FormatException('EPUB invalide : OPF non trouvé');
    }
    final opfPath = opfMatch.group(1)!;

    // 2. OPF → manifest (id → href) + spine (ordre des id)
    final opfFile = archive.findFile(opfPath);
    if (opfFile == null) {
      throw const FormatException('EPUB invalide : OPF introuvable');
    }
    final opfXml = utf8.decode(
      _entryBytes(opfFile, opfPath),
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
      // F4 : cap par chapitre (anti zip-bomb XHTML 2 Go). Passe par le même
      // helper que les deux autres entrées pour que la garde ne puisse plus
      // couvrir un seul site sur trois.
      final List<int> xhtmlBytes;
      try {
        xhtmlBytes = _entryBytes(entry, fullPath, max: FileCaps.epubChapter);
      } on FormatException {
        continue; // chapitre suspect : on saute, on n'annule pas le livre
      }
      final xhtml = utf8.decode(xhtmlBytes, allowMalformed: true);
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

  /// Lit le contenu décompressé d'une entrée d'archive **après** avoir vérifié
  /// sa taille annoncée.
  ///
  /// V-M5 (audit 2026-08-02) — `readEpub` accédait à `.content` sur trois
  /// entrées et n'en vérifiait la taille que sur une seule : les chapitres.
  /// `META-INF/container.xml` et le fichier OPF étaient lus sans aucune borne.
  /// Or `.content` déclenche la décompression : un EPUB de quelques centaines
  /// de kilo-octets dont le `container.xml` se décompresse en plusieurs Go
  /// (du zéro compressé atteint un ratio de ~1000:1) faisait tomber l'app par
  /// OOM à la simple ouverture, sous le cap de 100 Mo du fichier.
  ///
  /// La garde est passée dans un helper unique précisément pour qu'un
  /// quatrième site d'accès ne puisse plus l'oublier — c'est le motif
  /// « résolu ici, retombé là » qui a produit ce défaut.
  static List<int> _entryBytes(
    ArchiveFile entry,
    String label, {
    int max = FileCaps.zipEntryDecompressed,
  }) {
    if (entry.size > max) {
      throw FormatException(
        'Entrée « $label » suspecte : ${entry.size ~/ (1024 * 1024)} Mo '
        'décompressés (max ${max ~/ (1024 * 1024)} Mo).',
      );
    }
    return entry.content as List<int>;
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
