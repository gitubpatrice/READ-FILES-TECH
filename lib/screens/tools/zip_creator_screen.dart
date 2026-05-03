import 'dart:io';
import 'package:archive/archive.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import '../../widgets/rft_picker_screen.dart';

class ZipCreatorScreen extends StatefulWidget {
  const ZipCreatorScreen({super.key});

  @override
  State<ZipCreatorScreen> createState() => _ZipCreatorScreenState();
}

class _ZipCreatorScreenState extends State<ZipCreatorScreen> {
  final List<String> _files = [];
  bool _isProcessing = false;

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  int _totalSize() => _files.fold(0, (sum, p) {
    try {
      return sum + File(p).lengthSync();
    } catch (_) {
      return sum;
    }
  });

  Future<void> _addFiles() async {
    final paths = await RftPickerScreen.pickMany(
      context,
      title: 'Choisir des fichiers à compresser',
    );
    if (paths == null || paths.isEmpty) return;
    setState(() {
      for (final p in paths) {
        if (!_files.contains(p)) _files.add(p);
      }
    });
  }

  Future<void> _create() async {
    if (_files.isEmpty) return;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isProcessing = true);
    try {
      final archive = Archive();
      for (final path in _files) {
        final name = path.split(RegExp(r'[/\\]')).last;
        final bytes = await File(path).readAsBytes();
        archive.addFile(ArchiveFile(name, bytes.length, bytes));
      }
      final encoded = ZipEncoder().encode(archive);
      if (encoded == null) throw Exception('Compression échouée');

      final dir = await getApplicationDocumentsDirectory();
      final ts = DateTime.now().millisecondsSinceEpoch;
      final outPath = '${dir.path}/archive_$ts.zip';
      await File(outPath).writeAsBytes(encoded);

      if (!mounted) return;
      setState(() => _isProcessing = false);
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            'ZIP créé : ${_files.length} fichier${_files.length > 1 ? 's' : ''}',
          ),
          action: SnackBarAction(
            label: 'Partager',
            onPressed: () => Share.shareXFiles([XFile(outPath)]),
          ),
        ),
      );
      setState(() => _files.clear());
    } catch (e) {
      if (!mounted) return;
      setState(() => _isProcessing = false);
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Créer un ZIP')),
      body: _files.isEmpty ? _buildEmpty() : _buildList(),
      bottomNavigationBar: _files.isNotEmpty
          ? SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: FilledButton.icon(
                  onPressed: _isProcessing ? null : _create,
                  icon: _isProcessing
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.folder_zip_outlined),
                  label: Text(
                    _isProcessing
                        ? 'Compression…'
                        : 'Créer le ZIP (${_files.length} fichier${_files.length > 1 ? 's' : ''})',
                  ),
                  style: FilledButton.styleFrom(minimumSize: const Size(0, 48)),
                ),
              ),
            )
          : null,
    );
  }

  Widget _buildEmpty() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.folder_zip_outlined,
              size: 88,
              color: Theme.of(
                context,
              ).colorScheme.primary.withValues(alpha: 0.35),
            ),
            const SizedBox(height: 24),
            Text('Créer un ZIP', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(
              'Sélectionnez des fichiers à compresser en archive ZIP',
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: Colors.grey),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            FilledButton.icon(
              onPressed: _addFiles,
              icon: const Icon(Icons.add),
              label: const Text('Ajouter des fichiers'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildList() {
    final total = _totalSize();
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Row(
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${_files.length} fichier${_files.length > 1 ? 's' : ''}',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  Text(
                    _formatSize(total),
                    style: const TextStyle(fontSize: 11, color: Colors.grey),
                  ),
                ],
              ),
              const Spacer(),
              TextButton.icon(
                onPressed: _addFiles,
                icon: const Icon(Icons.add, size: 18),
                label: const Text('Ajouter'),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.only(bottom: 80),
            itemCount: _files.length,
            itemBuilder: (_, i) {
              final path = _files[i];
              final name = path.split(RegExp(r'[/\\]')).last;
              int? size;
              try {
                size = File(path).lengthSync();
              } catch (_) {}
              return ListTile(
                leading: Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: Colors.orange.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(
                    Icons.insert_drive_file_outlined,
                    color: Colors.orange,
                    size: 20,
                  ),
                ),
                title: Text(
                  name,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 13),
                ),
                subtitle: size != null
                    ? Text(
                        _formatSize(size),
                        style: const TextStyle(
                          fontSize: 11,
                          color: Colors.grey,
                        ),
                      )
                    : null,
                trailing: IconButton(
                  icon: const Icon(Icons.close, size: 18),
                  onPressed: () => setState(() => _files.removeAt(i)),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}
