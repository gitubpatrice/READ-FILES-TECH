import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:files_tech_core/files_tech_core.dart';
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

  /// Liste de tous les dossiers de premier niveau du stockage interne,
  /// excluant ceux déjà présents dans les raccourcis colorés et les dossiers
  /// système Android non pertinents pour l'utilisateur.
  List<Directory> _allFolders = [];

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this);
    _load();
    _loadAllFolders();
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

  /// Charge dynamiquement tous les dossiers de premier niveau du stockage
  /// interne. Exclut les dossiers déjà raccourcis (pour éviter doublons),
  /// les dossiers système Android (`Android/`) et les fichiers cachés.
  /// Icône smart dérivée du nom du dossier : reconnaît les patterns
  /// fréquents (photos, vidéos, docs, etc.) en fr/en. Sinon folder neutre.
  IconData _smartIconFor(String name) {
    final n = name.toLowerCase();
    if (RegExp(r'photo|image|picture|dcim|camera').hasMatch(n)) {
      return Icons.photo_camera_outlined;
    }
    if (RegExp(r'vid[ée]o|movie|film|cin[ée]ma').hasMatch(n)) {
      return Icons.videocam_outlined;
    }
    if (RegExp(r'music|audio|sound|son|chanson|podcast').hasMatch(n)) {
      return Icons.music_note_outlined;
    }
    if (RegExp(r'doc|text|note|word|excel|pdf').hasMatch(n)) {
      return Icons.description_outlined;
    }
    if (RegExp(r'download|t[ée]l[ée]chargement').hasMatch(n)) {
      return Icons.download_outlined;
    }
    if (RegExp(r'backup|sauvegarde|archive').hasMatch(n)) {
      return Icons.backup_outlined;
    }
    if (RegExp(r'screenshot|capture').hasMatch(n)) {
      return Icons.screenshot_outlined;
    }
    if (RegExp(r'book|livre|epub|read|lecture').hasMatch(n)) {
      return Icons.menu_book_outlined;
    }
    if (RegExp(r'whatsapp|telegram|signal|messenger|chat').hasMatch(n)) {
      return Icons.chat_outlined;
    }
    if (RegExp(r'zip|tar|rar|7z|archive').hasMatch(n)) {
      return Icons.folder_zip_outlined;
    }
    return Icons.folder_outlined;
  }

  /// Couleur déterministe basée sur le hash du nom — même nom donne toujours
  /// la même couleur, donc le visuel reste stable entre 2 ouvertures du
  /// picker. Palette de 12 couleurs Material 600/700 lisibles sur clair ET
  /// sombre.
  static const _autoPalette = <Color>[
    Color(0xFF1976D2), // bleu
    Color(0xFF43A047), // vert
    Color(0xFFE53935), // rouge
    Color(0xFFFF7043), // orange
    Color(0xFF8E24AA), // pourpre
    Color(0xFFE91E63), // rose
    Color(0xFF00897B), // teal
    Color(0xFF3949AB), // indigo
    Color(0xFF6D4C41), // marron
    Color(0xFF455A64), // bleu marin
    Color(0xFF7CB342), // lime
    Color(0xFF039BE5), // bleu clair
  ];

  Color _autoColorFor(String name) {
    var hash = 0;
    for (final c in name.codeUnits) {
      hash = (hash * 31 + c) & 0x7FFFFFFF;
    }
    return _autoPalette[hash % _autoPalette.length];
  }

  Future<void> _loadAllFolders() async {
    try {
      final root = Directory('/storage/emulated/0');
      if (!await root.exists()) return;
      final shortcutPaths = _shortcuts.map((s) => s.path).toSet();
      // Liste les enfants directs uniquement (pas récursif).
      final entries = await root.list(followLinks: false).toList();
      final folders = entries
          .whereType<Directory>()
          .where((d) {
            final name = d.path.split(RegExp(r'[/\\]')).last;
            if (name.startsWith('.')) return false;
            if (name == 'Android') return false; // dossiers data app peu utiles
            // Ne re-liste pas les chemins déjà dans les raccourcis colorés
            if (shortcutPaths.contains(d.path)) return false;
            return true;
          })
          .toList()
        ..sort((a, b) => a.path.split(RegExp(r'[/\\]')).last.toLowerCase()
            .compareTo(b.path.split(RegExp(r'[/\\]')).last.toLowerCase()));
      if (!mounted) return;
      setState(() => _allFolders = folders);
    } catch (_) {/* perm refusée — silent */}
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
    return SafeArea(
      top: false,
      child: ListView(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 0, 4, 4),
          child: Text(
            'Raccourcis',
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w700,
              color: Colors.grey.shade700,
              letterSpacing: 0.3,
            ),
          ),
        ),
        // Grille 2 colonnes : raccourcis colorés vers les dossiers Android
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisSpacing: 6,
          mainAxisSpacing: 6,
          childAspectRatio: 2.7,
          children: _shortcuts.map((s) => _ShortcutCard(
            shortcut: s,
            onTap: () => _openInExplorer(s.path, s.label, filter: s.filter),
          )).toList(),
        ),
        const SizedBox(height: 16),
        if (_allFolders.isNotEmpty) ...[
          const SizedBox(height: 4),
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 0, 4, 4),
            child: Text(
              'Tous les dossiers',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w700,
                color: Colors.grey.shade700,
                letterSpacing: 0.3,
              ),
            ),
          ),
          // Grille 2 colonnes cohérente avec les raccourcis : couleur auto
          // déterministe (hash du nom) + icône smart (caméra pour Photos,
          // video pour Vidéos, doc pour Docs, etc.).
          GridView.count(
            crossAxisCount: 2,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            crossAxisSpacing: 6,
            mainAxisSpacing: 6,
            childAspectRatio: 2.7,
            children: _allFolders.map((d) {
              final name = d.path.split(RegExp(r'[/\\]')).last;
              final shortcut = _FolderShortcut(
                icon: _smartIconFor(name),
                label: name,
                path: d.path,
                color: _autoColorFor(name),
              );
              return _ShortcutCard(
                shortcut: shortcut,
                onTap: () => _openInExplorer(d.path, name),
              );
            }).toList(),
          ),
        ],

        // "Parcourir un autre dossier" en bas avec marge réduite + SafeArea
        // au niveau du ListView pour ne pas être masqué par la barre de
        // navigation système (geste / 3 boutons).
        const SizedBox(height: 6),
        Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            dense: true,
            leading: Icon(Icons.folder_open,
                color: Theme.of(context).colorScheme.primary),
            title: const Text('Parcourir un autre dossier',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
            subtitle: const Text(
                'Sélecteur (sous-dossiers, SD, etc.)',
                style: TextStyle(fontSize: 11)),
            trailing: const Icon(Icons.chevron_right),
            onTap: _browseAnyFolder,
          ),
        ),
      ],
    ));
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
///
/// **Limitation connue** : en mode picker multi (`widget.multi=true`), ce
/// wrapper ne propage PAS le mode multi à l'explorateur. Le tap fichier dans
/// l'explorateur fait toujours pop avec UN seul path. Pour une sélection
/// multi, l'utilisateur doit passer par l'onglet Récents (qui lui supporte
/// les checkbox), ou faire plusieurs tours de Parcourir → 1 fichier ajouté
/// à la sélection multi parente, puis re-Parcourir, etc. UX acceptable car
/// les flows multi de RFT (vault import, zip create) sont peu utilisés via
/// la grille Parcourir.
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
