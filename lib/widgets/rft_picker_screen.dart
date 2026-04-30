import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../models/recent_file.dart';
import '../services/recent_files_service.dart';
import '../screens/explorer/file_explorer_screen.dart';

/// Picker custom pour Read Files Tech : remplace le Storage Access Framework
/// Android brut par une UI à 2 onglets, plus claire pour l'utilisateur.
///
/// - **Récents** : derniers fichiers ouverts (depuis RecentFilesService)
/// - **Parcourir** : grille de raccourcis colorés vers les dossiers Android
///   standards (Téléchargements, Documents, DCIM, Pictures, etc.) +
///   "Parcourir un autre dossier" (SAF directory) + "Picker système" fallback
///
/// Mode multi-sélection optionnel pour les flows d'import.
///
/// Retourne via Navigator.pop :
/// - `String` (path) si mode mono
/// - `List<String>` (paths) si mode multi
class RftPickerScreen extends StatefulWidget {
  final String title;
  final bool multi;
  /// Filtre d'extensions optionnel (ex: {'pdf','docx'}). Si null, accepte tout.
  final Set<String>? extensions;
  const RftPickerScreen({
    super.key,
    this.title = 'Choisir un fichier',
    this.multi = false,
    this.extensions,
  });

  /// Helper mono-sélection.
  static Future<String?> pickOne(BuildContext context, {
    String? title,
    Set<String>? extensions,
  }) {
    return Navigator.push<String>(context, MaterialPageRoute(
      builder: (_) => RftPickerScreen(
        title: title ?? 'Choisir un fichier',
        multi: false,
        extensions: extensions,
      ),
    ));
  }

  /// Helper multi-sélection.
  static Future<List<String>?> pickMany(BuildContext context, {
    String? title,
    Set<String>? extensions,
  }) {
    return Navigator.push<List<String>>(context, MaterialPageRoute(
      builder: (_) => RftPickerScreen(
        title: title ?? 'Choisir des fichiers',
        multi: true,
        extensions: extensions,
      ),
    ));
  }

  @override
  State<RftPickerScreen> createState() => _RftPickerScreenState();
}

/// Description d'un raccourci dossier dans la grille "Parcourir".
class _FolderShortcut {
  final IconData icon;
  final String label;
  final String path;
  final Color color;
  /// Filtre d'extensions facultatif (ex: APKs n'affiche que .apk).
  final Set<String>? filter;
  const _FolderShortcut({
    required this.icon,
    required this.label,
    required this.path,
    required this.color,
    this.filter,
  });
}

