import 'dart:io';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:csv/csv.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:syncfusion_flutter_pdf/pdf.dart';

class CsvToolsScreen extends StatefulWidget {
  const CsvToolsScreen({super.key});

  @override
  State<CsvToolsScreen> createState() => _CsvToolsScreenState();
}

class _CsvToolsScreenState extends State<CsvToolsScreen> {
  String? _path;
  String? _name;
  List<List<dynamic>> _rows = [];
  bool _isProcessing = false;

  List<dynamic> get _headers => _rows.isNotEmpty ? _rows[0] : [];
  List<List<dynamic>> get _dataRows => _rows.length > 1 ? _rows.sublist(1) : [];

  Future<void> _pickFile() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['csv'],
      allowMultiple: false,
    );
    if (result == null || result.files.single.path == null) return;
    final path = result.files.single.path!;
    final content = await File(path).readAsString();
    final rows = const CsvToListConverter().convert(content, eol: '\n');
    setState(() { _path = path; _name = result.files.single.name; _rows = rows; });
  }

  Future<void> _exportPdf() async {
    if (_path == null || _rows.isEmpty) return;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isProcessing = true);
    try {
      final doc = PdfDocument();
      final page = doc.pages.add();
      final size = page.getClientSize();
      final font  = PdfStandardFont(PdfFontFamily.helvetica, 9);
      final bold  = PdfStandardFont(PdfFontFamily.helvetica, 9, style: PdfFontStyle.bold);
      final brush = PdfSolidBrush(PdfColor(30, 30, 30));
      final headerBrush = PdfSolidBrush(PdfColor(21, 101, 192));

      final colCount = _headers.length;
      final colW = (size.width - 20) / colCount;
      double y = 0;

      // En-têtes
      for (int i = 0; i < colCount; i++) {
        page.graphics.drawRectangle(
          brush: PdfSolidBrush(PdfColor(220, 230, 245)),
          bounds: Rect.fromLTWH(i * colW, y, colW, 16),
        );
        page.graphics.drawString(
          _headers[i].toString(), bold,
          brush: headerBrush,
          bounds: Rect.fromLTWH(i * colW + 2, y + 2, colW - 4, 14),
        );
      }
      y += 16;

      // Lignes
      for (int r = 0; r < _dataRows.length && y < size.height - 20; r++) {
        if (r % 2 == 0) {
          page.graphics.drawRectangle(
            brush: PdfSolidBrush(PdfColor(248, 248, 248)),
            bounds: Rect.fromLTWH(0, y, size.width - 20, 14),
          );
        }
        for (int i = 0; i < colCount; i++) {
          final val = i < _dataRows[r].length ? _dataRows[r][i].toString() : '';
          page.graphics.drawString(val, font, brush: brush,
              bounds: Rect.fromLTWH(i * colW + 2, y + 1, colW - 4, 13));
        }
        y += 14;
      }

      final dir = await getApplicationDocumentsDirectory();
      final ts  = DateTime.now().millisecondsSinceEpoch;
      final base = (_name ?? 'data').replaceAll('.csv', '');
      final outPath = '${dir.path}/${base}_$ts.pdf';
      await File(outPath).writeAsBytes(await doc.save());
      doc.dispose();

      if (!mounted) return;
      setState(() => _isProcessing = false);
      messenger.showSnackBar(SnackBar(
        content: Text('PDF créé : ${outPath.split('/').last}'),
        action: SnackBarAction(label: 'Partager',
            onPressed: () => Share.shareXFiles([XFile(outPath)])),
      ));
    } catch (e) {
      if (!mounted) return;
      setState(() => _isProcessing = false);
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _mergeCsv() async {
    final messenger = ScaffoldMessenger.of(context);
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['csv'],
      allowMultiple: true,
    );
    if (result == null || result.files.isEmpty) return;
    setState(() => _isProcessing = true);
    try {
      final allRows = <List<dynamic>>[];
      bool firstFile = true;
      for (final f in result.files) {
        if (f.path == null) continue;
        final content = await File(f.path!).readAsString();
        final rows = const CsvToListConverter().convert(content, eol: '\n');
        if (firstFile) {
          allRows.addAll(rows);
          firstFile = false;
        } else {
          // Ignorer l'en-tête des fichiers suivants
          if (rows.length > 1) allRows.addAll(rows.sublist(1));
        }
      }

      final csv = const ListToCsvConverter().convert(allRows);
      final dir = await getApplicationDocumentsDirectory();
      final ts  = DateTime.now().millisecondsSinceEpoch;
      final outPath = '${dir.path}/fusion_csv_$ts.csv';
      await File(outPath).writeAsString(csv);

      if (!mounted) return;
      setState(() => _isProcessing = false);
      messenger.showSnackBar(SnackBar(
        content: Text('${allRows.length - 1} lignes fusionnées'),
        action: SnackBarAction(label: 'Partager',
            onPressed: () => Share.shareXFiles([XFile(outPath)])),
      ));
    } catch (e) {
      if (!mounted) return;
      setState(() => _isProcessing = false);
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Outils CSV')),
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
            Icon(Icons.table_chart_outlined, size: 88,
                color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.35)),
            const SizedBox(height: 24),
            Text('Outils CSV', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text('Analysez, exportez et fusionnez vos fichiers CSV',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: Colors.grey),
                textAlign: TextAlign.center),
            const SizedBox(height: 32),
            FilledButton.icon(
              onPressed: _pickFile,
              icon: const Icon(Icons.folder_open),
              label: const Text('Choisir un fichier CSV'),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _mergeCsv,
              icon: const Icon(Icons.merge_type),
              label: const Text('Fusionner plusieurs CSV'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTools() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.table_chart_outlined, color: Colors.green),
            const SizedBox(width: 8),
            Expanded(child: Text(_name!, overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w500))),
            TextButton(onPressed: _pickFile, child: const Text('Changer')),
          ]),
          const Divider(height: 24),

          // Stats
          Row(children: [
            _statCard('Lignes', _dataRows.length.toString(), Colors.green),
            const SizedBox(width: 8),
            _statCard('Colonnes', _headers.length.toString(), Colors.blue),
          ]),
          const SizedBox(height: 24),

          // Colonnes
          Text('Colonnes', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          Wrap(
            spacing: 6, runSpacing: 6,
            children: _headers.map((h) => Chip(label: Text(h.toString(),
                style: const TextStyle(fontSize: 12)))).toList(),
          ),
          const SizedBox(height: 24),

          // Actions
          Text('Actions', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _isProcessing ? null : _exportPdf,
              icon: const Icon(Icons.picture_as_pdf),
              label: const Text('Exporter en PDF'),
              style: FilledButton.styleFrom(minimumSize: const Size(0, 48)),
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _isProcessing ? null : _mergeCsv,
              icon: const Icon(Icons.merge_type),
              label: const Text('Fusionner avec d\'autres CSV'),
              style: OutlinedButton.styleFrom(minimumSize: const Size(0, 48)),
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
        child: Column(children: [
          Text(value, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: color)),
          Text(label, style: const TextStyle(fontSize: 11, color: Colors.grey)),
        ]),
      ),
    );
  }
}
