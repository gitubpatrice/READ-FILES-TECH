import 'dart:io';
import 'dart:isolate';
import 'package:crypto/crypto.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../widgets/rft_picker_screen.dart';

/// Capture le seul digest émis par une `Hash.startChunkedConversion`.
class _DigestSink implements Sink<Digest> {
  Digest? value;
  @override
  void add(Digest data) {
    value = data;
  }

  @override
  void close() {}
}

/// Calcule MD5/SHA-1/SHA-256/SHA-512 d'un fichier en streamant par chunks
/// (alimente les 4 sinks en parallèle, pas de copie complète en RAM).
/// Tourne dans un Isolate via [Isolate.run] — UI thread reste fluide même
/// sur 50+ Mo. Retourne une map {algo: hex}.
Future<Map<String, String>> _hashFileAllAlgos(String path) async {
  return Isolate.run(() async {
    final file = File(path);
    final md5Sink = _DigestSink();
    final md5Conv = md5.startChunkedConversion(md5Sink);
    final sha1Sink = _DigestSink();
    final sha1Conv = sha1.startChunkedConversion(sha1Sink);
    final sha256Sink = _DigestSink();
    final sha256Conv = sha256.startChunkedConversion(sha256Sink);
    final sha512Sink = _DigestSink();
    final sha512Conv = sha512.startChunkedConversion(sha512Sink);
    await for (final chunk in file.openRead()) {
      md5Conv.add(chunk);
      sha1Conv.add(chunk);
      sha256Conv.add(chunk);
      sha512Conv.add(chunk);
    }
    md5Conv.close();
    sha1Conv.close();
    sha256Conv.close();
    sha512Conv.close();
    return {
      'MD5': md5Sink.value!.toString(),
      'SHA-1': sha1Sink.value!.toString(),
      'SHA-256': sha256Sink.value!.toString(),
      'SHA-512': sha512Sink.value!.toString(),
    };
  });
}

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
    final path = await RftPickerScreen.pickOne(
      context,
      title: 'Calculer le hash',
    );
    if (path == null) return;
    final name = PathUtils.fileName(path);
    setState(() {
      _path = path;
      _name = name;
      _hashes = {};
      _isComputing = true;
    });
    await _compute(path);
  }

  Future<void> _compute(String path) async {
    try {
      final size = await File(path).length();
      final hashes = await _hashFileAllAlgos(path);
      if (!mounted) return;
      setState(() {
        _fileSize = size;
        _hashes = hashes;
        _isComputing = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _isComputing = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Erreur lors du calcul du hash')),
      );
    }
  }

  void _copy(String value) {
    Clipboard.setData(ClipboardData(text: value));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Copié : $value'),
        duration: const Duration(seconds: 2),
      ),
    );
  }

  String _formatSize(int bytes) => FormatUtils.bytesStorage(bytes);

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
            Icon(
              Icons.fingerprint,
              size: 88,
              color: Theme.of(
                context,
              ).colorScheme.primary.withValues(alpha: 0.35),
            ),
            const SizedBox(height: 24),
            Text(
              'Hash de fichier',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            Text(
              "Calculez MD5, SHA-1, SHA-256, SHA-512 de n'importe quel fichier",
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: Colors.grey),
              textAlign: TextAlign.center,
            ),
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
          Row(
            children: [
              const Icon(Icons.insert_drive_file_outlined, color: Colors.blue),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  _name!,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w500),
                ),
              ),
              TextButton(onPressed: _pickFile, child: const Text('Changer')),
            ],
          ),
          if (_fileSize > 0)
            Padding(
              padding: const EdgeInsets.only(left: 32, bottom: 4),
              child: Text(
                _formatSize(_fileSize),
                style: const TextStyle(color: Colors.grey, fontSize: 12),
              ),
            ),
          const Divider(height: 24),
          if (_isComputing)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Column(
                  children: [
                    CircularProgressIndicator(),
                    SizedBox(height: 16),
                    Text('Calcul en cours…'),
                  ],
                ),
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
          Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.primaryContainer,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  algo,
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                ),
              ),
              const Spacer(),
              IconButton(
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                icon: const Icon(Icons.copy, size: 18),
                onPressed: () => _copy(hash),
              ),
            ],
          ),
          const SizedBox(height: 8),
          SelectableText(
            hash,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
          ),
        ],
      ),
    );
  }
}
