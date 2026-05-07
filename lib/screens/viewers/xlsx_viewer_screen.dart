import 'dart:io';
import 'dart:isolate';
import 'package:flutter/material.dart';
import 'package:excel/excel.dart';
import 'package:share_plus/share_plus.dart';
import '../explorer/file_type_helpers.dart';

class XlsxViewerScreen extends StatefulWidget {
  final String path;
  const XlsxViewerScreen({super.key, required this.path});

  @override
  State<XlsxViewerScreen> createState() => _XlsxViewerScreenState();
}

/// Représentation pré-extraite et serialisable d'un classeur, produite
/// dans un Isolate. On évite ainsi de passer un `Excel` (non-isolatable) à
/// l'UI thread et on garde [_load] non-bloquant.
class _XlsxData {
  /// Map ordonnée : nom_feuille → lignes[colonne] (texte déjà résolu).
  final Map<String, List<List<String>>> sheets;
  const _XlsxData(this.sheets);
}

/// Décode un .xlsx/.ods en [_XlsxData] dans un isolate.
/// Plafonné à 5000 lignes × 200 colonnes par feuille pour borner RAM/CPU
/// (les vrais classeurs métier dépassent rarement). Toute donnée au-delà
/// est tronquée silencieusement — l'UI affichera la vraie taille pour info.
Future<_XlsxData> _decodeXlsx(String path) async {
  return Isolate.run(() {
    final bytes = File(path).readAsBytesSync();
    final excel = Excel.decodeBytes(bytes);
    const maxRows = 5000;
    const maxCols = 200;
    final out = <String, List<List<String>>>{};
    for (final entry in excel.tables.entries) {
      final sheet = entry.value;
      final rows = <List<String>>[];
      for (final r in sheet.rows.take(maxRows)) {
        final cells = <String>[];
        for (final c in r.take(maxCols)) {
          cells.add(c?.value?.toString() ?? '');
        }
        rows.add(cells);
      }
      out[entry.key] = rows;
    }
    return _XlsxData(out);
  });
}

class _XlsxViewerScreenState extends State<XlsxViewerScreen> {
  _XlsxData? _data;
  bool _isLoading = true;
  String? _error;
  int _sheetIndex = 0;

  String get _name => widget.path.basename;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final data = await _decodeXlsx(widget.path);
      if (!mounted) return;
      setState(() {
        _data = data;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = 'Fichier illisible';
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
    final data = _data!;
    final sheetNames = data.sheets.keys.toList();
    if (sheetNames.isEmpty) return const Center(child: Text('Classeur vide'));

    final sheetName = sheetNames[_sheetIndex];
    final rows = data.sheets[sheetName]!;
    final colCount = rows.fold<int>(0, (m, r) => r.length > m ? r.length : m);

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
          child: Text(
            '${rows.length} lignes  ·  $colCount colonnes',
            style: const TextStyle(fontSize: 12, color: Colors.grey),
          ),
        ),
        Expanded(child: _buildTable(rows, colCount)),
      ],
    );
  }

  Widget _buildTable(List<List<String>> rows, int maxCols) {
    if (rows.isEmpty) return const Center(child: Text('Feuille vide'));
    const colWidth = 120.0;
    // PaginatedDataTable génère des DataRow lazily quand on tourne les pages,
    // évitant la matérialisation de N×M cellules d'un coup (gros classeurs).
    final source = _XlsxRowSource(rows.skip(1).toList(), maxCols, colWidth);
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: PaginatedDataTable(
        rowsPerPage: 50,
        availableRowsPerPage: const [25, 50, 100, 200],
        showFirstLastButtons: true,
        columnSpacing: 16,
        dataRowMinHeight: 32,
        dataRowMaxHeight: 48,
        headingRowColor: WidgetStateProperty.all(
          Theme.of(context).colorScheme.surfaceContainerHighest,
        ),
        columns: List.generate(
          maxCols,
          (i) => DataColumn(
            label: SizedBox(
              width: colWidth,
              child: Text(
                rows.isNotEmpty && i < rows[0].length ? rows[0][i] : '',
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
          ),
        ),
        source: source,
      ),
    );
  }
}

class _XlsxRowSource extends DataTableSource {
  final List<List<String>> _rows;
  final int _cols;
  final double _colWidth;
  _XlsxRowSource(this._rows, this._cols, this._colWidth);

  @override
  DataRow? getRow(int index) {
    if (index >= _rows.length) return null;
    final row = _rows[index];
    return DataRow(
      cells: List.generate(_cols, (i) {
        final val = i < row.length ? row[i] : '';
        return DataCell(
          SizedBox(
            width: _colWidth,
            child: Text(val, overflow: TextOverflow.ellipsis),
          ),
        );
      }),
    );
  }

  @override
  bool get isRowCountApproximate => false;
  @override
  int get rowCount => _rows.length;
  @override
  int get selectedRowCount => 0;
}
