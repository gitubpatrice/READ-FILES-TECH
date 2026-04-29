import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:archive/archive.dart';
import 'package:share_plus/share_plus.dart';

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

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;
  String get _ext => _name.split('.').last.toLowerCase();

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final bytes = await File(widget.path).readAsBytes();
      final text = _ext == 'odt' || _ext == 'odp'
          ? _extractOdt(bytes)
          : _extractDocx(bytes);
      setState(() { _text = text; _isLoading = false; });
    } catch (e) {
      setState(() { _error = e.toString(); _isLoading = false; });
    }
  }

  String _extractDocx(List<int> bytes) {
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

  String _decodeEntities(String s) => s
      .replaceAll('&amp;', '&')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll('&apos;', "'")
      .replaceAll('&#xD;', '\n');

  String _extractOdt(List<int> bytes) {
    final archive = ZipDecoder().decodeBytes(bytes);
    final contentFile = archive.findFile('content.xml');
    if (contentFile == null) return 'Impossible de lire le document.';
    final xml = utf8.decode(contentFile.content as List<int>, allowMalformed: true);
    return _xmlToText(xml, tagName: 'text:p');
  }

  String _xmlToText(String xml, {required String tagName}) {
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
                .map((s) => PopupMenuItem(value: s.toDouble(), child: Text('$s pt')))
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
