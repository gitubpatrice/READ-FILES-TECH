import 'dart:io';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:syncfusion_flutter_pdf/pdf.dart';
import '../../widgets/rft_picker_screen.dart';

class TxtToolsScreen extends StatefulWidget {
  const TxtToolsScreen({super.key});

  @override
  State<TxtToolsScreen> createState() => _TxtToolsScreenState();
}

class _TxtToolsScreenState extends State<TxtToolsScreen> {
  String? _path;
  String? _name;
  String _content = '';
  bool _isProcessing = false;

  // Recherche/Remplacement
  final _searchCtrl  = TextEditingController();
  final _replaceCtrl = TextEditingController();
  int _matchCount = 0;

  @override
  void dispose() {
    _searchCtrl.dispose();
    _replaceCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickFile() async {
    final path = await RftPickerScreen.pickOne(context,
        title: 'Choisir un fichier texte',
        extensions: const {'txt', 'md', 'csv', 'xml', 'json'});
    if (path == null) return;
    if (!mounted) return;
    final content = await File(path).readAsString();
    if (!mounted) return;
    setState(() {
      _path = path;
      _name = path.split(RegExp(r'[/\\]')).last;
      _content = content;
      _matchCount = 0;
    });
  }

  void _countMatches() {
    final q = _searchCtrl.text;
    if (q.isEmpty) { setState(() => _matchCount = 0); return; }
    setState(() => _matchCount = q.allMatches(_content).length);
  }

  Future<void> _replace() async {
    if (_path == null || _searchCtrl.text.isEmpty) return;
    final messenger = ScaffoldMessenger.of(context);
    final updated = _content.replaceAll(_searchCtrl.text, _replaceCtrl.text);
    setState(() => _content = updated);
    await File(_path!).writeAsString(updated);
    if (!mounted) return;
    messenger.showSnackBar(
      const SnackBar(content: Text('Remplacement effectué et sauvegardé')),
    );
  }

  Future<void> _convertToPdf() async {
    if (_path == null) return;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isProcessing = true);
    try {
      final doc = PdfDocument();
      final page = doc.pages.add();
      final font = PdfStandardFont(PdfFontFamily.courier, 10);
      final brush = PdfSolidBrush(PdfColor(30, 30, 30));
      final size = page.getClientSize();

      doc.documentInformation.title = _name ?? 'Document';
      page.graphics.drawString(
        _content, font,
        brush: brush,
        bounds: Rect.fromLTWH(0, 0, size.width, size.height),
        format: PdfStringFormat(lineSpacing: 4),
      );

      final dir = await getApplicationDocumentsDirectory();
      final ts  = DateTime.now().millisecondsSinceEpoch;
      final base = (_name ?? 'document').replaceAll(RegExp(r'\.\w+$'), '');
      final outPath = '${dir.path}/${base}_$ts.pdf';
      await File(outPath).writeAsBytes(await doc.save());
      doc.dispose();

      if (!mounted) return;
      setState(() => _isProcessing = false);
      messenger.showSnackBar(
        SnackBar(
          content: Text('PDF créé : ${outPath.split('/').last}'),
          action: SnackBarAction(
            label: 'Partager',
            onPressed: () => Share.shareXFiles([XFile(outPath)]),
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _isProcessing = false);
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Map<String, int> get _stats {
    if (_content.isEmpty) return {'chars': 0, 'words': 0, 'lines': 0};
    return {
      'chars': _content.length,
      'words': _content.trim().isEmpty ? 0 : _content.trim().split(RegExp(r'\s+')).length,
      'lines': '\n'.allMatches(_content).length + 1,
    };
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Outils TXT')),
      body: _path == null ? _buildPicker() : _buildTools(),
    );
  }

  Widget _buildPicker() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.text_snippet_outlined, size: 88,
                color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.35)),
            const SizedBox(height: 24),
            Text('Outils TXT', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text('Statistiques, recherche/remplacement, export PDF',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: Colors.grey),
                textAlign: TextAlign.center),
            const SizedBox(height: 32),
            FilledButton.icon(
              onPressed: _pickFile,
              icon: const Icon(Icons.folder_open),
              label: const Text('Choisir un fichier TXT'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTools() {
    final stats = _stats;
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header fichier
          Row(children: [
            const Icon(Icons.text_snippet_outlined, color: Colors.blueGrey),
            const SizedBox(width: 8),
            Expanded(child: Text(_name!, overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w500))),
            TextButton(onPressed: _pickFile, child: const Text('Changer')),
          ]),
          const Divider(height: 24),

          // Statistiques
          Text('Statistiques', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 10),
          Row(children: [
            _statCard('Caractères', stats['chars'].toString(), Colors.blue),
            const SizedBox(width: 8),
            _statCard('Mots', stats['words'].toString(), Colors.green),
            const SizedBox(width: 8),
            _statCard('Lignes', stats['lines'].toString(), Colors.orange),
          ]),
          const SizedBox(height: 24),

          // Rechercher / Remplacer
          Text('Rechercher / Remplacer', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 10),
          TextField(
            controller: _searchCtrl,
            decoration: InputDecoration(
              labelText: 'Rechercher',
              prefixIcon: const Icon(Icons.search),
              suffixText: _matchCount > 0 ? '$_matchCount résultat${_matchCount > 1 ? 's' : ''}' : null,
              border: const OutlineInputBorder(),
            ),
            onChanged: (_) => _countMatches(),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _replaceCtrl,
            decoration: const InputDecoration(
              labelText: 'Remplacer par',
              prefixIcon: Icon(Icons.find_replace),
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _searchCtrl.text.isNotEmpty ? _replace : null,
              icon: const Icon(Icons.find_replace, size: 18),
              label: const Text('Remplacer tout'),
            ),
          ),
          const SizedBox(height: 24),

          // Convertir en PDF
          Text('Export', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _isProcessing ? null : _convertToPdf,
              icon: _isProcessing
                  ? const SizedBox(width: 16, height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : const Icon(Icons.picture_as_pdf),
              label: const Text('Convertir en PDF'),
              style: FilledButton.styleFrom(minimumSize: const Size(0, 48)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _statCard(String label, String value, Color color) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withValues(alpha: 0.3)),
        ),
        child: Column(
          children: [
            Text(value, style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: color)),
            Text(label, style: const TextStyle(fontSize: 11, color: Colors.grey)),
          ],
        ),
      ),
    );
  }
}
