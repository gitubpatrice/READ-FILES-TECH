import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;
import 'package:share_plus/share_plus.dart';
import '../../services/output_storage_service.dart';
import '../../widgets/cloud_share_row.dart';

class CompressScreen extends StatefulWidget {
  const CompressScreen({super.key});

  @override
  State<CompressScreen> createState() => _CompressScreenState();
}

class _CompressScreenState extends State<CompressScreen> {
  final _storage = OutputStorageService();
  String? _sourcePath;
  String? _outputPath;
  int _sourceSize = 0;
  int? _outputSize;
  int _quality = 80;
  int _maxWidth = 1920;
  bool _busy = false;

  Future<void> _pick() async {
    final res = await FilePicker.platform.pickFiles(type: FileType.image);
    if (res == null || res.files.single.path == null) return;
    final file = File(res.files.single.path!);
    setState(() {
      _sourcePath = file.path;
      _sourceSize = file.lengthSync();
      _outputSize = null;
    });
  }

  Future<void> _compress() async {
    if (_sourcePath == null) return;
    setState(() {
      _busy = true;
      _outputSize = null;
      _outputPath = null;
    });
    final messenger = ScaffoldMessenger.of(context);
    try {
      final bytes = await File(_sourcePath!).readAsBytes();
      final decoded = img.decodeImage(bytes);
      if (decoded == null) throw 'Image illisible';
      img.Image resized = decoded;
      if (decoded.width > _maxWidth) {
        resized = img.copyResize(decoded, width: _maxWidth);
      }
      final encoded = Uint8List.fromList(
        img.encodeJpg(resized, quality: _quality),
      );
      final base = _sourcePath!
          .split(RegExp(r'[/\\]'))
          .last
          .replaceAll(RegExp(r'\.[^.]+$'), '');
      final out = await _storage.reserveFile(
        category: OutputCategory.compressions,
        suggestedName: '${base}_compressed',
        extension: 'jpg',
      );
      await out.writeAsBytes(encoded);
      final autoShare = await _storage.getAutoShare();
      if (!mounted) return;
      setState(() {
        _busy = false;
        _outputSize = encoded.length;
        _outputPath = out.path;
      });
      if (autoShare) {
        await Share.shareXFiles([XFile(out.path)]);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  String _fmt(int b) {
    if (b < 1024) return '$b B';
    if (b < 1024 * 1024) return '${(b / 1024).toStringAsFixed(0)} KB';
    return '${(b / (1024 * 1024)).toStringAsFixed(2)} MB';
  }

  @override
  Widget build(BuildContext context) {
    final ratio = (_sourceSize > 0 && _outputSize != null)
        ? ((1 - _outputSize! / _sourceSize) * 100).clamp(0, 99).toInt()
        : null;

    return Scaffold(
      appBar: AppBar(title: const Text('Compresser image')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: ListTile(
              leading: const Icon(Icons.image_outlined),
              title: Text(
                _sourcePath == null
                    ? 'Choisir une image'
                    : _sourcePath!.split(RegExp(r'[/\\]')).last,
              ),
              subtitle: _sourcePath == null
                  ? null
                  : Text('Taille : ${_fmt(_sourceSize)}'),
              trailing: const Icon(Icons.folder_open),
              onTap: _pick,
            ),
          ),
          if (_sourcePath != null) ...[
            const SizedBox(height: 16),
            const Text('Qualité JPEG'),
            Slider(
              value: _quality.toDouble(),
              min: 10,
              max: 100,
              divisions: 18,
              label: '$_quality',
              onChanged: (v) => setState(() => _quality = v.toInt()),
            ),
            Text(
              'Qualité : $_quality / 100',
              style: const TextStyle(fontSize: 12, color: Colors.grey),
            ),
            const SizedBox(height: 16),
            const Text('Largeur maximum (px)'),
            Slider(
              value: _maxWidth.toDouble(),
              min: 480,
              max: 4096,
              divisions: 18,
              label: '$_maxWidth',
              onChanged: (v) => setState(() => _maxWidth = v.toInt()),
            ),
            Text(
              'Largeur max : $_maxWidth px',
              style: const TextStyle(fontSize: 12, color: Colors.grey),
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _busy ? null : _compress,
              icon: _busy
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.compress),
              label: Text(_busy ? 'Compression…' : 'Compresser et partager'),
            ),
            if (_outputSize != null) ...[
              const SizedBox(height: 20),
              Card(
                color: Colors.lightBlue.shade50.withValues(alpha: 0.85),
                shape: RoundedRectangleBorder(
                  side: BorderSide(
                    color: Colors.lightBlue.shade300,
                    width: 1.5,
                  ),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Avant : ${_fmt(_sourceSize)}'),
                      Text('Après : ${_fmt(_outputSize!)}'),
                      if (ratio != null)
                        Text(
                          'Réduction : -$ratio %',
                          style: TextStyle(
                            fontWeight: FontWeight.w700,
                            color: Colors.lightBlue.shade900,
                          ),
                        ),
                      if (_outputPath != null) ...[
                        const SizedBox(height: 8),
                        Text(
                          _outputPath!,
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 11,
                          ),
                        ),
                        const SizedBox(height: 8),
                        CloudShareRow(path: _outputPath!, mime: 'image/jpeg'),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ],
        ],
      ),
    );
  }
}
