import 'dart:io';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import '../../services/vault_service.dart';

/// Écran de sélection batch pour importer le contenu d'un dossier dans
/// le coffre. Toggle "Inclure sous-dossiers" (coché par défaut), case à cocher
/// par fichier, option "Supprimer originaux après chiffrement" (décoché par
/// défaut — sécurité avant tout).
///
/// Le coffre doit être déverrouillé avant d'arriver ici (sinon
/// [VaultService.importFileSafe] lèverait `StateError`).
class VaultImportFolderScreen extends StatefulWidget {
  /// Chemin absolu du dossier source.
  final String folderPath;
  final VaultService service;

  const VaultImportFolderScreen({
    super.key,
    required this.folderPath,
    required this.service,
  });

  @override
  State<VaultImportFolderScreen> createState() =>
      _VaultImportFolderScreenState();
}

class _VaultImportFolderScreenState extends State<VaultImportFolderScreen> {
  bool _recursive = true;
  bool _deleteOriginals = false;
  bool _scanning = true;
  bool _running = false;
  bool _capReached = false;
  double _progress = 0;

  /// Fichiers détectés dans le dossier (avec sélection courante).
  final List<_Entry> _entries = [];

  /// Cap dur sur le nombre d'entrées listées — protège contre l'OOM si
  /// l'utilisateur sélectionne `/storage/emulated/0` racine par erreur.
  static const _maxEntries = 5000;

  /// Patterns de chemins système à exclure (pas pertinents pour le coffre,
  /// et certains contiennent des fichiers temporaires de gros volume).
  static const _excludedPatterns = [
    '/Android/data/',
    '/Android/obb/',
    '/.thumbnails/',
    '/.cache/',
    '/.trash/',
  ];

  @override
  void initState() {
    super.initState();
    _scan();
  }

  Future<void> _scan() async {
    setState(() {
      _scanning = true;
      _capReached = false;
      _entries.clear();
    });
    try {
      final dir = Directory(widget.folderPath);
      if (!await dir.exists()) {
        if (mounted) setState(() => _scanning = false);
        return;
      }
      await for (final ent in dir.list(
        recursive: _recursive,
        followLinks: false,
      )) {
        if (ent is! File) continue;
        // Filtrage chemins système.
        final path = ent.path.replaceAll('\\', '/');
        if (_excludedPatterns.any(path.contains)) continue;
        try {
          final size = await ent.length();
          _entries.add(_Entry(file: ent, size: size, selected: true));
        } catch (_) {
          // Permission refusée / fichier disparu — on saute.
        }
        // Guard : utilisateur a quitté l'écran pendant le scan async.
        if (!mounted) break;
        // Cap dur — on stoppe le scan et on prévient l'utilisateur.
        if (_entries.length >= _maxEntries) {
          setState(() => _capReached = true);
          break;
        }
      }
      _entries.sort((a, b) => a.file.path.compareTo(b.file.path));
    } catch (_) {
      /* ignore */
    }
    if (mounted) setState(() => _scanning = false);
  }

  int get _selectedCount => _entries.where((e) => e.selected).length;
  int get _selectedBytes =>
      _entries.where((e) => e.selected).fold(0, (s, e) => s + e.size);

  void _toggleAll(bool value) {
    setState(() {
      for (final e in _entries) {
        e.selected = value;
      }
    });
  }

