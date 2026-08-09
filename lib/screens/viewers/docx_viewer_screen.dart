import 'dart:io';
import 'dart:isolate';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import '../../services/text_extraction_service.dart';
import '../../utils/file_caps.dart';
import '../explorer/file_type_helpers.dart';

class DocxViewerScreen extends StatefulWidget {
  final String path;
  const DocxViewerScreen({super.key, required this.path});

  @override
  State<DocxViewerScreen> createState() => _DocxViewerScreenState();
}

class _DocxViewerScreenState extends State<DocxViewerScreen> {
  String _text = '';
  bool _isLoading = true;
  String? _error;
  double _fontSize = 14;

  String get _name => widget.path.basename;
  String get _ext => PathUtils.fileExt(_name).toLowerCase();

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Seuil au-delà duquel l'extraction (unzip + parse XML) passe en Isolate :
  /// ZipDecoder + regex full-doc sont CPU-bound, freeze visible >1 Mo sur S9.
  // v2.11.1 — utilise PerfThresholds.isolateThreshold (files_tech_core)

  Future<void> _load() async {
    try {
      final f = File(widget.path);
      // F2 : cap fichier source (anti zip-bomb par taille brute).
      final capErr = await checkFileCap(f, FileCaps.docZipped);
      if (capErr != null) {
        if (!mounted) return;
        setState(() {
          _error = capErr;
          _isLoading = false;
        });
        return;
      }
      final size = await f.length();
      final bytes = await f.readAsBytes();
      final ext = _ext;
      final heavy = size > PerfThresholds.isolateThreshold;

      // A-P1b — les deux extracteurs (.docx et OpenDocument) vivent désormais
      // dans `text_extraction_service.dart`, seul endroit couvert par des
      // tests. Cet écran en portait sa propre copie, et les deux ne disaient
      // PAS la même chose : son décodage d'entités traitait `&amp;` en
      // premier, si bien que `&amp;lt;` ressortait en `<` au lieu de `&lt;`.
      // Le texte affiché différait donc du texte extrait par l'outil de
      // conversion, sur le même fichier. Elle ignorait aussi `<w:br>`,
      // `<w:tab>`, les entités numériques (`&#233;`) et la signature OLE des
      // vieux `.doc`.
      final result = ext == 'odt' || ext == 'odp'
          ? (heavy
                ? await Isolate.run(() => extractOdtText(bytes))
                : extractOdtText(bytes))
          : (heavy
                ? await Isolate.run(() => extractDocxText(bytes))
                : extractDocxText(bytes));
      if (!mounted) return;
      setState(() {
        _error = result.error;
        _text = result.text ?? '';
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: 'Partager',
            icon: const Icon(Icons.share),
            onPressed: () => Share.shareXFiles([XFile(widget.path)]),
          ),
          PopupMenuButton<double>(
            icon: const Icon(Icons.text_fields),
            onSelected: (v) => setState(() => _fontSize = v),
            itemBuilder: (_) => [12, 13, 14, 16, 18, 20]
                .map(
                  (s) =>
                      PopupMenuItem(value: s.toDouble(), child: Text('$s pt')),
                )
                .toList(),
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          // Sans préfixe « Erreur : ». Les messages du service sont des
          // phrases complètes, et l'un d'eux — « Le document semble vide » —
          // décrit un fichier parfaitement valide. L'annoncer comme une erreur
          // laissait croire à une panne de l'application.
          : _error != null
          ? Padding(
              padding: const EdgeInsets.all(24),
              child: Center(child: Text(_error!, textAlign: TextAlign.center)),
            )
          : _text.isEmpty
          ? const Center(child: Text('Document vide ou format non supporté'))
          : SingleChildScrollView(
              padding: const EdgeInsets.all(20),
              child: SelectableText(
                _text,
                style: TextStyle(fontSize: _fontSize, height: 1.7),
              ),
            ),
    );
  }
}
