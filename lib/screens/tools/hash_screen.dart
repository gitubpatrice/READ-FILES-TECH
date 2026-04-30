import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../widgets/rft_picker_screen.dart';

class HashScreen extends StatefulWidget {
  const HashScreen({super.key});

  @override
  State<HashScreen> createState() => _HashScreenState();
}

class _HashScreenState extends State<HashScreen> {
  String? _path, _name;
  bool _isComputing = false;
  Map<String, String> _hashes = {};
  int _fileSize = 0;

  Future<void> _pickFile() async {
    final path = await RftPickerScreen.pickOne(context,
        title: 'Calculer le hash');
    if (path == null) return;
    final name = path.split(RegExp(r'[/\\]')).last;
    setState(() {
      _path = path;
      _name = name;
      _hashes = {};
      _isComputing = true;
    });
    await _compute(path);
  }

  Future<void> _compute(String path) async {
    final bytes = await File(path).readAsBytes();
    setState(() {
      _fileSize = bytes.length;
      _hashes = {
        'MD5':     md5.convert(bytes).toString(),
        'SHA-1':   sha1.convert(bytes).toString(),
        'SHA-256': sha256.convert(bytes).toString(),
        'SHA-512': sha512.convert(bytes).toString(),
      };
      _isComputing = false;
    });
  }

  void _copy(String value) {
    Clipboard.setData(ClipboardData(text: value));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Copié : $value'),
          duration: const Duration(seconds: 2)),
    );
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Hash de fichier')),
      body: _path == null ? _buildPicker() : _buildResult(),
    );
  }

  Widget _buildPicker() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.fingerprint, size: 88,
                color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.35)),
            const SizedBox(height: 24),
            Text('Hash de fichier', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text("Calculez MD5, SHA-1, SHA-256, SHA-512 de n'importe quel fichier",
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: Colors.grey),
                textAlign: TextAlign.center),
            const SizedBox(height: 32),
            FilledButton.icon(
              onPressed: _pickFile,
              icon: const Icon(Icons.folder_open),
              label: const Text('Choisir un fichier'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildResult() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.insert_drive_file_outlined, color: Colors.blue),
            const SizedBox(width: 8),
            Expanded(child: Text(_name!, overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w500))),
            TextButton(onPressed: _pickFile, child: const Text('Changer')),
          ]),
          if (_fileSize > 0)
            Padding(
              padding: const EdgeInsets.only(left: 32, bottom: 4),
              child: Text(_formatSize(_fileSize),
                  style: const TextStyle(color: Colors.grey, fontSize: 12)),
            ),
          const Divider(height: 24),
          if (_isComputing)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Column(children: [
                  CircularProgressIndicator(),
                  SizedBox(height: 16),
                  Text('Calcul en cours…'),
                ]),
              ),
            )
          else ...[
            Text('Empreintes', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 12),
            ..._hashes.entries.map((e) => _hashCard(e.key, e.value)),
          ],
        ],
      ),
    );
  }

  Widget _hashCard(String algo, String hash) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Theme.of(context).dividerColor),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(algo,
                  style: TextStyle(
                      fontSize: 11, fontWeight: FontWeight.w700,
                      color: Theme.of(context).colorScheme.primary)),
            ),
            const Spacer(),
            IconButton(
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints(),
              icon: const Icon(Icons.copy, size: 18),
              onPressed: () => _copy(hash),
            ),
          ]),
          const SizedBox(height: 8),
          SelectableText(hash,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
        ],
      ),
    );
  }
}