  Future<void> _run() async {
    final messenger = ScaffoldMessenger.of(context);
    final selected = _entries.where((e) => e.selected).toList();
    if (selected.isEmpty) return;

    // Confirmation avant suppression originaux (action destructrice).
    if (_deleteOriginals) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          icon: const Icon(
            Icons.warning_amber_rounded,
            color: Colors.orange,
            size: 36,
          ),
          title: const Text('Supprimer les originaux ?'),
          content: Text(
            'Après chiffrement, ${selected.length} fichier'
            '${selected.length > 1 ? "s" : ""} sera'
            '${selected.length > 1 ? "ont" : ""} **supprimé'
            '${selected.length > 1 ? "s" : ""}** du dossier source. '
            'Action irréversible.',
            style: const TextStyle(fontSize: 13),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Annuler'),
            ),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Supprimer après chiffrement'),
            ),
          ],
        ),
      );
      if (ok != true || !mounted) return;
    }

    setState(() {
      _running = true;
      _progress = 0;
    });

    int ok = 0, skip = 0, fail = 0, deleted = 0;
    for (var i = 0; i < selected.length; i++) {
      final e = selected[i];
      try {
        await widget.service.importFileSafe(e.file);
        ok++;
        if (_deleteOriginals) {
          try {
            await e.file.delete();
            deleted++;
          } catch (_) {
            /* on ne stoppe pas le flow pour ça */
          }
        }
      } on FileSystemException {
        // Homonyme déjà dans le coffre — on saute (ne pas écraser sans demander).
        skip++;
      } catch (_) {
        fail++;
      }
      if (mounted) {
        setState(() => _progress = (i + 1) / selected.length);
      }
    }

    if (!mounted) return;
    setState(() => _running = false);

    final parts = <String>[
      if (ok > 0) '$ok chiffré${ok > 1 ? "s" : ""}',
      if (deleted > 0) '$deleted supprimé${deleted > 1 ? "s" : ""}',
      if (skip > 0) '$skip ignoré${skip > 1 ? "s" : ""} (homonyme)',
      if (fail > 0) '$fail erreur${fail > 1 ? "s" : ""}',
    ];
    messenger.showSnackBar(SnackBar(content: Text(parts.join(' · '))));
    Navigator.of(context).pop(ok); // remonte le compteur
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final folderName = widget.folderPath.split(RegExp(r'[/\\]')).last;

    return Scaffold(
      appBar: AppBar(
        title: Text(folderName, maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          if (!_scanning && !_running)
            IconButton(
              icon: const Icon(Icons.select_all),
              tooltip: 'Tout cocher',
              onPressed: () => _toggleAll(true),
            ),
          if (!_scanning && !_running)
            IconButton(
              icon: const Icon(Icons.deselect),
              tooltip: 'Tout décocher',
              onPressed: () => _toggleAll(false),
            ),
        ],
      ),
      body: _scanning
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                // Options
                Card(
                  margin: const EdgeInsets.fromLTRB(12, 12, 12, 6),
                  child: Column(
                    children: [
                      SwitchListTile(
                        title: const Text('Inclure sous-dossiers'),
                        value: _recursive,
                        onChanged: _running
                            ? null
                            : (v) {
                                setState(() => _recursive = v);
                                _scan();
                              },
                        secondary: const Icon(Icons.folder_copy_outlined),
                      ),
                      const Divider(height: 1),
                      SwitchListTile(
                        title: const Text('Supprimer les originaux après'),
                        subtitle: const Text(
                          'Confirmation requise avant exécution',
                          style: TextStyle(fontSize: 11),
                        ),
                        value: _deleteOriginals,
                        onChanged: _running
                            ? null
                            : (v) => setState(() => _deleteOriginals = v),
                        secondary: Icon(
                          Icons.delete_sweep_outlined,
                          color: _deleteOriginals ? Colors.orange : null,
                        ),
                      ),
                    ],
                  ),
                ),

                if (_capReached)
                  Container(
                    width: double.infinity,
                    margin: const EdgeInsets.symmetric(horizontal: 12),
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: Colors.orange.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: Colors.orange.withValues(alpha: 0.4),
                      ),
                    ),
                    child: Row(
                      children: [
                        const Icon(
                          Icons.warning_amber_rounded,
                          color: Colors.orange,
                          size: 18,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Limite de $_maxEntries fichiers atteinte. '
                            'Choisissez un sous-dossier plus précis pour tout voir.',
                            style: const TextStyle(fontSize: 11),
                          ),
                        ),
                      ],
                    ),
                  ),

                // Liste
                Expanded(
                  child: _entries.isEmpty
                      ? Center(
                          child: Padding(
                            padding: const EdgeInsets.all(32),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  Icons.folder_off_outlined,
                                  size: 56,
                                  color: Colors.grey.shade400,
                                ),
                                const SizedBox(height: 12),
                                const Text('Aucun fichier'),
                                const SizedBox(height: 4),
                                Text(
                                  _recursive
                                      ? 'Le dossier (et ses sous-dossiers) est vide.'
                                      : 'Le dossier est vide. Activez « Inclure sous-dossiers » ?',
                                  textAlign: TextAlign.center,
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: Colors.grey.shade600,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        )
                      : ListView.builder(
                          itemCount: _entries.length,
                          itemBuilder: (_, i) {
                            final e = _entries[i];
                            final relative = _relative(e.file.path);
                            return CheckboxListTile(
                              dense: true,
                              value: e.selected,
                              onChanged: _running
                                  ? null
                                  : (v) =>
                                        setState(() => e.selected = v ?? false),
                              title: Text(
                                relative,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 13),
                              ),
                              subtitle: Text(
                                FormatUtils.bytes(e.size),
                                style: const TextStyle(fontSize: 11),
                              ),
                              secondary: Icon(
                                _iconFor(e.file.path),
                                color: cs.primary,
                              ),
                            );
                          },
                        ),
                ),

                // Footer : progress + bouton
                SafeArea(
                  top: false,
                  child: Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: cs.surfaceContainerHighest.withValues(alpha: 0.4),
                      border: Border(top: BorderSide(color: cs.outlineVariant)),
                    ),
                    child: Column(
                      children: [
                        if (_running) ...[
                          LinearProgressIndicator(value: _progress),
                          const SizedBox(height: 8),
                          Text(
                            'Chiffrement en cours… '
                            '${(_progress * 100).round()}%',
                            style: const TextStyle(fontSize: 12),
                          ),
                          const SizedBox(height: 8),
                        ],
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton.icon(
                            onPressed: (_running || _selectedCount == 0)
                                ? null
                                : _run,
                            icon: const Icon(Icons.lock_outline),
                            label: Text(
                              _selectedCount == 0
                                  ? 'Aucun fichier sélectionné'
                                  : 'Chiffrer $_selectedCount fichier'
                                        '${_selectedCount > 1 ? "s" : ""} '
                                        '(${FormatUtils.bytes(_selectedBytes)}) '
                                        '→ coffre',
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
    );
  }

  String _relative(String path) {
    if (path.startsWith(widget.folderPath)) {
      var rel = path.substring(widget.folderPath.length);
      if (rel.startsWith('/') || rel.startsWith('\\')) {
        rel = rel.substring(1);
      }
      return rel.isEmpty ? path.split(RegExp(r'[/\\]')).last : rel;
    }
    return path.split(RegExp(r'[/\\]')).last;
  }

  IconData _iconFor(String path) {
    final ext = path.toLowerCase().split('.').last;
    switch (ext) {
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'webp':
      case 'heic':
        return Icons.image_outlined;
      case 'mp4':
      case 'mov':
      case 'avi':
      case 'mkv':
        return Icons.movie_outlined;
      case 'mp3':
      case 'wav':
      case 'm4a':
      case 'ogg':
      case 'flac':
        return Icons.music_note_outlined;
      case 'pdf':
        return Icons.picture_as_pdf_outlined;
      case 'doc':
      case 'docx':
      case 'odt':
        return Icons.description_outlined;
      case 'xls':
      case 'xlsx':
      case 'ods':
      case 'csv':
        return Icons.table_chart_outlined;
      case 'zip':
      case 'rar':
      case '7z':
      case 'tar':
      case 'gz':
        return Icons.folder_zip_outlined;
      default:
        return Icons.insert_drive_file_outlined;
    }
  }
}

class _Entry {
  final File file;
  final int size;
  bool selected;
  _Entry({required this.file, required this.size, required this.selected});
}
