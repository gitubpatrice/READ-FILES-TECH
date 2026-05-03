import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../../services/exif_service.dart';
import '../../services/output_storage_service.dart';
import '../../widgets/output_actions_row.dart';

class ExifScreen extends StatefulWidget {
  /// Path optionnel : si fourni, l'écran traite directement ce fichier
  /// (depuis un appel "Effacer EXIF" du menu d'un fichier dans l'explorateur).
  final String? initialPath;
  const ExifScreen({super.key, this.initialPath});

  @override
  State<ExifScreen> createState() => _ExifScreenState();
}

class _ExifScreenState extends State<ExifScreen> {
  final _service = ExifService();
  final _storage = OutputStorageService();
  String? _sourcePath;
  Map<String, String> _metadata = {};
  String? _outputPath;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.initialPath != null) {
      _load(widget.initialPath!);
    }
  }

  Future<void> _pick() async {
    final res = await FilePicker.platform.pickFiles(type: FileType.image);
    if (res == null || res.files.single.path == null) return;
    await _load(res.files.single.path!);
  }

  Future<void> _load(String path) async {
    setState(() {
      _busy = true;
      _error = null;
      _sourcePath = path;
      _outputPath = null;
    });
    try {
      final meta = await _service.inspect(File(path));
      if (!mounted) return;
      setState(() {
        _metadata = meta;
        _busy = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '$e';
        _busy = false;
      });
    }
  }

  Future<void> _strip() async {
    if (_sourcePath == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      // 1. Strip → fichier en cache temp
      final tmp = await _service.stripExif(File(_sourcePath!));
      // 2. Copie persistante dans <Files Tech>/Sans-EXIF/
      final ext = tmp.path.split('.').last.toLowerCase();
      final base = _sourcePath!
          .split(RegExp(r'[/\\]'))
          .last
          .replaceAll(RegExp(r'\.[^.]+$'), '');
      final dest = await _storage.reserveFile(
        category: OutputCategory.exifClean,
        suggestedName: '${base}_no_exif',
        extension: ext,
      );
      await tmp.copy(dest.path);
      // Nettoyage du temp
      try {
        await tmp.delete();
      } catch (_) {}
      if (!mounted) return;
      setState(() {
        _outputPath = dest.path;
        _busy = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '$e';
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final hasGps = _metadata.containsKey('GPS');
    return Scaffold(
      appBar: AppBar(title: const Text('Effacer les métadonnées (EXIF)')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (_busy) const LinearProgressIndicator(),
          Card(
            child: ListTile(
              leading: const Icon(Icons.image_outlined),
              title: Text(
                _sourcePath == null
                    ? 'Choisir une image'
                    : _sourcePath!.split(RegExp(r'[/\\]')).last,
              ),
              trailing: const Icon(Icons.folder_open),
              onTap: _busy ? null : _pick,
            ),
          ),
          if (_sourcePath != null) ...[
            const SizedBox(height: 16),
            const Text(
              'Métadonnées détectées',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            if (_metadata.isEmpty)
              const Text(
                'Aucune métadonnée notable',
                style: TextStyle(fontSize: 13, color: Colors.grey),
              )
            else
              Card(
                child: Column(
                  children: _metadata.entries.map((e) {
                    final isPrivacy = e.key == 'GPS' || e.key == 'Prise de vue';
                    return ListTile(
                      dense: true,
                      leading: Icon(
                        isPrivacy ? Icons.warning_amber : Icons.info_outline,
                        size: 18,
                        color: isPrivacy ? Colors.orange : Colors.grey,
                      ),
                      title: Text(e.key, style: const TextStyle(fontSize: 13)),
                      trailing: Text(
                        e.value,
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.grey,
                        ),
                      ),
                    );
                  }).toList(),
                ),
              ),
            if (hasGps) ...[
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Colors.orange.withValues(alpha: 0.10),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: Colors.orange.withValues(alpha: 0.4),
                  ),
                ),
                child: const Row(
                  children: [
                    Icon(
                      Icons.location_on_outlined,
                      size: 18,
                      color: Colors.orange,
                    ),
                    SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Cette image contient une localisation GPS — '
                        'effacez avant tout partage public.',
                        style: TextStyle(fontSize: 11),
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: _busy ? null : _strip,
              icon: const Icon(Icons.cleaning_services_outlined),
              label: const Text('Effacer et partager'),
            ),
            if (_outputPath != null) ...[
              const SizedBox(height: 12),
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
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(
                            Icons.verified_outlined,
                            color: Colors.lightBlue.shade700,
                            size: 18,
                          ),
                          const SizedBox(width: 6),
                          Text(
                            'Image nettoyée et sauvegardée',
                            style: TextStyle(
                              fontWeight: FontWeight.w700,
                              fontSize: 13,
                              color: Colors.lightBlue.shade900,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
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
                        mime: _outputPath!.toLowerCase().endsWith('.png')
                            ? 'image/png'
                            : 'image/jpeg',
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
          if (_error != null) ...[
            const SizedBox(height: 16),
            Text(
              _error!,
              style: const TextStyle(color: Colors.red, fontSize: 12),
            ),
          ],
        ],
      ),
    );
  }
}
