import 'dart:io';
import 'package:flutter/material.dart';

/// Renommage en masse — propose un aperçu temps réel des nouveaux noms
/// avant d'appliquer. Tous les fichiers doivent être dans le même dossier
/// (l'explorateur garantit ça car on appelle depuis le mode sélection).
class BulkRenameScreen extends StatefulWidget {
  /// Paths absolus des fichiers à renommer.
  final List<String> paths;
  const BulkRenameScreen({super.key, required this.paths});

  @override
  State<BulkRenameScreen> createState() => _BulkRenameScreenState();
}

enum _Mode { sequence, prefixSuffix, regex }

class _BulkRenameScreenState extends State<BulkRenameScreen> {
  _Mode _mode = _Mode.sequence;
  final _baseCtrl = TextEditingController(text: 'Photo');
  final _startCtrl = TextEditingController(text: '1');
  final _padCtrl = TextEditingController(text: '3');
  final _prefixCtrl = TextEditingController();
  final _suffixCtrl = TextEditingController();
  final _patternCtrl = TextEditingController();
  final _replaceCtrl = TextEditingController();
  bool _keepExtension = true;
  bool _busy = false;

  @override
  void dispose() {
    _baseCtrl.dispose();
    _startCtrl.dispose();
    _padCtrl.dispose();
    _prefixCtrl.dispose();
    _suffixCtrl.dispose();
    _patternCtrl.dispose();
    _replaceCtrl.dispose();
    super.dispose();
  }

  /// Calcule les nouveaux noms (sans renommer). Retourne (oldName, newName)
  /// ou newName == null si le résultat est invalide.
  List<(String, String?)> _computePreview() {
    final out = <(String, String?)>[];
    for (var i = 0; i < widget.paths.length; i++) {
      final old = widget.paths[i].split(RegExp(r'[/\\]')).last;
      final ext = old.contains('.') ? '.${old.split('.').last}' : '';
      final stem = ext.isEmpty
          ? old
          : old.substring(0, old.length - ext.length);
      String? next;
      try {
        switch (_mode) {
          case _Mode.sequence:
            final start = int.tryParse(_startCtrl.text) ?? 1;
            final pad = (int.tryParse(_padCtrl.text) ?? 3).clamp(0, 6);
            final num = (start + i).toString().padLeft(pad, '0');
            next = '${_baseCtrl.text}_$num${_keepExtension ? ext : ''}';
            break;
          case _Mode.prefixSuffix:
            next =
                '${_prefixCtrl.text}$stem${_suffixCtrl.text}${_keepExtension ? ext : ''}';
            break;
          case _Mode.regex:
            if (_patternCtrl.text.isEmpty) {
              next = old;
              break;
            }
            final reg = RegExp(_patternCtrl.text);
            final base = stem.replaceAllMapped(reg, (m) => _replaceCtrl.text);
            next = '$base${_keepExtension ? ext : ''}';
            break;
        }
      } catch (_) {
        next = null;
      }
      out.add((old, next));
    }
    return out;
  }

  /// Validation : nouveaux noms uniques, sans /\, sans .., ni vides.
  String? _validate(List<(String, String?)> preview) {
    final names = <String>{};
    for (final (_, n) in preview) {
      if (n == null || n.isEmpty) return 'Un nouveau nom est invalide';
      if (n.contains('/') || n.contains('\\') || n == '.' || n == '..') {
        return 'Caractères interdits : / \\ ..';
      }
      if (!names.add(n)) return 'Doublon de nom : $n';
    }
    return null;
  }

