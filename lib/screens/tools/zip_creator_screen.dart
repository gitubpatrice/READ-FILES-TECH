import 'dart:io';
import 'dart:isolate';
import 'package:archive/archive.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import '../../services/output_storage_service.dart';
import '../../utils/atomic_write.dart';
import '../../utils/file_caps.dart';
import '../../utils/snack_utils.dart';
import '../../widgets/rft_picker_screen.dart';

class ZipCreatorScreen extends StatefulWidget {
  const ZipCreatorScreen({super.key});

  @override
  State<ZipCreatorScreen> createState() => _ZipCreatorScreenState();
}

class _ZipCreatorScreenState extends State<ZipCreatorScreen> {
  final List<String> _files = [];
  bool _isProcessing = false;

  String _formatSize(int bytes) => FormatUtils.bytesStorage(bytes);

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
    final snack = SnackTarget.of(context);
    setState(() => _isProcessing = true);
    try {
      // V-H4 (audit 2026-08-02) — cet écran était le seul outil sans aucun
      // `FileCaps`. `readAsBytes()` sans borne, sur autant de fichiers que
      // l'utilisateur en avait sélectionnés, TOUS vivants en RAM en même
      // temps, puis `ZipEncoder().encode()` synchrone sur le thread UI.
      // Une vidéo de 1,5 Go tuait l'app ; 200 Mo la figeaient plusieurs
      // secondes avec un pic RAM au triple (source + archive + sortie).
      //
      // Le cap porte sur le CUMUL, pas sur chaque fichier pris isolément :
      // c'est la somme qui tient en mémoire, et vingt fichiers de 100 Mo
      // passaient chacun un contrôle unitaire sans problème.
      int total = 0;
      for (final path in _files) {
        total += await File(path).length();
      }
      if (total > FileCaps.zipCreateTotal) {
        throw Exception(
          'Sélection trop volumineuse : '
          '${total ~/ (1024 * 1024)} Mo (max '
          '${FileCaps.zipCreateTotal ~/ (1024 * 1024)} Mo).',
        );
      }

      final archive = Archive();
      for (final path in _files) {
        final name = PathUtils.fileName(path);
        final bytes = await File(path).readAsBytes();
        archive.addFile(ArchiveFile(name, bytes.length, bytes));
      }
      // L'encodage part sur un Isolate : c'est la doctrine du projet
      // (`hash_screen`, `convert_screen`, `docx_viewer`) et cet écran en
      // était la seule exception.
      // `encode` rendait un `Uint8List?` en archive 3 ; la 4.x le rend non
      // nullable, d'où la disparition du test de nullité qui suivait.
      final encoded = await Isolate.run(() => ZipEncoder().encode(archive));

      // Sauvegarde dans Files Tech/Conversions/ — apparaîtra dans Récents
      // via le scan auto-output de RftPickerScreen.
      final out = await OutputStorageService().reserveFile(
        category: OutputCategory.conversions,
        suggestedName: 'archive',
        extension: 'zip',
      );
      await atomicWriteBytes(out.path, encoded);
      final outPath = out.path;

      // Le libellé se calcule AVANT le vidage de `_files`, sans quoi il
      // annoncerait « 0 fichier » — l'ordre était déjà correct, il le reste
      // explicitement.
      final count = _files.length;
      if (mounted) {
        setState(() {
          _isProcessing = false;
          _files.clear();
        });
      } else {
        _isProcessing = false;
        _files.clear();
      }
      snack.info(
        'ZIP créé : $count fichier${count > 1 ? 's' : ''}',
        action: SnackBarAction(
          label: 'Partager',
          onPressed: () => Share.shareXFiles([XFile(outPath)]),
        ),
      );
    } catch (e) {
      if (mounted) setState(() => _isProcessing = false);
      snack.error('Erreur : $e');
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
              final name = PathUtils.fileName(path);
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
