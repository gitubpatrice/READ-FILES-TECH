import 'dart:io';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import '../../widgets/rft_picker_screen.dart';

class CodeEditorScreen extends StatefulWidget {
  final String path;
  const CodeEditorScreen({super.key, required this.path});

  @override
  State<CodeEditorScreen> createState() => _CodeEditorScreenState();
}

class _CodeEditorScreenState extends State<CodeEditorScreen> {
  late TextEditingController _ctrl;
  bool _isLoading = true;
  bool _modified = false;
  bool _isSaving = false;
  String _original = '';
  String _resolvedPath = '';
  double _fontSize = 13;

  String get _name =>
      _resolvedPath.isEmpty ? '' : _resolvedPath.split(RegExp(r'[/\\]')).last;

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController();
    _resolvedPath = widget.path;
    WidgetsBinding.instance.addPostFrameCallback((_) => _init());
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _init() async {
    if (_resolvedPath.isEmpty) {
      final nav = Navigator.of(context);
      final path = await RftPickerScreen.pickOne(
        context,
        title: 'Choisir un fichier à éditer',
        extensions: const {
          'txt',
          'md',
          'csv',
          'xml',
          'json',
          'html',
          'css',
          'js',
          'php',
          'dart',
        },
      );
      if (path == null) {
        if (mounted) {
          nav.pop();
        }
        return;
      }
      _resolvedPath = path;
    }
    await _load();
  }

  Future<void> _load() async {
    try {
      final content = await File(_resolvedPath).readAsString();
      _ctrl.text = content;
      _original = content;
      _ctrl.addListener(() {
        final changed = _ctrl.text != _original;
        if (changed != _modified) setState(() => _modified = changed);
      });
      setState(() => _isLoading = false);
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Erreur de lecture : $e')));
      }
    }
  }

  Future<void> _backup() async {
    if (_resolvedPath.isEmpty) return;
    try {
      final dir = await getApplicationDocumentsDirectory();
      final histDir = Directory('${dir.path}/history');
      if (!await histDir.exists()) await histDir.create(recursive: true);

      final base = _name.replaceAll(RegExp(r'[^\w.]'), '_');
      final ts = DateTime.now().millisecondsSinceEpoch;
      await File(_resolvedPath).copy('${histDir.path}/${base}_$ts.bak');

      // list().toList() async pour ne pas bloquer le thread UI quand le
      // dossier history grossit (rotation à 10 mais d'autres fichiers .bak
      // d'autres fichiers édités cohabitent).
      final entries = await histDir.list().toList();
      final baks =
          entries
              .whereType<File>()
              .where((f) => f.path.contains('${base}_'))
              .toList()
            ..sort((a, b) => a.path.compareTo(b.path));
      if (baks.length > 10) {
        for (final old in baks.sublist(0, baks.length - 10)) {
          await old.delete();
        }
      }
    } catch (_) {}
  }

  Future<void> _showHistory() async {
    final dir = await getApplicationDocumentsDirectory();
    final histDir = Directory('${dir.path}/history');
    if (!await histDir.exists()) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Aucun historique disponible')),
      );
      return;
    }
    final base = _name.replaceAll(RegExp(r'[^\w.]'), '_');
    // Async list() pour ne pas freezer l'UI sur dossier history volumineux.
    final entries = await histDir.list().toList();
    if (!mounted) return;
    final baks =
        entries
            .whereType<File>()
            .where(
              (f) => f.path.contains('${base}_') && f.path.endsWith('.bak'),
            )
            .toList()
          ..sort((a, b) => b.path.compareTo(a.path));

    if (!mounted) return;
    if (baks.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Aucune sauvegarde pour ce fichier')),
      );
      return;
    }

    showModalBottomSheet(
      context: context,
      builder: (_) => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              'Historique — $_name',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          const Divider(height: 1),
          Flexible(
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: baks.length,
              itemBuilder: (ctx, i) {
                final f = baks[i];
                final fname = f.path.split(RegExp(r'[/\\]')).last;
                final tsStr = fname.split('_').last.replaceAll('.bak', '');
                final ms = int.tryParse(tsStr);
                final label = ms != null
                    ? DateTime.fromMillisecondsSinceEpoch(
                        ms,
                      ).toString().substring(0, 19)
                    : fname;
                return ListTile(
                  leading: const Icon(Icons.history),
                  title: Text(label, style: const TextStyle(fontSize: 14)),
                  trailing: TextButton(
                    child: const Text('Restaurer'),
                    onPressed: () async {
                      final nav = Navigator.of(ctx);
                      final content = await f.readAsString();
                      if (!mounted) return;
                      nav.pop();
                      _ctrl.text = content;
                      setState(() => _modified = true);
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text(
                            'Version restaurée — pensez à sauvegarder',
                          ),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isSaving = true);
    try {
      await _backup();
      await File(_resolvedPath).writeAsString(_ctrl.text);
      _original = _ctrl.text;
      setState(() {
        _modified = false;
        _isSaving = false;
      });
      if (!mounted) return;
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Fichier sauvegardé'),
          duration: Duration(seconds: 1),
        ),
      );
    } catch (e) {
      setState(() => _isSaving = false);
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(content: Text('Erreur de sauvegarde : $e')),
      );
    }
  }

  Future<bool> _onWillPop() async {
    if (!_modified) return true;
    final result = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Modifications non sauvegardées'),
        content: const Text('Voulez-vous sauvegarder avant de quitter ?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Ignorer'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, null),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () async {
              await _save();
              if (mounted) {
                Navigator.of(context).pop(true);
              }
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
          final leave = await _onWillPop();
          if (leave) nav.pop();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Row(
            children: [
              Expanded(
                child: Text(
                  _name.isEmpty ? 'Éditeur' : _name,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (_modified)
                Container(
                  margin: const EdgeInsets.only(left: 8),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 6,
                    vertical: 2,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.orange.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(
                      color: Colors.orange.withValues(alpha: 0.5),
                    ),
                  ),
                  child: const Text(
                    'modifié',
                    style: TextStyle(fontSize: 11, color: Colors.orange),
                  ),
                ),
            ],
          ),
          actions: [
            if (_modified)
              IconButton(
                tooltip: 'Sauvegarder',
                icon: _isSaving
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.save),
                onPressed: _isSaving ? null : _save,
              ),
            if (_resolvedPath.isNotEmpty)
              IconButton(
                tooltip: 'Historique',
                icon: const Icon(Icons.history),
                onPressed: _showHistory,
              ),
            if (_resolvedPath.isNotEmpty)
              IconButton(
                icon: const Icon(Icons.share),
                onPressed: () => Share.shareXFiles([XFile(_resolvedPath)]),
              ),
            PopupMenuButton<double>(
              icon: const Icon(Icons.text_fields),
              onSelected: (v) => setState(() => _fontSize = v),
              itemBuilder: (_) => [10, 11, 12, 13, 14, 16, 18]
                  .map(
                    (s) => PopupMenuItem(
                      value: s.toDouble(),
                      child: Text(
                        '$s pt',
                        style: TextStyle(
                          fontWeight: _fontSize == s ? FontWeight.bold : null,
                        ),
                      ),
                    ),
                  )
                  .toList(),
            ),
          ],
        ),
        body: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : TextField(
                controller: _ctrl,
                maxLines: null,
                expands: true,
                keyboardType: TextInputType.multiline,
                style: TextStyle(
                  fontFamily: 'monospace',
                  fontSize: _fontSize,
                  height: 1.5,
                ),
                decoration: const InputDecoration(
                  contentPadding: EdgeInsets.all(16),
                  border: InputBorder.none,
                ),
              ),
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
}
