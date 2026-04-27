import 'dart:io';
import 'package:flutter/material.dart';
import 'package:excel/excel.dart';
import 'package:share_plus/share_plus.dart';

class XlsxViewerScreen extends StatefulWidget {
  final String path;
  const XlsxViewerScreen({super.key, required this.path});

  @override
  State<XlsxViewerScreen> createState() => _XlsxViewerScreenState();
}

class _XlsxViewerScreenState extends State<XlsxViewerScreen> {
  Excel? _excel;
  bool _isLoading = true;
  String? _error;
  int _sheetIndex = 0;

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final bytes = await File(widget.path).readAsBytes();
      final excel = Excel.decodeBytes(bytes);
      setState(() { _excel = excel; _isLoading = false; });
    } catch (e) {
      setState(() { _error = e.toString(); _isLoading = false; });
    }
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
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Text('Erreur : $_error'))
              : _buildContent(),
    );
  }

  Widget _buildContent() {
    final excel = _excel!;
    final sheetNames = excel.tables.keys.toList();
    if (sheetNames.isEmpty) return const Center(child: Text('Classeur vide'));

    final sheetName = sheetNames[_sheetIndex];
    final sheet = excel.tables[sheetName]!;
    final rows = sheet.rows;

    return Column(
      children: [
        if (sheetNames.length > 1)
          SizedBox(
            height: 40,
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 8),
              itemCount: sheetNames.length,
              itemBuilder: (_, i) => Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
                child: ChoiceChip(
                  label: Text(sheetNames[i]),
                  selected: _sheetIndex == i,
                  onSelected: (_) => setState(() => _sheetIndex = i),
                ),
              ),
            ),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
          child: Text('${rows.length} lignes  ·  ${rows.fold(0, (max, r) => r.length > max ? r.length : max)} colonnes',
              style: const TextStyle(fontSize: 12, color: Colors.grey)),
        ),
        Expanded(child: _buildTable(rows)),
      ],
    );
  }

  Widget _buildTable(List<List<Data?>> rows) {
    if (rows.isEmpty) return const Center(child: Text('Feuille vide'));
    final maxCols = rows.map((r) => r.length).fold(0, (a, b) => a > b ? a : b);
    const colWidth = 120.0;

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: SingleChildScrollView(
        child: DataTable(
          headingRowColor: WidgetStateProperty.all(
              Theme.of(context).colorScheme.surfaceContainerHighest),
          columnSpacing: 16,
          dataRowMinHeight: 32,
          dataRowMaxHeight: 48,
          columns: List.generate(maxCols, (i) => DataColumn(
            label: SizedBox(
              width: colWidth,
              child: Text(rows.isNotEmpty ? (rows[0][i]?.value?.toString() ?? '') : '',
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600)),
            ),
          )),
          rows: rows.skip(1).map((row) => DataRow(
            cells: List.generate(maxCols, (i) {
              final val = i < row.length ? (row[i]?.value?.toString() ?? '') : '';
              return DataCell(SizedBox(
                  width: colWidth,
                  child: Text(val, overflow: TextOverflow.ellipsis)));
            }),
          )).toList(),
        ),
      ),
    );
  }
}
