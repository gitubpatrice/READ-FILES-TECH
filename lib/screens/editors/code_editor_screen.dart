import 'dart:io';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:share_plus/share_plus.dart';

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

  String get _name => _resolvedPath.isEmpty
      ? ''
      : _resolvedPath.split(RegExp(r'[/\\]')).last;

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
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['txt','md','csv','xml','json','html','css','js','php','dart'],
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
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erreur de lecture : $e')),
        );
      }
    }
  }

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isSaving = true);
    try {
      await File(_resolvedPath).writeAsString(_ctrl.text);
      _original = _ctrl.text;
      setState(() { _modified = false; _isSaving = false; });
      if (!mounted) return;
      messenger.showSnackBar(
        const SnackBar(content: Text('Fichier sauvegardé'), duration: Duration(seconds: 1)),
      );
    } catch (e) {
      setState(() => _isSaving = false);
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text('Erreur de sauvegarde : $e')));
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
          final leave = await _onWillPop();
          if (leave) nav.pop();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Row(children: [
            Expanded(child: Text(_name.isEmpty ? 'Éditeur' : _name,
                overflow: TextOverflow.ellipsis)),
            if (_modified)
              Container(
                margin: const EdgeInsets.only(left: 8),
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.orange.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: Colors.orange.withValues(alpha: 0.5)),
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
                    ? const SizedBox(width: 18, height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.save),
                onPressed: _isSaving ? null : _save,
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
                  .map((s) => PopupMenuItem(
                        value: s.toDouble(),
                        child: Text('$s pt',
                            style: TextStyle(
                                fontWeight: _fontSize == s ? FontWeight.bold : null)),
                      ))
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
