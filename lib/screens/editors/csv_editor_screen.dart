import 'dart:io';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:csv/csv.dart';

class CsvEditorScreen extends StatefulWidget {
  final String path;
  const CsvEditorScreen({super.key, required this.path});

  @override
  State<CsvEditorScreen> createState() => _CsvEditorScreenState();
}

class _CsvEditorScreenState extends State<CsvEditorScreen> {
  List<List<String>> _rows = [];
  bool _isLoading = true;
  bool _modified = false;
  bool _isSaving = false;
  String _resolvedPath = '';

  String get _name => _resolvedPath.isEmpty
      ? ''
      : _resolvedPath.split(RegExp(r'[/\\]')).last;

  @override
  void initState() {
    super.initState();
    _resolvedPath = widget.path;
    WidgetsBinding.instance.addPostFrameCallback((_) => _init());
  }

  Future<void> _init() async {
    if (_resolvedPath.isEmpty) {
      final nav = Navigator.of(context);
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['csv'],
        allowMultiple: false,
      );
      if (result == null || result.files.single.path == null) {
        if (mounted) { nav.pop(); }
        return;
      }
      _resolvedPath = result.files.single.path!;
    }
    await _load();
  }

  Future<void> _load() async {
    try {
      final content = await File(_resolvedPath).readAsString();
      final parsed = const CsvToListConverter().convert(content, eol: '\n');
      setState(() {
        _rows = parsed
            .map((r) => r.map((c) => c.toString()).toList())
            .toList();
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Erreur : $e')));
      }
    }
  }

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isSaving = true);
    try {
      final csv = const ListToCsvConverter().convert(_rows);
      await File(_resolvedPath).writeAsString(csv);
      setState(() { _modified = false; _isSaving = false; });
      if (!mounted) return;
      messenger.showSnackBar(
        const SnackBar(
            content: Text('Sauvegardé'), duration: Duration(seconds: 1)),
      );
    } catch (e) {
      setState(() => _isSaving = false);
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  void _editCell(int row, int col) {
    final ctrl = TextEditingController(text: _rows[row][col]);
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(row == 0
            ? 'En-tête · col ${col + 1}'
            : 'Ligne $row · col ${col + 1}'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(border: OutlineInputBorder()),
          maxLines: 3,
          minLines: 1,
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Annuler')),
          FilledButton(
            onPressed: () {
              setState(() {
                _rows[row][col] = ctrl.text;
                _modified = true;
              });
              Navigator.pop(context);
            },
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }

  void _addRow() {
    if (_rows.isEmpty) return;
    final cols = _rows[0].length;
    setState(() {
      _rows.add(List.filled(cols, ''));
      _modified = true;
    });
  }

  void _addColumn() {
    setState(() {
      for (final row in _rows) {
        row.add('');
      }
      _modified = true;
    });
  }

  void _deleteRow(int index) {
    if (_rows.length <= 1) return;
    setState(() {
      _rows.removeAt(index);
      _modified = true;
    });
  }

  void _deleteColumn(int index) {
    if (_rows.isEmpty || _rows[0].length <= 1) return;
    setState(() {
      for (final row in _rows) {
        if (index < row.length) row.removeAt(index);
      }
      _modified = true;
    });
  }

  Future<bool> _confirmLeave() async {
    final result = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Modifications non sauvegardées'),
        content: const Text('Voulez-vous sauvegarder avant de quitter ?'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Ignorer')),
          TextButton(
              onPressed: () => Navigator.pop(context, null),
              child: const Text('Annuler')),
          FilledButton(
            onPressed: () async {
              await _save();
              if (mounted) { Navigator.of(context).pop(true); }
            },
            child: const Text('Sauvegarder'),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_modified,
      onPopInvokedWithResult: (didPop, _) async {
        if (!didPop) {
          final nav = Navigator.of(context);
          final leave = await _confirmLeave();
          if (leave) nav.pop();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Row(children: [
            Expanded(
              child: Text(_name.isEmpty ? 'Éditeur CSV' : _name,
                  overflow: TextOverflow.ellipsis),
            ),
            if (_modified)
              Container(
                margin: const EdgeInsets.only(left: 8),
                padding:
                    const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.orange.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(4),
                  border:
                      Border.all(color: Colors.orange.withValues(alpha: 0.5)),
                ),
                child: const Text('modifié',
                    style: TextStyle(fontSize: 11, color: Colors.orange)),
              ),
          ]),
          actions: [
            if (_modified)
              IconButton(
                tooltip: 'Sauvegarder',
                icon: _isSaving
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.save),
                onPressed: _isSaving ? null : _save,
              ),
            PopupMenuButton<String>(
              onSelected: (v) {
                if (v == 'add_row') _addRow();
                if (v == 'add_col') _addColumn();
              },
              itemBuilder: (_) => const [
                PopupMenuItem(
                    value: 'add_row',
                    child: ListTile(
                        leading: Icon(Icons.add),
                        title: Text('Ajouter une ligne'))),
                PopupMenuItem(
                    value: 'add_col',
                    child: ListTile(
                        leading: Icon(Icons.add),
                        title: Text('Ajouter une colonne'))),
              ],
            ),
          ],
        ),
        body: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : _rows.isEmpty
                ? const Center(child: Text('Fichier vide'))
                : _buildTable(),
        floatingActionButton: _modified
            ? FloatingActionButton.extended(
                onPressed: _isSaving ? null : _save,
                icon: const Icon(Icons.save),
                label: const Text('Sauvegarder'),
                backgroundColor: Colors.orange,
              )
            : null,
      ),
    );
  }

  Widget _buildTable() {
    final cols =
        _rows.fold(0, (max, r) => r.length > max ? r.length : max);
    final theme = Theme.of(context);
    const cellW = 120.0;
    const rowNumW = 36.0;

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: SizedBox(
        width: rowNumW + cols * cellW + 40,
        child: Column(
          children: [
            // Delete-column buttons row
            Row(children: [
              const SizedBox(width: rowNumW),
              ...List.generate(
                  cols,
                  (c) => SizedBox(
                        width: cellW,
                        child: Center(
                          child: IconButton(
                            icon: const Icon(Icons.remove_circle_outline,
                                size: 16),
                            color: Colors.red.withValues(alpha: 0.6),
                            onPressed:
                                cols > 1 ? () => _deleteColumn(c) : null,
                            tooltip: 'Supprimer col ${c + 1}',
                          ),
                        ),
                      )),
            ]),
            // Data rows
            Expanded(
              child: ListView.builder(
                itemCount: _rows.length,
                itemBuilder: (_, rowIdx) {
                  final row = _rows[rowIdx];
                  final isHeader = rowIdx == 0;
                  return Container(
                    decoration: BoxDecoration(
                      color: isHeader
                          ? theme.colorScheme.surfaceContainerHighest
                          : rowIdx.isOdd
                              ? theme.colorScheme.surface
                              : theme.colorScheme.surfaceContainerLow,
                      border: Border(
                        bottom: BorderSide(
                          color: theme.dividerColor.withValues(alpha: 0.3),
                        ),
                      ),
                    ),
                    child: Row(children: [
                      // Row delete button (skip for header)
                      SizedBox(
                        width: rowNumW,
                        child: rowIdx == 0
                            ? const SizedBox()
                            : IconButton(
                                icon: const Icon(Icons.remove_circle_outline,
                                    size: 14),
                                color: Colors.red.withValues(alpha: 0.5),
                                onPressed: () => _deleteRow(rowIdx),
                                padding: EdgeInsets.zero,
                                tooltip: 'Supprimer ligne',
                              ),
                      ),
                      // Cells
                      ...List.generate(cols, (colIdx) {
                        final val =
                            colIdx < row.length ? row[colIdx] : '';
                        return InkWell(
                          onTap: () => _editCell(rowIdx, colIdx),
                          child: Container(
                            width: cellW,
                            height: 36,
                            padding: const EdgeInsets.symmetric(
                                horizontal: 8, vertical: 4),
                            decoration: BoxDecoration(
                              border: Border(
                                right: BorderSide(
                                  color: theme.dividerColor
                                      .withValues(alpha: 0.3),
                                ),
                              ),
                            ),
                            child: Text(
                              val,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: isHeader
                                    ? FontWeight.w700
                                    : FontWeight.normal,
                              ),
                            ),
                          ),
                        );
                      }),
                    ]),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