  Future<void> _apply() async {
    final preview = _computePreview();
    final err = _validate(preview);
    if (err != null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(err)));
      return;
    }
    setState(() => _busy = true);
    int ok = 0, fail = 0;
    for (var i = 0; i < widget.paths.length; i++) {
      final (_, newName) = preview[i];
      final oldPath = widget.paths[i];
      try {
        final sepIdx = oldPath.lastIndexOf(RegExp(r'[/\\]'));
        if (sepIdx < 0) {
          fail++;
          continue;
        }
        final dir = oldPath.substring(0, sepIdx);
        final newPath = '$dir/$newName';
        // Évite d'écraser silencieusement un fichier homonyme préexistant.
        if (newPath != oldPath &&
            await FileSystemEntity.type(newPath) !=
                FileSystemEntityType.notFound) {
          fail++;
          continue;
        }
        final type = FileSystemEntity.typeSync(oldPath);
        if (type == FileSystemEntityType.directory) {
          await Directory(oldPath).rename(newPath);
        } else {
          await File(oldPath).rename(newPath);
        }
        ok++;
      } catch (_) {
        fail++;
      }
    }
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          '$ok renommé${ok > 1 ? 's' : ''}'
          '${fail > 0 ? ' · $fail échec(s)' : ''}',
        ),
      ),
    );
    Navigator.pop(context, ok);
  }

  @override
  Widget build(BuildContext context) {
    final preview = _computePreview();
    final err = _validate(preview);

    return Scaffold(
      appBar: AppBar(
        title: Text(
          'Renommer ${widget.paths.length} fichier${widget.paths.length > 1 ? 's' : ''}',
        ),
      ),
      body: Column(
        children: [
          if (_busy) const LinearProgressIndicator(),
          // Sélecteur de mode
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
            child: SegmentedButton<_Mode>(
              segments: const [
                ButtonSegment(
                  value: _Mode.sequence,
                  label: Text('Numéroter'),
                  icon: Icon(Icons.format_list_numbered),
                ),
                ButtonSegment(
                  value: _Mode.prefixSuffix,
                  label: Text('Préfixe'),
                  icon: Icon(Icons.text_fields),
                ),
                ButtonSegment(
                  value: _Mode.regex,
                  label: Text('Regex'),
                  icon: Icon(Icons.find_replace),
                ),
              ],
              selected: {_mode},
              onSelectionChanged: (s) => setState(() => _mode = s.first),
            ),
          ),

          // Champs selon mode
          Padding(padding: const EdgeInsets.all(12), child: _modeFields()),

          // Preserve extension
          CheckboxListTile(
            dense: true,
            value: _keepExtension,
            onChanged: (v) => setState(() => _keepExtension = v ?? true),
            title: const Text(
              'Conserver l\'extension',
              style: TextStyle(fontSize: 13),
            ),
            controlAffinity: ListTileControlAffinity.leading,
          ),

          if (err != null)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              color: Colors.red.withValues(alpha: 0.10),
              child: Text(
                err,
                style: const TextStyle(color: Colors.red, fontSize: 12),
              ),
            ),

          // Aperçu
          Expanded(
            child: ListView.separated(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              itemCount: preview.length,
              separatorBuilder: (_, i) => const Divider(height: 1),
              itemBuilder: (_, i) {
                final (old, neu) = preview[i];
                final changed = neu != null && neu != old;
                return ListTile(
                  dense: true,
                  leading: Icon(
                    changed ? Icons.arrow_forward : Icons.remove,
                    size: 16,
                    color: changed ? Colors.green : Colors.grey,
                  ),
                  title: Text(
                    old,
                    style: const TextStyle(fontSize: 12, color: Colors.grey),
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    neu ?? '⚠ invalide',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: neu == null ? Colors.red : null,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                );
              },
            ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: FilledButton.icon(
                onPressed: (err != null || _busy) ? null : _apply,
                icon: const Icon(Icons.check),
                label: const Text('Appliquer'),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _modeFields() {
    switch (_mode) {
      case _Mode.sequence:
        return Column(
          children: [
            TextField(
              controller: _baseCtrl,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                labelText: 'Nom de base',
                hintText: 'ex : Photo',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _startCtrl,
                    onChanged: (_) => setState(() {}),
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Début',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _padCtrl,
                    onChanged: (_) => setState(() {}),
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Chiffres',
                      helperText: 'ex 3 → 001',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                  ),
                ),
              ],
            ),
          ],
        );
      case _Mode.prefixSuffix:
        return Column(
          children: [
            TextField(
              controller: _prefixCtrl,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                labelText: 'Préfixe',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _suffixCtrl,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                labelText: 'Suffixe (avant l\'extension)',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
          ],
        );
      case _Mode.regex:
        return Column(
          children: [
            TextField(
              controller: _patternCtrl,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                labelText: 'Motif (regex)',
                hintText: r'ex : IMG_(\d+)',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _replaceCtrl,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                labelText: 'Remplacement',
                hintText: r'ex : Photo_$1',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
          ],
        );
    }
  }
}
