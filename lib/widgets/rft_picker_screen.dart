import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../screens/explorer/file_explorer_screen.dart';
import '../screens/editors/code_editor_screen.dart';
import '../services/output_storage_service.dart';
import 'file_viewer_router.dart';

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

  /// Si true, le picker retourne un chemin de DOSSIER au tap d'un raccourci
  /// ou d'un dossier listé (au lieu d'ouvrir l'explorateur). L'onglet
  /// Récents est masqué (pas de récents pour les dossiers).
  final bool folderMode;

  /// Filtre d'extensions optionnel (ex: {'pdf','docx'}). Si null, accepte tout.
  final Set<String>? extensions;
  const RftPickerScreen({
    super.key,
    this.title = 'Choisir un fichier',
    this.multi = false,
    this.folderMode = false,
    this.extensions,
  });

  /// Helper mono-sélection.
  static Future<String?> pickOne(
    BuildContext context, {
    String? title,
    Set<String>? extensions,
  }) {
    return Navigator.push<String>(
      context,
      MaterialPageRoute(
        builder: (_) => RftPickerScreen(
          title: title ?? 'Choisir un fichier',
          multi: false,
          extensions: extensions,
        ),
      ),
    );
  }

  /// Helper multi-sélection.
  static Future<List<String>?> pickMany(
    BuildContext context, {
    String? title,
    Set<String>? extensions,
  }) {
    return Navigator.push<List<String>>(
      context,
      MaterialPageRoute(
        builder: (_) => RftPickerScreen(
          title: title ?? 'Choisir des fichiers',
          multi: true,
          extensions: extensions,
        ),
      ),
    );
  }

  /// Helper sélection de dossier — UX cohérente avec le picker fichiers
  /// (mêmes raccourcis colorés, même grille "Tous les dossiers", même
  /// "Parcourir un autre dossier" en SAF). Tap sur un raccourci/dossier
  /// retourne directement son chemin.
  static Future<String?> pickFolder(BuildContext context, {String? title}) {
    return Navigator.push<String>(
      context,
      MaterialPageRoute(
        builder: (_) => RftPickerScreen(
          title: title ?? 'Choisir un dossier',
          folderMode: true,
        ),
      ),
    );
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
  late bool _loading;
  final List<String> _selected = [];

  /// En mode folder, on n'a qu'un seul onglet "Parcourir" (pas de Récents).
  bool get _folderMode => widget.folderMode;

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
      path:
          '/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents',
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
    // Mode folder : 1 seul onglet (Parcourir). Mode fichier : 2 onglets.
    _tabs = TabController(length: _folderMode ? 1 : 2, vsync: this);
    _loading = !_folderMode; // chargement Récents uniquement en mode fichier
    if (!_folderMode) _load();
    _loadAllFolders();
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    // 1. Charge la liste persistée des Récents (SharedPreferences).
    final all = await _recentService.load();
    if (!mounted) return;
    // 2. Filtre async : ne garde que les fichiers qui existent encore.
    final checks = await Future.wait(
      all.map((f) async => (await File(f.path).exists()) ? f : null),
    );
    final fromPrefs = checks.whereType<RecentFile>().toList();

    // 3. Auto-découverte : scanne les dossiers d'output (Conversions, OCR,
    //    Scans, Compressions, Signatures, Sans-EXIF) pour faire apparaître
    //    AUSSI les fichiers produits avant l'auto-register OutputActionsRow,
    //    ou si l'index Récents a été vidé. Les paths que l'utilisateur a
    //    explicitement retirés (via menu ⋮ "Retirer") sont exclus du scan.
    final dismissed = await _loadDismissed();
    final fromOutput = await _scanOutputFolders(dismissed);
    // On filtre AUSSI fromPrefs des paths dismissed, par cohérence : si on
    // a retiré un fichier scanné, retire-le aussi des prefs s'il y traîne.
    fromPrefs.removeWhere((f) => dismissed.contains(f.path));
    if (!mounted) return;

    // 4. Fusion : on prend tous les paths uniques, prefs prioritaires (gardent
    //    leur lastOpened d'origine) ; les fichiers d'output non-prefs sont
    //    ajoutés avec leur mtime comme lastOpened approximatif.
    final byPath = <String, RecentFile>{for (final f in fromPrefs) f.path: f};
    for (final f in fromOutput) {
      byPath.putIfAbsent(f.path, () => f);
    }
    var merged = byPath.values.toList()
      ..sort((a, b) => b.lastOpened.compareTo(a.lastOpened));

    // 5. Filtre extensions si le picker en demande.
    if (widget.extensions != null && widget.extensions!.isNotEmpty) {
      merged = merged
          .where((f) => widget.extensions!.contains(f.extension.toLowerCase()))
          .toList();
    }
    if (!mounted) return;
    setState(() {
      _recents = merged;
      _loading = false;
    });
  }

  /// Clé SharedPreferences : ensemble des paths que l'utilisateur a explicitement
  /// retirés via le menu ⋮ "Retirer des récents". Sans cette persistance, le
  /// scan output les réinjecterait au prochain ouverture du picker.
  static const _kDismissedPaths = 'recents_dismissed_paths';

  Future<Set<String>> _loadDismissed() async {
    final prefs = await SharedPreferences.getInstance();
    return (prefs.getStringList(_kDismissedPaths) ?? const <String>[]).toSet();
  }

  Future<void> _addDismissed(String path) async {
    final prefs = await SharedPreferences.getInstance();
    final set = (prefs.getStringList(_kDismissedPaths) ?? const <String>[])
        .toSet();
    set.add(path);
    await prefs.setStringList(_kDismissedPaths, set.toList());
  }

  /// RegExp séparateur path — static final pour éviter recompilation.
  static final _kSepRe = RegExp(r'[/\\]');

  /// Scanne tous les sous-dossiers d'output produits par nos outils.
  /// Source de vérité : [OutputCategory] + base configurée via
  /// [OutputStorageService.getBasePath]. Évite ainsi divergence noms
  /// hardcodés (Scans vs Scanner, Sans-EXIF vs EXIF) et respecte
  /// l'éventuel base path personnalisé.
  ///
  /// Filtre extensions appliqué dès la lecture du nom (évite les `stat()`
  /// inutiles). stat() en parallèle via Future.wait pour SD lente.
  Future<List<RecentFile>> _scanOutputFolders(Set<String> dismissed) async {
    final out = <RecentFile>[];
    final basePath = await OutputStorageService().getBasePath();
    final filter = widget.extensions;
    final hasFilter = filter != null && filter.isNotEmpty;

    for (final cat in OutputCategory.values) {
      final folder = '$basePath/${cat.folderName}';
      try {
        final dir = Directory(folder);
        if (!await dir.exists()) continue;
        final entries = await dir
            .list(followLinks: false)
            .where((e) => e is File)
            .cast<File>()
            .toList();
        final futures = <Future<RecentFile?>>[];
        for (final f in entries) {
          final name = f.path.split(_kSepRe).last;
          if (name.startsWith('.')) continue; // fichiers cachés
          if (dismissed.contains(f.path)) continue;
          if (hasFilter) {
            final dot = name.lastIndexOf('.');
            final ext = dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
            if (!filter.contains(ext)) continue;
          }
          futures.add(() async {
            try {
              final stat = await f.stat();
              return RecentFile(
                path: f.path,
                name: name,
                lastOpened: stat.modified,
                sizeBytes: stat.size,
              );
            } catch (_) {
              return null;
            }
          }());
        }
        final results = await Future.wait(futures);
        out.addAll(results.whereType<RecentFile>());
      } catch (_) {
        /* perm refusée sur le dossier — skip */
      }
    }
    return out;
  }

  /// Charge dynamiquement tous les dossiers de premier niveau du stockage
  /// interne. Exclut les dossiers déjà raccourcis (pour éviter doublons),
  /// les dossiers système Android (`Android/`) et les fichiers cachés.
  /// Icône smart dérivée du nom du dossier : reconnaît les patterns
  /// fréquents (photos, vidéos, docs, etc.) en fr/en. Sinon folder neutre.
  /// Les RegExp sont hissées en `static final` — un seul compile au lieu
  /// de N par rebuild (la grille appelle ce helper pour chaque dossier).
  static final _rePhoto = RegExp(r'photo|image|picture|dcim|camera');
  static final _reVideo = RegExp(r'vid[ée]o|movie|film|cin[ée]ma');
  static final _reMusic = RegExp(r'music|audio|sound|son|chanson|podcast');
  static final _reDoc = RegExp(r'doc|text|note|word|excel|pdf');
  static final _reDownload = RegExp(r'download|t[ée]l[ée]chargement');
  static final _reBackup = RegExp(r'backup|sauvegarde|archive');
  static final _reScreen = RegExp(r'screenshot|capture');
  static final _reBook = RegExp(r'book|livre|epub|read|lecture');
  static final _reChat = RegExp(r'whatsapp|telegram|signal|messenger|chat');
  static final _reZip = RegExp(r'zip|tar|rar|7z|archive');

  IconData _smartIconFor(String name) {
    final n = name.toLowerCase();
    if (_rePhoto.hasMatch(n)) return Icons.photo_camera_outlined;
    if (_reVideo.hasMatch(n)) return Icons.videocam_outlined;
    if (_reMusic.hasMatch(n)) return Icons.music_note_outlined;
    if (_reDoc.hasMatch(n)) return Icons.description_outlined;
    if (_reDownload.hasMatch(n)) return Icons.download_outlined;
    if (_reBackup.hasMatch(n)) return Icons.backup_outlined;
    if (_reScreen.hasMatch(n)) return Icons.screenshot_outlined;
    if (_reBook.hasMatch(n)) return Icons.menu_book_outlined;
    if (_reChat.hasMatch(n)) return Icons.chat_outlined;
    if (_reZip.hasMatch(n)) return Icons.folder_zip_outlined;
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

  /// Formate les extensions en liste lisible : "txt, md, csv, json…".
  String _formatExtensions(Set<String> exts) {
    final list = exts.map((e) => '.$e').toList()..sort();
    if (list.length <= 6) return list.join(', ');
    return '${list.take(6).join(', ')}…';
  }

  /// Heuristique : un raccourci est pertinent pour un filtre donné si son
  /// label suggère qu'il peut contenir ce type de fichier. Évite de proposer
  /// "Photos" quand l'utilisateur cherche du .txt/.md/.dart.
  static const _mediaExts = {
    'jpg',
    'jpeg',
    'png',
    'gif',
    'webp',
    'bmp',
    'heic',
    'mp4',
    'mov',
    'avi',
    'mkv',
    'webm',
    'mp3',
    'wav',
    'flac',
    'm4a',
    'ogg',
    'aac',
  };
  bool _shortcutMatchesFilter(_FolderShortcut s, Set<String>? filter) {
    if (filter == null || filter.isEmpty) return true;
    // Si le filtre contient un raccourci spécifique (ex: APKs filter={apk}),
    // on garde tel quel.
    if (s.filter != null && s.filter!.intersection(filter).isNotEmpty) {
      return true;
    }
    final label = s.label.toLowerCase();
    final filterIsMedia = filter.every(_mediaExts.contains);
    final shortcutIsMedia =
        label.contains('photo') ||
        label.contains('galerie') ||
        label.contains('vid') ||
        label.contains('musique') ||
        label.contains('capture');
    // Filtre média : ne montre que les raccourcis média.
    if (filterIsMedia) return shortcutIsMedia;
    // Filtre non-média (code, docs) : masque les raccourcis purement média.
    if (shortcutIsMedia) return false;
    return true;
  }

  Future<void> _loadAllFolders() async {
    try {
      final root = Directory('/storage/emulated/0');
      if (!await root.exists()) return;
      final shortcutPaths = _shortcuts.map((s) => s.path).toSet();
      // Liste les enfants directs uniquement (pas récursif).
      final entries = await root.list(followLinks: false).toList();
      final folders =
          entries.whereType<Directory>().where((d) {
            final name = d.path.split(RegExp(r'[/\\]')).last;
            if (name.startsWith('.')) return false;
            if (name == 'Android') return false; // dossiers data app peu utiles
            // Ne re-liste pas les chemins déjà dans les raccourcis colorés
            if (shortcutPaths.contains(d.path)) return false;
            return true;
          }).toList()..sort(
            (a, b) => a.path
                .split(RegExp(r'[/\\]'))
                .last
                .toLowerCase()
                .compareTo(b.path.split(RegExp(r'[/\\]')).last.toLowerCase()),
          );
      if (!mounted) return;
      setState(() => _allFolders = folders);
    } catch (_) {
      /* perm refusée — silent */
    }
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
    if (_folderMode) {
      // Mode folder : retour direct du path SAF, pas d'ouverture explorer.
      Navigator.pop(context, dir);
      return;
    }
    final label = dir.split(RegExp(r'[/\\]')).last;
    await _openInExplorer(dir, label.isEmpty ? 'Dossier' : label);
  }

  Future<void> _openInExplorer(
    String path,
    String label, {
    Set<String>? filter,
  }) async {
    // En mode folder, on ne navigue pas dans l'explorateur — tap sur un
    // raccourci ou un dossier listé retourne directement le chemin.
    if (_folderMode) {
      final dir = Directory(path);
      if (!await dir.exists()) {
        if (label == 'Files Tech') {
          try {
            await dir.create(recursive: true);
          } catch (_) {}
        } else {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Dossier "$label" introuvable')),
          );
          return;
        }
      }
      if (!mounted) return;
      Navigator.pop(context, path);
      return;
    }

    final dir = Directory(path);
    if (!await dir.exists()) {
      // Auto-création silencieuse pour Files Tech (notre dossier app).
      if (label == 'Files Tech') {
        try {
          await dir.create(recursive: true);
        } catch (_) {}
      }
      // Pour les autres : on tente quand même la navigation. Si le dossier
      // n'existe vraiment pas, l'explorateur l'affichera vide ; s'il existe
      // mais permission manque, sa bannière prendra le relais.
    }
    if (!mounted) return;
    // Le picker utilise l'explorateur en mode "sélection au tap" : on capture
    // le path retourné via onPick callback. La navigation pop ramène le path.
    final picked = await Navigator.push<String>(
      context,
      MaterialPageRoute(
        builder: (_) => _PickerExplorerWrapper(
          initialPath: path,
          title: label,
          extensionFilter: filter ?? widget.extensions,
        ),
      ),
    );
    if (picked != null && mounted) _pick(picked);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title),
        // Pas de TabBar en mode folder (1 seul onglet visible).
        bottom: _folderMode
            ? null
            : TabBar(
                controller: _tabs,
                tabs: const [
                  Tab(text: 'Récents', icon: Icon(Icons.history)),
                  Tab(text: 'Parcourir', icon: Icon(Icons.folder_outlined)),
                ],
              ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _folderMode
          ? _buildBrowse()
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
              Icon(Icons.history, size: 56, color: Colors.grey.shade400),
              const SizedBox(height: 12),
              const Text(
                'Aucun fichier récent',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 6),
              const Text(
                'Les fichiers ouverts ou produits par les outils '
                '(Conversions, Scanner, OCR…) apparaîtront ici automatiquement.',
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
        final ext = f.extension.toLowerCase();
        return ListTile(
          // Pas de dense: les Récents sont peu nombreux (max 20), on laisse
          // respirer pour faciliter le tap au pouce. Cohérent avec les cards
          // de file_explorer (icone colorée + tap zone confortable).
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 4,
          ),
          leading: Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: _colorForExt(ext).withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(_iconForExt(ext), color: _colorForExt(ext), size: 22),
          ),
          title: Text(
            f.name,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
          ),
          subtitle: Text(
            f.formattedSize,
            style: const TextStyle(fontSize: 11, color: Colors.grey),
          ),
          trailing: widget.multi
              ? Checkbox(value: selected, onChanged: (_) => _toggle(f.path))
              : PopupMenuButton<String>(
                  icon: const Icon(Icons.more_vert, size: 20),
                  tooltip: 'Actions',
                  onSelected: (v) => _onRecentAction(v, f.path),
                  itemBuilder: (_) => const [
                    PopupMenuItem(
                      value: 'open',
                      child: ListTile(
                        leading: Icon(Icons.visibility_outlined),
                        title: Text('Ouvrir'),
                        dense: true,
                      ),
                    ),
                    PopupMenuItem(
                      value: 'edit',
                      child: ListTile(
                        leading: Icon(Icons.edit_outlined),
                        title: Text('Modifier'),
                        dense: true,
                      ),
                    ),
                    PopupMenuItem(
                      value: 'share',
                      child: ListTile(
                        leading: Icon(Icons.share_outlined),
                        title: Text('Partager'),
                        dense: true,
                      ),
                    ),
                    PopupMenuItem(
                      value: 'remove',
                      child: ListTile(
                        leading: Icon(
                          Icons.history_toggle_off_outlined,
                          color: Colors.orange,
                        ),
                        title: Text('Retirer des récents'),
                        dense: true,
                      ),
                    ),
                  ],
                ),
          selected: selected,
          // Tap simple : ouvre directement (UX standard) — comportement
          // historique préservé pour ne pas casser les flows picker.
          onTap: () => widget.multi ? _toggle(f.path) : _pick(f.path),
        );
      },
    );
  }

  /// Routage des actions du menu ⋮ d'un récent.
  Future<void> _onRecentAction(String action, String path) async {
    switch (action) {
      case 'open':
        await FileViewerRouter.open(context, path);
      case 'edit':
        if (!mounted) return;
        await Navigator.push(
          context,
          MaterialPageRoute(builder: (_) => CodeEditorScreen(path: path)),
        );
      case 'share':
        // Anti-symlink : on canonicalise pour éviter qu'un lien planté dans
        // Files Tech/Conversions/ exfiltre un fichier hors zone via Share.
        try {
          final resolved = await File(path).resolveSymbolicLinks();
          if (!mounted) return;
          await Share.shareXFiles([XFile(resolved)]);
        } catch (_) {
          if (!mounted) return;
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('Fichier introuvable')));
        }
      case 'remove':
        // Double action : retire des prefs ET ajoute aux paths "dismissed"
        // pour empêcher la ré-injection par le scan output au prochain load.
        await _recentService.remove(_recents, path);
        await _addDismissed(path);
        if (!mounted) return;
        await _load();
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Retiré des récents'),
            duration: Duration(seconds: 2),
          ),
        );
    }
  }

  /// Couleur déterministe par extension — cohérent avec le file_explorer.
  Color _colorForExt(String ext) {
    switch (ext) {
      case 'pdf':
        return Colors.red;
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'webp':
        return Colors.purple;
      case 'js':
      case 'ts':
        return Colors.amber.shade700;
      case 'html':
      case 'htm':
        return Colors.orange;
      case 'css':
        return Colors.blue;
      case 'json':
        return Colors.deepPurple;
      case 'docx':
      case 'doc':
        return Colors.blue.shade700;
      case 'xlsx':
      case 'xls':
      case 'csv':
        return Colors.green;
      case 'txt':
      case 'md':
        return Colors.blueGrey;
      case 'zip':
      case 'rar':
      case '7z':
        return Colors.brown;
      default:
        return Colors.grey;
    }
  }

  IconData _iconForExt(String ext) {
    switch (ext) {
      case 'pdf':
        return Icons.picture_as_pdf_outlined;
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'webp':
        return Icons.image_outlined;
      case 'mp4':
      case 'avi':
      case 'mov':
        return Icons.videocam_outlined;
      case 'mp3':
      case 'wav':
      case 'flac':
        return Icons.audiotrack_outlined;
      case 'zip':
      case 'rar':
      case '7z':
        return Icons.folder_zip_outlined;
      case 'docx':
      case 'doc':
      case 'odt':
        return Icons.article_outlined;
      case 'xlsx':
      case 'xls':
      case 'csv':
        return Icons.table_chart_outlined;
      case 'html':
      case 'htm':
        return Icons.html_outlined;
      case 'js':
      case 'ts':
        return Icons.javascript_outlined;
      case 'json':
        return Icons.data_object;
      case 'md':
        return Icons.text_snippet_outlined;
      default:
        return Icons.insert_drive_file_outlined;
    }
  }

  Widget _buildBrowse() {
    return SafeArea(
      top: false,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
        children: [
          if (widget.extensions != null && widget.extensions!.isNotEmpty)
            Container(
              margin: const EdgeInsets.fromLTRB(0, 0, 0, 12),
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
              decoration: BoxDecoration(
                color: Theme.of(
                  context,
                ).colorScheme.primary.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  color: Theme.of(
                    context,
                  ).colorScheme.primary.withValues(alpha: 0.25),
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.filter_alt_outlined,
                    color: Theme.of(context).colorScheme.primary,
                    size: 18,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      'Filtre actif : ${_formatExtensions(widget.extensions!)}',
                      style: const TextStyle(fontSize: 12),
                    ),
                  ),
                ],
              ),
            ),
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
            children: _shortcuts
                .where((s) => _shortcutMatchesFilter(s, widget.extensions))
                .map(
                  (s) => _ShortcutCard(
                    shortcut: s,
                    onTap: () =>
                        _openInExplorer(s.path, s.label, filter: s.filter),
                  ),
                )
                .toList(),
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
              leading: Icon(
                Icons.folder_open,
                color: Theme.of(context).colorScheme.primary,
              ),
              title: const Text(
                'Parcourir un autre dossier',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
              ),
              subtitle: const Text(
                'Sélecteur (sous-dossiers, SD, etc.)',
                style: TextStyle(fontSize: 11),
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: _browseAnyFolder,
            ),
          ),
        ],
      ),
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
          child: Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: shortcut.color.withValues(alpha: 0.18),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(shortcut.icon, color: shortcut.color, size: 22),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  shortcut.label,
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                  ),
                  overflow: TextOverflow.ellipsis,
                  maxLines: 2,
                ),
              ),
            ],
          ),
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
