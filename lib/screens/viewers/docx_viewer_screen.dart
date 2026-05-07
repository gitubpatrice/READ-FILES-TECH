import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'package:flutter/material.dart';
import 'package:archive/archive.dart';
import 'package:share_plus/share_plus.dart';
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
  String get _ext => _name.split('.').last.toLowerCase();

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Seuil au-delà duquel l'extraction (unzip + parse XML) passe en Isolate :
  /// ZipDecoder + regex full-doc sont CPU-bound, freeze visible >1 Mo sur S9.
  static const _isolateThreshold = 1024 * 1024; // 1 Mo

  Future<void> _load() async {
    try {
      final size = await File(widget.path).length();
      final bytes = await File(widget.path).readAsBytes();
      final ext = _ext;
      final String text;
      if (size > _isolateThreshold) {
        text = await Isolate.run(() {
          return ext == 'odt' || ext == 'odp'
              ? _extractOdtStatic(bytes)
              : _extractDocxStatic(bytes);
        });
      } else {
        text = ext == 'odt' || ext == 'odp'
            ? _extractOdtStatic(bytes)
            : _extractDocxStatic(bytes);
      }
      if (!mounted) return;
      setState(() {
        _text = text;
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

  // Static helpers : pas de capture de `this`, donc Isolate-safe.
  static String _extractDocxStatic(List<int> bytes) {
    final archive = ZipDecoder().decodeBytes(bytes);
    final docFile = archive.findFile('word/document.xml');
    if (docFile == null) return 'Impossible de lire le document.';
    final xml = utf8.decode(docFile.content as List<int>, allowMalformed: true);
    // Split paragraphs first, then concat all <w:t> inside each.
    final paragraphs = RegExp(r'<w:p[^>]*>(.*?)</w:p>', dotAll: true);
    final tRun = RegExp(r'<w:t[^>]*>(.*?)</w:t>', dotAll: true);
    final out = StringBuffer();
    for (final m in paragraphs.allMatches(xml)) {
      final pXml = m.group(1) ?? '';
      final line = StringBuffer();
      for (final t in tRun.allMatches(pXml)) {
        line.write(_decodeEntities(t.group(1) ?? ''));
      }
      final text = line.toString();
      if (text.trim().isNotEmpty) out.writeln(text);
    }
    return out.toString().trim();
  }

  static String _decodeEntities(String s) => s
      .replaceAll('&amp;', '&')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll('&apos;', "'")
      .replaceAll('&#xD;', '\n');

  static String _extractOdtStatic(List<int> bytes) {
    final archive = ZipDecoder().decodeBytes(bytes);
    final contentFile = archive.findFile('content.xml');
    if (contentFile == null) return 'Impossible de lire le document.';
    final xml = utf8.decode(
      contentFile.content as List<int>,
      allowMalformed: true,
    );
    return _xmlToText(xml, tagName: 'text:p');
  }

  static String _xmlToText(String xml, {required String tagName}) {
    final buffer = StringBuffer();
    final reg = RegExp('<$tagName[^>]*>(.*?)</$tagName>', dotAll: true);
    for (final m in reg.allMatches(xml)) {
      final inner = m.group(1) ?? '';
      // Supprimer les balises internes
      final clean = inner.replaceAll(RegExp(r'<[^>]+>'), '');
      // Décoder les entités XML basiques
      final decoded = clean
          .replaceAll('&amp;', '&')
          .replaceAll('&lt;', '<')
          .replaceAll('&gt;', '>')
          .replaceAll('&quot;', '"')
          .replaceAll('&apos;', "'")
          .replaceAll('&#xD;', '\n');
      if (decoded.trim().isNotEmpty) buffer.writeln(decoded);
    }
    return buffer.toString().trim();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
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
          : _error != null
          ? Center(child: Text('Erreur : $_error'))
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
