import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../../services/duplicate_finder_service.dart';

class DuplicatesScreen extends StatefulWidget {
  const DuplicatesScreen({super.key});

  @override
  State<DuplicatesScreen> createState() => _DuplicatesScreenState();
}

class _DuplicatesScreenState extends State<DuplicatesScreen>
    with SingleTickerProviderStateMixin {
  final _service = DuplicateFinderService();
  late TabController _tabs;
  String _root = '/storage/emulated/0';
  bool _scanning = false;
  FinderResult? _result;
  String? _error;
  final Set<String> _selected = {};

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this);
  }

  @override
  void dispose() {
    _service.cancel();
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _pickRoot() async {
    final dir = await FilePicker.platform.getDirectoryPath();
    if (dir != null) setState(() => _root = dir);
  }

  Future<void> _scan() async {
    setState(() {
      _scanning = true;
      _error = null;
      _result = null;
      _selected.clear();
    });
    try {
      final r = await _service.find(root: _root);
      if (!mounted) return;
      setState(() { _result = r; _scanning = false; });
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = '$e'; _scanning = false; });
    }
  }

  Future<void> _deleteSelected() async {
    if (_selected.isEmpty) return;
    final paths = _selected.toList();
    int totalBytes = 0;
    for (final p in paths) {
      try { totalBytes += await File(p).length(); } catch (_) {}
    }
    if (!mounted) return;
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Supprimer définitivement ?'),
        content: Text(
          'Vous allez supprimer ${paths.length} fichier${paths.length > 1 ? 's' : ''} '
          '(${_fmt(totalBytes)}). Cette action est irréversible.',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Annuler')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Supprimer'),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    int ok = 0, fail = 0;
    for (final p in paths) {
      try { await File(p).delete(); ok++; } catch (_) { fail++; }
    }
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('$ok supprimé${ok > 1 ? 's' : ''}'
          '${fail > 0 ? ' · $fail échec(s)' : ''}'),
    ));
    setState(() => _selected.clear());
    _scan();
  }

  void _toggle(String path) {
    setState(() {
      if (!_selected.add(path)) _selected.remove(path);
    });
  }

  /// Sécurité : dans un set de doublons, on refuse de cocher TOUS les fichiers.
  /// L'utilisateur doit en garder au moins un.
  bool _canSelect(DuplicateSet set, String path) {
    if (_selected.contains(path)) return true; // décocher toujours possible
    final selectedInSet = set.files.where((f) => _selected.contains(f.path)).length;
    return selectedInSet < set.files.length - 1;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Doublons & gros fichiers'),
        bottom: TabBar(
          controller: _tabs,
          tabs: const [
            Tab(text: 'Doublons', icon: Icon(Icons.content_copy_outlined)),
            Tab(text: 'Plus gros', icon: Icon(Icons.scale_outlined)),
          ],
        ),
      ),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
          child: Row(children: [
            IconButton(
              tooltip: 'Choisir le dossier à scanner',
              icon: const Icon(Icons.folder_open),
              onPressed: _scanning ? null : _pickRoot,
            ),
            Expanded(
              child: Text(_root,
                  style: const TextStyle(fontSize: 11, color: Colors.grey),
                  overflow: TextOverflow.ellipsis),
            ),
            FilledButton.icon(
              onPressed: _scanning ? null : _scan,
              icon: _scanning
                  ? const SizedBox(width: 14, height: 14,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.search),
              label: Text(_scanning ? 'Scan…' : 'Scanner'),
            ),
          ]),
        ),
        if (_result != null)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                  '${_result!.filesScanned} fichiers · '
                  '${_result!.duplicates.length} groupe(s) de doublons '
                  '· ${_fmt(_totalWasted())} gaspillés',
                  style: const TextStyle(fontSize: 11, color: Colors.grey)),
            ),
          ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.all(12),
            child: Text('Erreur : $_error',
                style: const TextStyle(color: Colors.red)),
          ),
        Expanded(
          child: TabBarView(
            controller: _tabs,
            children: [
              _buildDuplicatesTab(),
              _buildLargestTab(),
            ],
          ),
        ),
      ]),
      floatingActionButton: _selected.isEmpty
          ? null
          : FloatingActionButton.extended(
              backgroundColor: Colors.red,
              onPressed: _deleteSelected,
              icon: const Icon(Icons.delete_outline, color: Colors.white),
              label: Text('Supprimer ${_selected.length}',
                  style: const TextStyle(color: Colors.white)),
            ),
    );
  }

  int _totalWasted() => _result?.duplicates
      .fold<int>(0, (s, d) => s + d.wastedBytes) ?? 0;

  Widget _buildDuplicatesTab() {
    final r = _result;
    if (r == null) {
      return const Center(child: Text('Lancez un scan pour voir les doublons',
          style: TextStyle(color: Colors.grey)));
    }
    if (r.duplicates.isEmpty) {
      return const Center(child: Text('Aucun doublon trouvé ✓',
          style: TextStyle(color: Colors.grey)));
    }
    return ListView.builder(
      itemCount: r.duplicates.length,
      itemBuilder: (_, i) {
        final set = r.duplicates[i];
        return Card(
          margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: ExpansionTile(
            leading: const Icon(Icons.content_copy_outlined),
            title: Text(set.files.first.path.split(RegExp(r'[/\\]')).last,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 13)),
            subtitle: Text(
                '${set.files.length} copies · ${_fmt(set.files.first.size)} chacune · '
                '${_fmt(set.wastedBytes)} gaspillés',
                style: const TextStyle(fontSize: 11)),
            children: set.files.map((f) {
              final allowed = _canSelect(set, f.path);
              return CheckboxListTile(
                dense: true,
                value: _selected.contains(f.path),
                onChanged: allowed ? (_) => _toggle(f.path) : null,
                title: Text(f.path,
                    style: TextStyle(
                      fontSize: 11, fontFamily: 'monospace',
                      color: allowed ? null : Colors.grey,
                    ),
                    overflow: TextOverflow.ellipsis),
                subtitle: Text(_dateFmt(f.modified),
                    style: const TextStyle(fontSize: 10, color: Colors.grey)),
              );
            }).toList(),
          ),
        );
      },
    );
  }

  Widget _buildLargestTab() {
    final r = _result;
    if (r == null) {
      return const Center(child: Text('Lancez un scan pour voir les plus gros fichiers',
          style: TextStyle(color: Colors.grey)));
    }
    if (r.largest.isEmpty) {
      return const Center(child: Text('Aucun fichier'));
    }
    return ListView.builder(
      itemCount: r.largest.length,
      itemBuilder: (_, i) {
        final f = r.largest[i];
        return CheckboxListTile(
          dense: true,
          value: _selected.contains(f.path),
          onChanged: (_) => _toggle(f.path),
          title: Text(f.path.split(RegExp(r'[/\\]')).last,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 13)),
          subtitle: Text(f.path,
              style: const TextStyle(fontSize: 10, color: Colors.grey),
              overflow: TextOverflow.ellipsis),
          secondary: Text(_fmt(f.size),
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
        );
      },
    );
  }

  String _fmt(int b) {
    if (b < 1024) return '$b B';
    if (b < 1024 * 1024) return '${(b / 1024).toStringAsFixed(0)} KB';
    if (b < 1024 * 1024 * 1024) return '${(b / (1024 * 1024)).toStringAsFixed(1)} MB';
    return '${(b / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }

  String _dateFmt(DateTime d) =>
      '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
}