class _RftPickerScreenState extends State<RftPickerScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs;
  final _recentService = RecentFilesService();
  List<RecentFile> _recents = [];
  bool _loading = true;
  final List<String> _selected = [];

  /// Grille de raccourcis. Chaque tuile a sa couleur distinctive pour un
  /// repérage visuel immédiat. Couleurs choisies pour bon contraste sur fond
  /// clair ET sombre (saturation ~600 / brightness moyenne).
  static const _shortcuts = [
    _FolderShortcut(
      icon: Icons.download_outlined,
      label: 'Téléchargements',
      path: '/storage/emulated/0/Download',
      color: Color(0xFF43A047), // vert
    ),
    _FolderShortcut(
      icon: Icons.description_outlined,
      label: 'Documents',
      path: '/storage/emulated/0/Documents',
      color: Color(0xFF1976D2), // bleu
    ),
    _FolderShortcut(
      icon: Icons.photo_camera_outlined,
      label: 'Photos',
      path: '/storage/emulated/0/DCIM/Camera',
      color: Color(0xFFE91E63), // rose
    ),
    _FolderShortcut(
      icon: Icons.photo_library_outlined,
      label: 'Galerie',
      path: '/storage/emulated/0/Pictures',
      color: Color(0xFF8E24AA), // pourpre
    ),
    _FolderShortcut(
      icon: Icons.videocam_outlined,
      label: 'Vidéos',
      path: '/storage/emulated/0/Movies',
      color: Color(0xFFE53935), // rouge
    ),
    _FolderShortcut(
      icon: Icons.music_note_outlined,
      label: 'Musique',
      path: '/storage/emulated/0/Music',
      color: Color(0xFFFF7043), // orange
    ),
    _FolderShortcut(
      icon: Icons.screenshot_outlined,
      label: 'Captures',
      path: '/storage/emulated/0/Pictures/Screenshots',
      color: Color(0xFF3949AB), // indigo
    ),
    _FolderShortcut(
      icon: Icons.chat_outlined,
      label: 'WhatsApp',
      path: '/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents',
      color: Color(0xFF25D366), // vert WhatsApp
    ),
    _FolderShortcut(
      icon: Icons.shield_outlined,
      label: 'Files Tech',
      path: '/storage/emulated/0/Files Tech',
      color: Color(0xFF455A64), // bleu marin
    ),
    _FolderShortcut(
      icon: Icons.android_outlined,
      label: 'APKs',
      path: '/storage/emulated/0/Download',
      color: Color(0xFF00897B), // teal
      filter: {'apk'},
    ),
  ];

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this);
    _load();
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final all = await _recentService.load();
    if (!mounted) return;
    // Filtre async : ne garde que les fichiers qui existent encore.
    // existsSync sur N entries jankerait sur stockage lent.
    final checks = await Future.wait(
        all.map((f) async => (await File(f.path).exists()) ? f : null));
    final existing = checks.whereType<RecentFile>().where((f) {
      // Applique aussi le filtre d'extensions si fourni
      if (widget.extensions == null || widget.extensions!.isEmpty) return true;
      return widget.extensions!.contains(f.extension.toLowerCase());
    }).toList();
    if (!mounted) return;
    setState(() { _recents = existing; _loading = false; });
  }

  void _pick(String path) {
    if (widget.multi) {
      setState(() {
        if (!_selected.contains(path)) _selected.add(path);
      });
    } else {
      Navigator.pop(context, path);
    }
  }

  void _toggle(String path) {
    setState(() {
      _selected.contains(path) ? _selected.remove(path) : _selected.add(path);
    });
  }

  Future<void> _browseAnyFolder() async {
    final dir = await FilePicker.platform.getDirectoryPath();
    if (dir == null || !mounted) return;
    final label = dir.split(RegExp(r'[/\\]')).last;
    await _openInExplorer(dir, label.isEmpty ? 'Dossier' : label);
  }

  Future<void> _openInExplorer(String path, String label, {Set<String>? filter}) async {
    final dir = Directory(path);
    if (!dir.existsSync()) {
      // Auto-création silencieuse pour Files Tech (notre dossier app).
      if (label == 'Files Tech') {
        try { await dir.create(recursive: true); } catch (_) {}
      } else {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Dossier "$label" introuvable')));
        return;
      }
    }
    if (!mounted) return;
    // Le picker utilise l'explorateur en mode "sélection au tap" : on capture
    // le path retourné via onPick callback. La navigation pop ramène le path.
    final picked = await Navigator.push<String>(context, MaterialPageRoute(
      builder: (_) => _PickerExplorerWrapper(
        initialPath: path,
        title: label,
        extensionFilter: filter ?? widget.extensions,
      ),
    ));
    if (picked != null && mounted) _pick(picked);
  }

  Future<void> _pickWithSystem() async {
    final res = await FilePicker.platform.pickFiles(
      type: widget.extensions == null
          ? FileType.any
          : FileType.custom,
      allowedExtensions: widget.extensions?.toList(),
      allowMultiple: widget.multi,
    );
    if (res == null || !mounted) return;
    if (widget.multi) {
      setState(() {
        for (final f in res.files) {
          if (f.path != null && !_selected.contains(f.path!)) {
            _selected.add(f.path!);
          }
        }
      });
    } else {
      final path = res.files.isEmpty ? null : res.files.first.path;
      if (path != null) Navigator.pop(context, path);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title),
        bottom: TabBar(
          controller: _tabs,
          tabs: const [
            Tab(text: 'Récents', icon: Icon(Icons.history)),
            Tab(text: 'Parcourir', icon: Icon(Icons.folder_outlined)),
          ],
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : TabBarView(
              controller: _tabs,
              children: [_buildRecents(), _buildBrowse()],
            ),
      floatingActionButton: widget.multi && _selected.isNotEmpty
          ? FloatingActionButton.extended(
              onPressed: () => Navigator.pop(context, _selected),
              icon: const Icon(Icons.check),
              label: Text('Valider (${_selected.length})'),
            )
          : null,
    );
  }

  Widget _buildRecents() {
    if (_recents.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.history,
                  size: 56, color: Colors.grey.shade400),
              const SizedBox(height: 12),
              const Text('Aucun fichier récent',
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
              const SizedBox(height: 6),
              const Text(
                'Ouvrez un fichier depuis l\'onglet Parcourir pour le voir ici.',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 12, color: Colors.grey),
              ),
            ],
          ),
        ),
      );
    }
    return ListView.builder(
      itemCount: _recents.length,
      itemBuilder: (_, i) {
        final f = _recents[i];
        final selected = _selected.contains(f.path);
        return ListTile(
          leading: const Icon(Icons.insert_drive_file_outlined),
          title: Text(f.name, overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 13)),
          subtitle: Text(f.formattedSize,
              style: const TextStyle(fontSize: 11, color: Colors.grey)),
          trailing: widget.multi
              ? Checkbox(value: selected, onChanged: (_) => _toggle(f.path))
              : const Icon(Icons.chevron_right),
          selected: selected,
          onTap: () => widget.multi ? _toggle(f.path) : _pick(f.path),
        );
      },
    );
  }

  Widget _buildBrowse() {
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        // Grille 2 colonnes : raccourcis colorés vers les dossiers Android
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisSpacing: 10,
          mainAxisSpacing: 10,
          childAspectRatio: 2.5,
          children: _shortcuts.map((s) => _ShortcutCard(
            shortcut: s,
            onTap: () => _openInExplorer(s.path, s.label, filter: s.filter),
          )).toList(),
        ),
        const SizedBox(height: 16),
        // Parcourir n'importe quel dossier
        Card(
          child: ListTile(
            leading: Icon(Icons.folder_outlined,
                color: Theme.of(context).colorScheme.primary),
            title: const Text('Parcourir un autre dossier',
                style: TextStyle(fontSize: 13)),
            subtitle: const Text(
                'Choisir n\'importe quel dossier du téléphone',
                style: TextStyle(fontSize: 11)),
            trailing: const Icon(Icons.chevron_right),
            onTap: _browseAnyFolder,
          ),
        ),
        // Picker système Android (fallback Drive, etc.)
        Card(
          child: ListTile(
            leading: Icon(Icons.folder_open,
                color: Theme.of(context).colorScheme.primary),
            title: const Text('Picker système Android',
                style: TextStyle(fontSize: 13)),
            subtitle: const Text(
                'Sélecteur Android (Drive, Téléchargements…)',
                style: TextStyle(fontSize: 11)),
            trailing: const Icon(Icons.chevron_right),
            onTap: _pickWithSystem,
          ),
        ),
      ],
    );
  }
}

