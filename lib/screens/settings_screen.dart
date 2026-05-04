import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../services/output_storage_service.dart';
import 'explorer/file_explorer_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _service = OutputStorageService();
  String _basePath = '';
  String? _effectivePath;
  bool _autoShare = true;
  bool _canWrite = true;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final base = await _service.getBasePath();
    final auto = await _service.getAutoShare();
    final ok = await _service.canWriteToConfiguredBase();
    // Pré-crée les sous-dossiers — l'utilisateur les verra dans l'explorateur
    // même sans avoir encore généré de fichier.
    final effective = await _service.ensureFolders();
    if (!mounted) return;
    setState(() {
      _basePath = base;
      _effectivePath = effective;
      _autoShare = auto;
      _canWrite = ok;
      _loading = false;
    });
  }

  void _openFolder() {
    final path = _effectivePath ?? _basePath;
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => FileExplorerScreen(initialPath: path)),
    );
  }

  Future<void> _changeBase() async {
    final messenger = ScaffoldMessenger.of(context);
    final dir = await FilePicker.getDirectoryPath();
    if (dir == null) return;
    try {
      await _service.setBasePath(dir);
      await _load();
    } on ArgumentError catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text('${e.message}')));
    }
  }

  Future<void> _resetBase() async {
    await _service.setBasePath('/storage/emulated/0/Files Tech');
    await _load();
  }

  Future<void> _toggleAutoShare(bool v) async {
    setState(() => _autoShare = v);
    await _service.setAutoShare(v);
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return Scaffold(
      appBar: AppBar(title: const Text('Réglages')),
      body: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          _section('Sauvegarde des fichiers générés'),
          Card(
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.folder_outlined),
                  title: const Text('Dossier de sortie'),
                  subtitle: Text(
                    _basePath,
                    style: const TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 11,
                    ),
                  ),
                  trailing: const Icon(Icons.edit_outlined),
                  onTap: _changeBase,
                ),
                if (_effectivePath != null && _effectivePath != _basePath)
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(10),
                    margin: const EdgeInsets.fromLTRB(8, 0, 8, 8),
                    decoration: BoxDecoration(
                      color: Colors.blue.withValues(alpha: 0.10),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: Colors.blue.withValues(alpha: 0.4),
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Dossier réel utilisé (fallback car configuré inaccessible) :',
                          style: TextStyle(fontSize: 11),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _effectivePath!,
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 10,
                          ),
                        ),
                      ],
                    ),
                  ),
                const Divider(height: 1),
                ListTile(
                  dense: true,
                  leading: const Icon(Icons.folder_open, size: 20),
                  title: const Text(
                    'Ouvrir le dossier dans l\'explorateur',
                    style: TextStyle(fontSize: 13),
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _openFolder,
                ),
                if (!_canWrite)
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(10),
                    margin: const EdgeInsets.fromLTRB(8, 0, 8, 8),
                    decoration: BoxDecoration(
                      color: Colors.orange.withValues(alpha: 0.10),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: Colors.orange.withValues(alpha: 0.4),
                      ),
                    ),
                    child: const Text(
                      'Ce dossier n\'est pas accessible en écriture. Les fichiers '
                      'iront dans le dossier app-privé externe (visible via '
                      '/Android/data/<package>/files/Files Tech/).',
                      style: TextStyle(fontSize: 11, color: Colors.orange),
                    ),
                  ),
                const Divider(height: 1),
                ListTile(
                  dense: true,
                  leading: const Icon(Icons.refresh, size: 20),
                  title: const Text(
                    'Restaurer le dossier par défaut',
                    style: TextStyle(fontSize: 13),
                  ),
                  subtitle: const Text(
                    '/storage/emulated/0/Files Tech',
                    style: TextStyle(fontFamily: 'monospace', fontSize: 10),
                  ),
                  onTap: _resetBase,
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: SwitchListTile(
              secondary: const Icon(Icons.share_outlined),
              title: const Text('Partager automatiquement après création'),
              subtitle: const Text(
                'Ouvre la fenêtre de partage Android dès qu\'un fichier '
                'est généré (scan, conversion, signature, etc.)',
                style: TextStyle(fontSize: 11),
              ),
              value: _autoShare,
              onChanged: _toggleAutoShare,
            ),
          ),

          const SizedBox(height: 16),
          _section('Sous-dossiers utilisés'),
          Card(
            child: Column(
              children: [
                for (final c in OutputCategory.values)
                  ListTile(
                    dense: true,
                    leading: Icon(_iconFor(c), size: 18),
                    title: Text(
                      c.folderName,
                      style: const TextStyle(fontSize: 13),
                    ),
                    subtitle: Text(
                      '$_basePath/${c.folderName}',
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 10,
                      ),
                    ),
                  ),
              ],
            ),
          ),

          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(
                context,
              ).colorScheme.primaryContainer.withValues(alpha: 0.20),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Row(
              children: [
                Icon(Icons.info_outline, size: 18),
                SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Les fichiers que vous générez (scans, conversions, signatures, '
                    'OCR, images sans EXIF) sont automatiquement sauvegardés dans le '
                    'dossier ci-dessus. Vous pouvez les retrouver à tout moment dans '
                    'l\'explorateur, même sans les avoir partagés.',
                    style: TextStyle(fontSize: 12),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  IconData _iconFor(OutputCategory c) {
    switch (c) {
      case OutputCategory.scans:
        return Icons.document_scanner_outlined;
      case OutputCategory.conversions:
        return Icons.transform;
      case OutputCategory.compressions:
        return Icons.compress;
      case OutputCategory.signatures:
        return Icons.draw_outlined;
      case OutputCategory.exifClean:
        return Icons.cleaning_services_outlined;
      case OutputCategory.ocr:
        return Icons.text_snippet_outlined;
    }
  }

  Widget _section(String title) => Padding(
    padding: const EdgeInsets.fromLTRB(4, 8, 4, 6),
    child: Text(
      title,
      style: TextStyle(
        color: Colors.grey.shade600,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.5,
        fontSize: 12,
      ),
    ),
  );
}
