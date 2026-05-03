import 'dart:io';
import 'package:flutter/material.dart';
import 'package:csv/csv.dart';
import 'package:share_plus/share_plus.dart';
import '../editors/csv_editor_screen.dart';

class CsvViewerScreen extends StatefulWidget {
  final String path;
  const CsvViewerScreen({super.key, required this.path});

  @override
  State<CsvViewerScreen> createState() => _CsvViewerScreenState();
}

class _CsvViewerScreenState extends State<CsvViewerScreen> {
  List<List<dynamic>> _rows = [];
  List<List<dynamic>> _filtered = [];
  bool _isLoading = true;
  int? _sortCol;
  bool _sortAsc = true;
  String _search = '';
  final _searchCtrl = TextEditingController();

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;
  List<dynamic> get _headers => _rows.isNotEmpty ? _rows[0] : [];
  List<List<dynamic>> get _dataRows => _rows.length > 1 ? _rows.sublist(1) : [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final content = await File(widget.path).readAsString();
      final rows = const CsvToListConverter().convert(content, eol: '\n');
      setState(() {
        _rows = rows;
        _filtered = rows;
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
    }
  }

  void _sort(int col) {
    setState(() {
      if (_sortCol == col) {
        _sortAsc = !_sortAsc;
      } else {
        _sortCol = col;
        _sortAsc = true;
      }
      final header = _rows[0];
      final data = List<List<dynamic>>.from(_dataRows);
      data.sort((a, b) {
        final av = col < a.length ? a[col].toString() : '';
        final bv = col < b.length ? b[col].toString() : '';
        final num1 = num.tryParse(av);
        final num2 = num.tryParse(bv);
        int cmp;
        if (num1 != null && num2 != null) {
          cmp = num1.compareTo(num2);
        } else {
          cmp = av.compareTo(bv);
        }
        return _sortAsc ? cmp : -cmp;
      });
      _rows = [header, ...data];
      _applySearch(_search);
    });
  }

  void _applySearch(String query) {
    setState(() {
      _search = query;
      if (query.isEmpty) {
        _filtered = _rows;
      } else {
        final header = _rows[0];
        final data = _dataRows
            .where(
              (row) => row.any(
                (cell) =>
                    cell.toString().toLowerCase().contains(query.toLowerCase()),
              ),
            )
            .toList();
        _filtered = [header, ...data];
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: 'Éditer',
            icon: const Icon(Icons.edit_outlined),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) => CsvEditorScreen(path: widget.path),
              ),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.share),
            onPressed: () => Share.shareXFiles([XFile(widget.path)]),
          ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(48),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
            child: TextField(
              controller: _searchCtrl,
              decoration: InputDecoration(
                hintText: 'Rechercher…',
                prefixIcon: const Icon(Icons.search, size: 18),
                suffixIcon: _search.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear, size: 18),
                        onPressed: () {
                          _searchCtrl.clear();
                          _applySearch('');
                        },
                      )
                    : null,
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(vertical: 8),
              ),
              onChanged: _applySearch,
            ),
          ),
        ),
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _rows.isEmpty
          ? const Center(child: Text('Fichier CSV vide'))
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 6, 16, 2),
                  child: Row(
                    children: [
                      Text(
                        '${_dataRows.length} lignes  ·  ${_headers.length} colonnes',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.grey,
                        ),
                      ),
                    ],
                  ),
                ),
                Expanded(child: _buildTable()),
              ],
            ),
    );
  }

  Widget _buildTable() {
    final cols = _headers.length;
    final displayRows = _filtered.length > 1 ? _filtered.sublist(1) : [];
    final colWidth = 120.0;

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: SingleChildScrollView(
        child: DataTable(
          headingRowColor: WidgetStateProperty.all(
            Theme.of(context).colorScheme.surfaceContainerHighest,
          ),
          columnSpacing: 16,
          dataRowMinHeight: 32,
          dataRowMaxHeight: 48,
          columns: List.generate(cols, (i) {
            return DataColumn(
              label: SizedBox(
                width: colWidth,
                child: Text(
                  _headers[i].toString(),
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
              onSort: (col, _) => _sort(col),
            );
          }),
          rows: displayRows.map((row) {
            return DataRow(
              cells: List.generate(cols, (i) {
                final val = i < row.length ? row[i].toString() : '';
                return DataCell(
                  SizedBox(
                    width: colWidth,
                    child: Text(val, overflow: TextOverflow.ellipsis),
                  ),
                );
              }),
            );
          }).toList(),
          sortColumnIndex: _sortCol,
          sortAscending: _sortAsc,
        ),
      ),
    );
  }
}
