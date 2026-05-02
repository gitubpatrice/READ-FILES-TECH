import 'dart:io';
import 'dart:typed_data';
import 'package:archive/archive.dart';
import 'package:csv/csv.dart';
import 'package:excel/excel.dart' as xls;
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;
import 'package:share_plus/share_plus.dart';
import 'package:syncfusion_flutter_pdf/pdf.dart';
import '../../services/output_storage_service.dart';
import '../../widgets/cloud_share_row.dart';
import '../../widgets/rft_picker_screen.dart';

class ConvertScreen extends StatefulWidget {
  const ConvertScreen({super.key});

  @override
  State<ConvertScreen> createState() => _ConvertScreenState();
}

class _ConvertScreenState extends State<ConvertScreen> {
  final _storage = OutputStorageService();
  bool _busy = false;
  String? _status;
  String? _lastPath;

  /// Réserve un fichier persistant dans `Files Tech/Conversions/`.
  /// Tous les jobs de conversion passent par cette méthode → cohérence garantie.
  Future<File> _reserve(String suggested, String ext) => _storage.reserveFile(
    category: OutputCategory.conversions,
    suggestedName: suggested,
    extension: ext,
  );

  Future<void> _run(Future<File?> Function() job) async {
    setState(() {
      _busy = true;
      _status = null;
      _lastPath = null;
    });
    try {
      final out = await job();
      if (!mounted) return;
      if (out == null) {
        setState(() {
          _busy = false;
          _status = 'Annulé';
        });
        return;
      }
      final autoShare = await _storage.getAutoShare();
      if (!mounted) return;
      setState(() {
        _busy = false;
        _lastPath = out.path;
        _status = 'Sauvegardé : ${out.path.split(RegExp(r'[/\\]')).last}';
      });
      if (autoShare) {
        await Share.shareXFiles([XFile(out.path)]);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _status = 'Erreur : $e';
      });
    }
  }

  // ── Images → PDF ────────────────────────────────────────────────────────────
  Future<File?> _imagesToPdf() async {
    final res = await FilePicker.platform.pickFiles(
      type: FileType.image,
      allowMultiple: true,
    );
    if (res == null || res.files.isEmpty) return null;
    final pdf = PdfDocument();
    for (final f in res.files) {
      if (f.path == null) continue;
      final bytes = await File(f.path!).readAsBytes();
      final page = pdf.pages.add();
      final image = PdfBitmap(bytes);
      final pageSize = page.getClientSize();
      // Ratio fit
      final w = image.width.toDouble();
      final h = image.height.toDouble();
      final scale = (pageSize.width / w < pageSize.height / h)
          ? pageSize.width / w
          : pageSize.height / h;
      final dw = w * scale;
      final dh = h * scale;
      final dx = (pageSize.width - dw) / 2;
      final dy = (pageSize.height - dh) / 2;
      page.graphics.drawImage(image, Rect.fromLTWH(dx, dy, dw, dh));
    }
    final out = await _reserve('images', 'pdf');
    await out.writeAsBytes(await pdf.save());
    pdf.dispose();
    return out;
  }

  // ── CSV → XLSX ──────────────────────────────────────────────────────────────
  Future<File?> _csvToXlsx() async {
    final path = await RftPickerScreen.pickOne(
      context,
      title: 'Choisir un CSV',
      extensions: const {'csv'},
    );
    if (path == null) return null;
    final raw = await File(path).readAsString();
    final rows = const CsvToListConverter(
      eol: '\n',
      shouldParseNumbers: false,
    ).convert(raw);
    final excel = xls.Excel.createExcel();
    final sheet = excel['Sheet1'];
    for (var r = 0; r < rows.length; r++) {
      for (var c = 0; c < rows[r].length; c++) {
        sheet
            .cell(xls.CellIndex.indexByColumnRow(columnIndex: c, rowIndex: r))
            .value = xls.TextCellValue(
          rows[r][c].toString(),
        );
      }
    }
    final bytes = excel.save();
    if (bytes == null) throw 'Échec génération XLSX';
    final base = path
        .split(RegExp(r'[/\\]'))
        .last
        .replaceAll(RegExp(r'\.csv$', caseSensitive: false), '');
    final out = await _reserve(base, 'xlsx');
    await out.writeAsBytes(bytes);
    return out;
  }

  // ── Texte (TXT/MD) → PDF ────────────────────────────────────────────────────
  Future<File?> _textToPdf() async {
    final path = await RftPickerScreen.pickOne(
      context,
      title: 'Choisir un fichier texte',
      extensions: const {'txt', 'md'},
    );
    if (path == null) return null;
    final text = await File(path).readAsString();
    final name = path.split(RegExp(r'[/\\]')).last;
    return _generateTextPdf(text, name);
  }

  Future<File> _generateTextPdf(String text, String sourceName) async {
    final pdf = PdfDocument();
    final font = PdfStandardFont(PdfFontFamily.helvetica, 11);
    final pageFormat = pdf.pageSettings;
    pageFormat.margins.all = 36;
    final layoutFormat = PdfLayoutFormat(layoutType: PdfLayoutType.paginate);
    final element = PdfTextElement(text: text, font: font);
    element.draw(
      page: pdf.pages.add(),
      bounds: Rect.fromLTWH(
        0,
        0,
        pdf.pages[0].getClientSize().width,
        pdf.pages[0].getClientSize().height,
      ),
      format: layoutFormat,
    );
    final base = sourceName.replaceAll(RegExp(r'\.[^.]+$'), '');
    final out = await _reserve(base, 'pdf');
    await out.writeAsBytes(await pdf.save());
    pdf.dispose();
    return out;
  }

  // ── PDF → TXT ───────────────────────────────────────────────────────────────
  ///
  /// Extrait le texte sélectionnable d'un PDF page par page via le moteur
  /// Syncfusion (déjà utilisé pour Images → PDF / Texte → PDF). Conserve la
  /// pagination en intercalant `\n\n--- Page N ---\n\n` entre pages, ce qui
  /// reste lisible en .txt et permet à un `Ctrl+F` de retomber sur la bonne
  /// page si l'utilisateur recompare avec le PDF source.
  ///
  /// Limites connues (à signaler à l'utilisateur en cas d'échec) :
  /// - Les PDF **scannés sans OCR** ne contiennent pas de texte sélectionnable
  ///   → utiliser l'outil OCR de RFT pour ce cas.
  /// - Les PDF **chiffrés** (mot de passe) lèvent une exception du SDK.
  Future<File?> _pdfToText() async {
    final path = await RftPickerScreen.pickOne(
      context,
      title: 'Choisir un PDF',
      extensions: const {'pdf'},
    );
    if (path == null) return null;

    final src = File(path);
    final size = await src.length();
    // Garde-fou défensif : un .pdf de plusieurs centaines de Mo sature la RAM
    // sur un device d'entrée de gamme. On laisse passer 100 Mo, au-delà on
    // refuse plutôt que de crasher.
    if (size > 100 * 1024 * 1024) {
      throw 'Fichier trop volumineux (${(size / 1024 / 1024).toStringAsFixed(0)} Mo). '
          'Maximum 100 Mo.';
    }

    final bytes = await src.readAsBytes();
    PdfDocument? doc;
    try {
      doc = PdfDocument(inputBytes: bytes);
    } catch (e) {
      throw 'PDF illisible (peut-être chiffré ou corrompu).';
    }

    final extractor = PdfTextExtractor(doc);
    final buf = StringBuffer();
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
    doc.dispose();

    final extracted = buf.toString().trim();
    if (extracted.isEmpty ||
        extracted.replaceAll(RegExp(r'[\s\n\-]'), '').length < pageCount * 5) {
      // Heuristique : si on n'a quasiment rien sorti à part les en-têtes
      // de page, c'est probablement un PDF scanné. On le dit clairement.
      throw 'Aucun texte sélectionnable détecté. Si le PDF est scanné, '
          'utilisez d\'abord l\'outil OCR.';
    }

    final base = path
        .split(RegExp(r'[/\\]'))
        .last
        .replaceAll(RegExp(r'\.[^.]+$'), '');
    final out = await _reserve(base, 'txt');
    await out.writeAsString(extracted);
    return out;
  }

  // ── DOCX → TXT ──────────────────────────────────────────────────────────────
  ///
  /// Un fichier `.docx` est une archive ZIP qui contient `word/document.xml`.
  /// On extrait ce XML, puis on convertit chaque balise structurelle Word en
  /// texte brut :
  /// - `<w:p>` (paragraphe) → 2 sauts de ligne en sortie
  /// - `<w:br/>` (saut de ligne dur) → 1 saut de ligne
  /// - `<w:tab/>` (tabulation) → \t
  /// - `<w:t>texte</w:t>` (texte d'une « run ») → contenu brut
  ///
  /// Les autres balises (mise en forme, styles, images, commentaires…) sont
  /// silencieusement ignorées — c'est le but : produire du texte sans bruit.
  ///
  /// Limites :
  /// - `.doc` (binaire pré-2007, format Compound File) **n'est pas** un ZIP
  ///   et n'est donc pas supporté — on renvoie une erreur claire.
  /// - `.docx` chiffré : le ZIP n'expose pas `document.xml` → erreur claire.
  /// - Tableaux : seul le texte brut des cellules est concaténé (pas de
  ///   restitution de la structure).
  Future<File?> _docxToText() async {
    final path = await RftPickerScreen.pickOne(
      context,
      title: 'Choisir un fichier Word',
      extensions: const {'docx', 'docm'},
    );
    if (path == null) return null;

    final src = File(path);
    final size = await src.length();
    if (size > 50 * 1024 * 1024) {
      throw 'Fichier trop volumineux (${(size / 1024 / 1024).toStringAsFixed(0)} Mo). '
          'Maximum 50 Mo.';
    }

    final bytes = await src.readAsBytes();

    // Garde-fou minimal : un .doc binaire commence par la signature OLE
    // `D0 CF 11 E0 A1 B1 1A E1`. On rejette proprement avant de tenter le
    // décodage ZIP qui crasherait avec un message obscur.
    if (bytes.length >= 8 &&
        bytes[0] == 0xD0 &&
        bytes[1] == 0xCF &&
        bytes[2] == 0x11 &&
        bytes[3] == 0xE0) {
      throw 'Format .doc (Word 97-2003) non supporté. Réenregistrez en .docx '
          'depuis Word ou LibreOffice.';
    }

    Archive archive;
    try {
      archive = ZipDecoder().decodeBytes(bytes);
    } catch (_) {
      throw 'Le fichier n\'est pas un .docx valide (archive ZIP illisible).';
    }

    final entry = archive.findFile('word/document.xml');
    if (entry == null) {
      throw '`word/document.xml` introuvable dans l\'archive — fichier '
          'corrompu ou chiffré.';
    }
    final xmlContent = String.fromCharCodes(entry.content as List<int>);
    final extracted = _docxXmlToPlainText(xmlContent);

    if (extracted.trim().isEmpty) {
      throw 'Le document semble vide (aucun texte trouvé).';
    }

    final base = path
        .split(RegExp(r'[/\\]'))
        .last
        .replaceAll(RegExp(r'\.[^.]+$'), '');
    final out = await _reserve(base, 'txt');
    await out.writeAsString(extracted);
    return out;
  }

  /// Convertit le XML interne d'un `.docx` en texte brut.
  ///
  /// Approche par regex plutôt que parser DOM complet (Word XML est verbeux
  /// et la dépendance `xml` n'est pas embarquée). Robuste pour le cas général
  /// de production de texte lisible.
  static String _docxXmlToPlainText(String xml) {
    var s = xml;
    // Sauts de ligne durs et tabulations.
    s = s.replaceAll(RegExp(r'<w:br\s*/>'), '\n');
    s = s.replaceAll(RegExp(r'<w:tab\s*/>'), '\t');
    // Fin de paragraphe → marqueur unique qu'on remplacera après extraction
    // des runs (l'ordre compte pour ne pas dupliquer des sauts).
    s = s.replaceAll(RegExp(r'</w:p>'), '');

    // Extrait tous les contenus <w:t ...>texte</w:t>, en respectant l'option
    // xml:space="preserve" via `.*?` non-greedy.
    final runRe = RegExp(r'<w:t(?:\s[^>]*)?>(.*?)</w:t>', dotAll: true);
    final paragraphs = s.split('');
    final out = StringBuffer();
    for (final p in paragraphs) {
      final pieces = runRe
          .allMatches(p)
          .map((m) => _decodeXmlEntities(m.group(1) ?? ''))
          .join();
      // On préserve les paragraphes vides (ligne blanche en sortie).
      out
        ..write(pieces)
        ..writeln();
    }
    // Compresse les enchaînements de plus de 2 lignes vides (mise en page
    // Word génère parfois plusieurs paragraphes vides successifs).
    final result = out
        .toString()
        .replaceAll(RegExp(r'\n{3,}'), '\n\n')
        .trimRight();
    return result;
  }

  /// Décode les 5 entités XML standard. Suffisant pour Word qui n'utilise
  /// pas d'entités HTML étendues.
  static String _decodeXmlEntities(String s) {
    return s
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&quot;', '"')
        .replaceAll('&apos;', "'")
        .replaceAll('&amp;', '&');
  }

  // ── Image conversion (any → JPG/PNG/WebP) ──────────────────────────────────
  Future<File?> _convertImage(String targetExt) async {
    final res = await FilePicker.platform.pickFiles(type: FileType.image);
    if (res == null || res.files.single.path == null) return null;
    final bytes = await File(res.files.single.path!).readAsBytes();
    final decoded = img.decodeImage(bytes);
    if (decoded == null) throw 'Image illisible';
    Uint8List encoded;
    switch (targetExt) {
      case 'jpg':
        encoded = Uint8List.fromList(img.encodeJpg(decoded, quality: 90));
        break;
      case 'png':
        encoded = Uint8List.fromList(img.encodePng(decoded));
        break;
      case 'webp':
        // image package n'encode pas WebP — fallback sur PNG.
        throw 'WebP non supporté en encodage — utilisez PNG ou JPG.';
      default:
        throw 'Format inconnu';
    }
    final base = res.files.single.name.replaceAll(RegExp(r'\.[^.]+$'), '');
    final out = await _reserve(base, targetExt);
    await out.writeAsBytes(encoded);
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final tiles = [
      (
        icon: Icons.picture_as_pdf,
        color: Colors.red,
        title: 'Images → PDF',
        subtitle: 'JPG/PNG → un seul PDF',
        onTap: () => _run(_imagesToPdf),
      ),
      (
        icon: Icons.table_chart,
        color: Colors.green,
        title: 'CSV → XLSX',
        subtitle: 'Convertir un CSV en classeur Excel',
        onTap: () => _run(_csvToXlsx),
      ),
      (
        icon: Icons.description,
        color: Colors.blue,
        title: 'Texte → PDF',
        subtitle: 'TXT ou Markdown → PDF',
        onTap: () => _run(_textToPdf),
      ),
      (
        icon: Icons.text_snippet_outlined,
        color: Colors.deepOrange,
        title: 'PDF → Texte',
        subtitle: 'Extraire le texte d\'un PDF en .txt',
        onTap: () => _run(_pdfToText),
      ),
      (
        icon: Icons.article_outlined,
        color: Colors.teal,
        title: 'DOCX → Texte',
        subtitle: 'Extraire le texte d\'un Word en .txt',
        onTap: () => _run(_docxToText),
      ),
      (
        icon: Icons.image,
        color: Colors.purple,
        title: 'Image → JPG',
        subtitle: 'PNG/HEIC/WebP → JPG',
        onTap: () => _run(() => _convertImage('jpg')),
      ),
      (
        icon: Icons.image_outlined,
        color: Colors.indigo,
        title: 'Image → PNG',
        subtitle: 'JPG/HEIC → PNG',
        onTap: () => _run(() => _convertImage('png')),
      ),
    ];

    return Scaffold(
      appBar: AppBar(title: const Text('Conversion')),
      body: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          if (_busy) const LinearProgressIndicator(),
          if (_lastPath != null)
            Card(
              color: Colors.lightBlue.shade50.withValues(alpha: 0.85),
              shape: RoundedRectangleBorder(
                side: BorderSide(color: Colors.lightBlue.shade300, width: 1.5),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          Icons.check_circle,
                          color: Colors.lightBlue.shade700,
                          size: 18,
                        ),
                        const SizedBox(width: 6),
                        Text(
                          'Sauvegardé',
                          style: TextStyle(
                            fontWeight: FontWeight.w700,
                            fontSize: 13,
                            color: Colors.lightBlue.shade900,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      _lastPath!,
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 11,
                      ),
                    ),
                    const SizedBox(height: 8),
                    CloudShareRow(path: _lastPath!),
                  ],
                ),
              ),
            )
          else if (_status != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Text(
                _status!,
                style: const TextStyle(fontSize: 12, color: Colors.grey),
              ),
            ),
          ...tiles.map(
            (t) => Card(
              child: ListTile(
                leading: Icon(t.icon, color: t.color, size: 32),
                title: Text(
                  t.title,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                subtitle: Text(t.subtitle),
                trailing: const Icon(Icons.chevron_right),
                onTap: _busy ? null : t.onTap,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
