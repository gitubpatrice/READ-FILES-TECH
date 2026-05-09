import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;
import 'package:share_plus/share_plus.dart';
import '../../services/output_storage_service.dart';
import '../../utils/atomic_write.dart';
import '../../utils/file_caps.dart';
import '../../utils/image_bounds.dart';
import '../../utils/snack_utils.dart';
import '../../widgets/output_actions_row.dart';

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
    final res = await FilePicker.pickFiles(type: FileType.image);
    if (res == null || res.files.single.path == null) return;
    final file = File(res.files.single.path!);
    setState(() {
      _sourcePath = file.path;
      _sourceSize = file.lengthSync();
      _outputSize = null;
    });
  }

  Future<void> _compress() async {
    if (_sourcePath == null || _busy) return;
    setState(() {
      _busy = true;
      _outputSize = null;
      _outputPath = null;
    });
    try {
      final src = File(_sourcePath!);
      // F5 : cap fichier + dimensions (anti image-bomb).
      final capErr = await checkFileCap(src, FileCaps.imageFile);
      if (capErr != null) throw capErr;
      final bytes = await src.readAsBytes();
      final dimErr = ImageBounds.assertSafeBounds(bytes);
      if (dimErr != null) throw dimErr;
      final decoded = img.decodeImage(bytes);
      if (decoded == null) throw 'Image illisible';
      img.Image resized = decoded;
      if (decoded.width > _maxWidth) {
        resized = img.copyResize(decoded, width: _maxWidth);
      }
      final encoded = Uint8List.fromList(
        img.encodeJpg(resized, quality: _quality),
      );
      final base = PathUtils.fileName(
        _sourcePath!,
      ).replaceAll(RegExp(r'\.[^.]+$'), '');
      final out = await _storage.reserveFile(
        category: OutputCategory.compressions,
        suggestedName: '${base}_compressed',
        extension: 'jpg',
      );
      await atomicWriteBytes(out.path, encoded);
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
      showErrorSnack(context, 'Erreur : $e');
    }
  }

  // Alias local — délègue à FormatUtils (uniformise affichage tailles).
  String _fmt(int b) => FormatUtils.bytesStorage(b);

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
                    : PathUtils.fileName(_sourcePath!),
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
                        OutputActionsRow(
                          path: _outputPath!,
                          mime: 'image/jpeg',
                        ),
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