/// Tuile colorée pour un raccourci dossier.
class _ShortcutCard extends StatelessWidget {
  final _FolderShortcut shortcut;
  final VoidCallback onTap;
  const _ShortcutCard({required this.shortcut, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(children: [
            Container(
              width: 42, height: 42,
              decoration: BoxDecoration(
                color: shortcut.color.withValues(alpha: 0.18),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(shortcut.icon, color: shortcut.color, size: 22),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(shortcut.label,
                  style: const TextStyle(
                      fontWeight: FontWeight.w600, fontSize: 13),
                  overflow: TextOverflow.ellipsis,
                  maxLines: 2),
            ),
          ]),
        ),
      ),
    );
  }
}

/// Wrapper autour du FileExplorerScreen qui rend la sélection au tap : le tap
/// sur un fichier (au lieu de l'ouvrir) pop avec le path. On réutilise
/// l'explorateur existant pour la navigation, le tri, la recherche, etc.
class _PickerExplorerWrapper extends StatelessWidget {
  final String initialPath;
  final String title;
  final Set<String>? extensionFilter;
  const _PickerExplorerWrapper({
    required this.initialPath,
    required this.title,
    this.extensionFilter,
  });

  @override
  Widget build(BuildContext context) {
    // FileExplorerScreen accepte un extensionFilter et un title custom.
    // Le tap sur un fichier ouvre le viewer normalement — pour la sélection
    // pure on utilise le menu ⋮ → "Choisir" en V2. Pour l'instant, le user
    // tape sur le fichier qui s'ouvre, ce qui est cohérent avec le flow
    // explorer existant. Si on veut une vraie sélection, il faudra étendre
    // FileExplorerScreen avec un mode "pickMode".
    //
    // Note pour l'évolution : ajouter un paramètre `onPickFile` à
    // FileExplorerScreen qui shortcircuit l'ouverture par un pop(path).
    return FileExplorerScreen(
      initialPath: initialPath,
      title: title,
      extensionFilter: extensionFilter,
      pickMode: true, // tap fichier → pop(path) au lieu d'ouvrir le viewer
    );
  }
}
