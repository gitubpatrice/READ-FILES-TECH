import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';
import 'package:share_plus/share_plus.dart';
import 'package:intl/intl.dart';
import '../models/recent_file.dart';
import '../services/recent_files_service.dart';
import '../services/update_service.dart';
import 'viewers/txt_viewer_screen.dart';
import 'viewers/md_viewer_screen.dart';
import 'viewers/json_viewer_screen.dart';
import 'viewers/html_viewer_screen.dart';
import 'viewers/csv_viewer_screen.dart';
import 'viewers/xlsx_viewer_screen.dart';
import 'viewers/docx_viewer_screen.dart';
import 'tools/color_picker_screen.dart';
import 'tools/txt_tools_screen.dart';
import 'tools/csv_tools_screen.dart';
import 'tools/diff_screen.dart';
import 'tools/hash_screen.dart';
import 'tools/encode_screen.dart';
import 'tools/format_screen.dart';
import 'tools/content_search_screen.dart';
import 'viewers/pdf_viewer_screen.dart';
import 'viewers/zip_viewer_screen.dart';
import 'editors/code_editor_screen.dart';
import 'editors/csv_editor_screen.dart';
import 'tools/zip_creator_screen.dart';
import 'tools/convert_screen.dart';
import 'tools/ocr_screen.dart';
import 'tools/compress_screen.dart';
import 'tools/scanner_screen.dart';
import 'tools/exif_screen.dart';
import 'vault/vault_screen.dart';
import 'viewers/reader_viewer_screen.dart';
import 'viewers/image_viewer_screen.dart';
import 'explorer/file_explorer_screen.dart';
import 'about_screen.dart';

class HomeScreen extends StatefulWidget {
  final ThemeMode themeMode;
  final ValueChanged<ThemeMode> onThemeChanged;

  const HomeScreen({
    super.key,
    required this.themeMode,
    required this.onThemeChanged,
  });

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _service = RecentFilesService();
  List<RecentFile> _recentFiles = [];
  int _navIndex = 0;
  bool _isLoading = true;

  // Extensions supportées
  static const _supported = [
    'txt', 'csv', 'html', 'htm', 'css', 'js', 'php',
    'docx', 'doc', 'xlsx', 'xls', 'odt', 'ods', 'odp',
    'xml', 'json', 'md', 'pdf', 'zip', 'epub',
  ];

  @override
  void initState() {
    super.initState();
    _loadRecents();
    WidgetsBinding.instance.addPostFrameCallback((_) => _checkUpdate());
  }

  Future<void> _loadRecents() async {
    final files = await _service.load();
    if (mounted) { setState(() { _recentFiles = files; _isLoading = false; }); }
  }

  Future<void> _checkUpdate() async {
    final info = await UpdateService().checkForUpdate();
    if (info == null) return;
    if (!mounted) return;
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: Text('Mise à jour v${info.version} disponible'),
        content: Text(info.body.isNotEmpty
            ? info.body
            : 'Une nouvelle version de Read Files Tech est disponible.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Plus tard')),
          FilledButton(onPressed: () => Navigator.pop(context), child: const Text('OK')),
        ],
      ),
    );
  }

  Future<void> _pickAndOpen() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: _supported,
      allowMultiple: false,
    );
    if (result == null || result.files.single.path == null) return;
    await _openFile(result.files.single.path!);
  }

  Future<void> _openFile(String path) async {
    final updated = await _service.addOrUpdate(_recentFiles, path);
    if (mounted) { setState(() => _recentFiles = updated); }
    if (!mounted) return;
    final ext = path.split('.').last.toLowerCase();
    final screen = _screenForExt(ext, path);
    if (screen == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Format ".$ext" non supporté')),
      );
      return;
    }
    await Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
  }

  Widget? _screenForExt(String ext, String path) {
    switch (ext) {
      case 'txt': case 'xml':
        return TxtViewerScreen(path: path);
      case 'md':
        return MdViewerScreen(path: path);
      case 'json':
        return JsonViewerScreen(path: path);
      case 'html': case 'htm':
        return HtmlViewerScreen(path: path);
      case 'css': case 'js': case 'php':
        return TxtViewerScreen(path: path, highlightLanguage: ext);
      case 'csv':
        return CsvViewerScreen(path: path);
      case 'xlsx': case 'xls': case 'ods':
        return XlsxViewerScreen(path: path);
      case 'docx': case 'doc': case 'odt': case 'odp':
        return DocxViewerScreen(path: path);
      case 'pdf':
        return PdfViewerScreen(path: path);
      case 'zip':
        return ZipViewerScreen(path: path);
      case 'epub':
        return ReaderViewerScreen(path: path, isEpub: true);
      case 'jpg': case 'jpeg': case 'png': case 'gif': case 'webp':
        return ImageViewerScreen(path: path);
      default:
        return null;
    }
  }

  Future<void> _toggleFavorite(RecentFile file) async {
    final updated = await _service.toggleFavorite(_recentFiles, file.path);
    if (mounted) { setState(() => _recentFiles = updated); }
  }

  Future<void> _removeRecent(RecentFile file) async {
    final updated = await _service.remove(_recentFiles, file.path);
    if (mounted) { setState(() => _recentFiles = updated); }
  }

  String _formatDate(DateTime date) {
    final now = DateTime.now();
    final diff = now.difference(date);
    if (diff.inDays == 0) return "Aujourd'hui";
    if (diff.inDays == 1) return 'Hier';
    if (diff.inDays < 7) return 'Il y a ${diff.inDays} jours';
    return DateFormat('dd/MM/yyyy').format(date);
  }

  IconData _themeModeIcon(ThemeMode mode) {
    switch (mode) {
      case ThemeMode.light:  return Icons.light_mode;
      case ThemeMode.dark:   return Icons.dark_mode;
      case ThemeMode.system: return Icons.brightness_auto;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Read Files Tech'),
        actions: [
          if (_navIndex == 0 && _recentFiles.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.search),
              onPressed: () => showSearch(
                context: context,
                delegate: _FileSearchDelegate(_recentFiles, _openFile),
              ),
            ),
          IconButton(
            icon: const Icon(Icons.info_outline),
            tooltip: 'À propos',
            onPressed: () => Navigator.push(context,
                MaterialPageRoute(builder: (_) => const AboutScreen())),
          ),
          PopupMenuButton<ThemeMode>(
            tooltip: 'Thème',
            icon: Icon(_themeModeIcon(widget.themeMode)),
            onSelected: widget.onThemeChanged,
            itemBuilder: (_) => const [
              PopupMenuItem(value: ThemeMode.light,
                  child: ListTile(leading: Icon(Icons.light_mode), title: Text('Clair'))),
              PopupMenuItem(value: ThemeMode.dark,
                  child: ListTile(leading: Icon(Icons.dark_mode), title: Text('Sombre'))),
              PopupMenuItem(value: ThemeMode.system,
                  child: ListTile(leading: Icon(Icons.brightness_auto), title: Text('Automatique'))),
            ],
          ),
        ],
      ),
      body: IndexedStack(
        index: _navIndex,
        children: [
          _HomeTab(
            recentFiles: _recentFiles,
            isLoading: _isLoading,
            onOpen: _openFile,
            onRemove: _removeRecent,
            onToggleFavorite: _toggleFavorite,
            formatDate: _formatDate,
          ),
          _ToolsTab(onPickFile: _pickAndOpen),
          const FileExplorerScreen(),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _navIndex,
        onDestinationSelected: (i) => setState(() => _navIndex = i),
        destinations: const [
          NavigationDestination(
              icon: Icon(Icons.home_outlined),
              selectedIcon: Icon(Icons.home),
              label: 'Accueil'),
          NavigationDestination(
              icon: Icon(Icons.build_outlined),
              selectedIcon: Icon(Icons.build),
              label: 'Outils'),
          NavigationDestination(
              icon: Icon(Icons.folder_outlined),
              selectedIcon: Icon(Icons.folder),
              label: 'Explorateur'),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _pickAndOpen,
        icon: const Icon(Icons.folder_open),
        label: const Text('Ouvrir un fichier'),
      ),
    );
  }
}

// ── Home tab ──────────────────────────────────────────────────────────────────

class _HomeTab extends StatefulWidget {
  final List<RecentFile> recentFiles;
  final bool isLoading;
  final ValueChanged<String> onOpen;
  final ValueChanged<RecentFile> onRemove;
  final ValueChanged<RecentFile> onToggleFavorite;
  final String Function(DateTime) formatDate;

  const _HomeTab({
    required this.recentFiles,
    required this.isLoading,
    required this.onOpen,
    required this.onRemove,
    required this.onToggleFavorite,
    required this.formatDate,
  });

  @override
  State<_HomeTab> createState() => _HomeTabState();
}

class _HomeTabState extends State<_HomeTab> {
  static final _storageChannel = MethodChannel('com.readfilestech/storage');
  int _totalBytes = 0;
  int _freeBytes  = 0;

  static const _quickFolders = [
    (icon: Icons.photo_camera_outlined,   label: 'Caméra',       color: Color(0xFF9C27B0), path: '/storage/emulated/0/DCIM/Camera',     filter: <String>{}),
    (icon: Icons.screenshot_outlined,     label: 'Screenshots',  color: Color(0xFF7B1FA2), path: '/storage/emulated/0/DCIM/Screenshots', filter: <String>{}),
    (icon: Icons.description_outlined,    label: 'Documents',    color: Color(0xFF1976D2), path: '/storage/emulated/0/Documents',      filter: <String>{}),
    (icon: Icons.videocam_outlined,       label: 'Vidéos',       color: Color(0xFFE53935), path: '/storage/emulated/0/Movies',         filter: {'mp4','mov','avi','mkv','webm','3gp'}),
    (icon: Icons.download_outlined,       label: 'Télécharg.',   color: Color(0xFF43A047), path: '/storage/emulated/0/Download',       filter: <String>{}),
    (icon: Icons.android_outlined,        label: 'APKs',         color: Color(0xFF00897B), path: '/storage/emulated/0/Download',       filter: {'apk'}),
    (icon: Icons.photo_library_outlined,  label: 'Galerie',      color: Color(0xFFFF7043), path: '/storage/emulated/0/DCIM',           filter: {'jpg','jpeg','png','gif','webp','heic'}),
  ];

  @override
  void initState() {
    super.initState();
    _loadStorage();
  }

  Future<void> _loadStorage() async {
    try {
      final res = await _storageChannel.invokeMethod<Map>('getStorageInfo');
      if (res != null && mounted) {
        setState(() {
          _totalBytes = (res['total'] as num).toInt();
          _freeBytes  = (res['free']  as num).toInt();
        });
      }
    } catch (_) {}
  }

  String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    if (bytes < 1024 * 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(0)} MB';
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }

  void _openFolder(String path,
      {Set<String>? filter, String? title}) {
    Navigator.push(context, MaterialPageRoute(
        builder: (_) => FileExplorerScreen(
              initialPath: path,
              extensionFilter: (filter != null && filter.isNotEmpty) ? filter : null,
              title: title,
            )));
  }

  Color _extColor(String ext) {
    switch (ext) {
      case 'txt': case 'md':                   return Colors.blueGrey;
      case 'html': case 'htm':                 return Colors.orange;
      case 'css':                              return Colors.blue;
      case 'js':                               return Colors.yellow.shade700;
      case 'csv':                              return Colors.green;
      case 'xlsx': case 'xls': case 'ods':    return Colors.green.shade700;
      case 'docx': case 'doc': case 'odt':    return Colors.blue.shade700;
      case 'json': case 'xml':                 return Colors.purple;
      case 'jpg': case 'jpeg': case 'png':    return Colors.pinkAccent;
      case 'pdf':                              return Colors.red;
      case 'zip':                              return Colors.orange.shade700;
      default:                                 return Colors.grey;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.isLoading) return const Center(child: CircularProgressIndicator());

    final favorites = widget.recentFiles.where((f) => f.isFavorite).toList();
    final recents   = widget.recentFiles.where((f) => !f.isFavorite).toList();
    final lastFile  = widget.recentFiles.isNotEmpty ? widget.recentFiles.first : null;

    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 100),
      children: [

        // ── Stockage ────────────────────────────────────────────────────────
        if (_totalBytes > 0) ...[
          _sectionHeader(context, 'Stockage interne', Icons.storage_outlined, Colors.blueGrey),
          const SizedBox(height: 6),
          _StorageBar(freeBytes: _freeBytes, totalBytes: _totalBytes, formatBytes: _formatBytes),
          const SizedBox(height: 16),
        ],

        // ── Reprendre ────────────────────────────────────────────────────────
        if (lastFile != null) ...[
          _sectionHeader(context, 'Reprendre', Icons.play_circle_outline, Colors.blue),
          const SizedBox(height: 6),
          _ResumeCard(
            file: lastFile,
            extColor: _extColor,
            formatDate: widget.formatDate,
            onTap: () => widget.onOpen(lastFile.path),
          ),
          const SizedBox(height: 16),
        ],

        // ── Accès rapide ─────────────────────────────────────────────────────
        _sectionHeader(context, 'Accès rapide', Icons.grid_view_outlined, Colors.deepOrange),
        const SizedBox(height: 8),
        GridView.count(
          crossAxisCount: 3,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
          childAspectRatio: 1.1,
          children: [
            ..._quickFolders.map((f) => _FolderCard(
              icon: f.icon, label: f.label, color: f.color,
              onTap: () => _openFolder(f.path, filter: f.filter, title: f.label),
            )),
            _FolderCard(
              icon: Icons.camera_alt_outlined,
              label: 'Scanner',
              color: const Color(0xFF00897B),
              onTap: () => Navigator.push(context, MaterialPageRoute(
                  builder: (_) => const ScannerScreen())),
            ),
            _FolderCard(
              icon: Icons.shield_outlined,
              label: 'Coffre fort',
              color: const Color(0xFFD32F2F),
              onTap: () => Navigator.push(context, MaterialPageRoute(
                  builder: (_) => const VaultScreen())),
            ),
          ],
        ),
        const SizedBox(height: 16),

        // ── Favoris ───────────────────────────────────────────────────────────
        if (favorites.isNotEmpty) ...[
          _sectionHeader(context, 'Favoris', Icons.star, Colors.amber),
          ...favorites.map((f) => _fileCard(context, f)),
          const SizedBox(height: 8),
        ],

        // ── Récents ───────────────────────────────────────────────────────────
        _sectionHeader(context, 'Récemment ouverts', Icons.history, Colors.grey),
        if (widget.recentFiles.isEmpty)
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text('Aucun fichier récent',
                style: TextStyle(color: Colors.grey.shade500)),
          )
        else
          ...recents.map((f) => _fileCard(context, f)),
      ],
    );
  }

  Widget _sectionHeader(BuildContext context, String label, IconData icon, Color color) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(2, 4, 2, 0),
      child: Row(children: [
        Icon(icon, size: 14, color: color),
        const SizedBox(width: 5),
        Text(label,
            style: Theme.of(context).textTheme.titleSmall?.copyWith(
                color: Colors.grey.shade600, fontWeight: FontWeight.w600)),
      ]),
    );
  }

  Widget _fileCard(BuildContext context, RecentFile file) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 3),
      child: ListTile(
        dense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
        leading: Stack(children: [
          Container(
            width: 44, height: 44,
            decoration: BoxDecoration(
              color: _extColor(file.extension).withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Center(
              child: Text(file.extension.toUpperCase(),
                  style: TextStyle(
                      color: _extColor(file.extension),
                      fontWeight: FontWeight.w700,
                      fontSize: 10)),
            ),
          ),
          if (file.isFavorite)
            const Positioned(right: 0, top: 0,
                child: Icon(Icons.star, size: 12, color: Colors.amber)),
        ]),
        title: Text(file.name, maxLines: 1, overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 13)),
        subtitle: Text('${widget.formatDate(file.lastOpened)} · ${file.formattedSize}',
            style: const TextStyle(fontSize: 11)),
        trailing: PopupMenuButton<String>(
          onSelected: (v) {
            if (v == 'favorite') widget.onToggleFavorite(file);
            if (v == 'share')    Share.shareXFiles([XFile(file.path)]);
            if (v == 'remove')   widget.onRemove(file);
          },
          itemBuilder: (_) => [
            PopupMenuItem(value: 'favorite', child: ListTile(
                leading: Icon(file.isFavorite ? Icons.star_border : Icons.star, color: Colors.amber),
                title: Text(file.isFavorite ? 'Retirer des favoris' : 'Ajouter aux favoris'))),
            const PopupMenuItem(value: 'share', child: ListTile(
                leading: Icon(Icons.share), title: Text('Partager'))),
            const PopupMenuItem(value: 'remove', child: ListTile(
                leading: Icon(Icons.delete_outline), title: Text('Retirer'))),
          ],
        ),
        onTap: () => widget.onOpen(file.path),
      ),
    );
  }
}

// ── Widgets helpers ───────────────────────────────────────────────────────────

class _StorageBar extends StatelessWidget {
  final int freeBytes;
  final int totalBytes;
  final String Function(int) formatBytes;

  const _StorageBar({
    required this.freeBytes,
    required this.totalBytes,
    required this.formatBytes,
  });

  @override
  Widget build(BuildContext context) {
    final usedBytes = totalBytes - freeBytes;
    final ratio = totalBytes > 0 ? usedBytes / totalBytes : 0.0;
    final color = ratio > 0.9
        ? Colors.red
        : ratio > 0.75
            ? Colors.orange
            : Theme.of(context).colorScheme.primary;

    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Column(children: [
          Row(children: [
            Text(formatBytes(usedBytes),
                style: TextStyle(fontWeight: FontWeight.w700, color: color, fontSize: 15)),
            Text(' utilisés sur ${formatBytes(totalBytes)}',
                style: const TextStyle(fontSize: 13, color: Colors.grey)),
            const Spacer(),
            Text('${formatBytes(freeBytes)} libres',
                style: const TextStyle(fontSize: 12, color: Colors.grey)),
          ]),
          const SizedBox(height: 8),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: ratio.toDouble(),
              minHeight: 7,
              backgroundColor: color.withValues(alpha: 0.15),
              valueColor: AlwaysStoppedAnimation<Color>(color),
            ),
          ),
        ]),
      ),
    );
  }
}

class _ResumeCard extends StatelessWidget {
  final RecentFile file;
  final Color Function(String) extColor;
  final String Function(DateTime) formatDate;
  final VoidCallback onTap;

  const _ResumeCard({
    required this.file,
    required this.extColor,
    required this.formatDate,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = extColor(file.extension);
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Row(children: [
            Container(
              width: 46, height: 46,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Center(
                child: Text(file.extension.toUpperCase(),
                    style: TextStyle(
                        color: color, fontWeight: FontWeight.w800, fontSize: 11)),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(file.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                const SizedBox(height: 2),
                Text('${formatDate(file.lastOpened)} · ${file.formattedSize}',
                    style: const TextStyle(fontSize: 11, color: Colors.grey)),
              ],
            )),
            Icon(Icons.play_circle_fill,
                color: color.withValues(alpha: 0.8), size: 28),
          ]),
        ),
      ),
    );
  }
}

class _FolderCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;

  const _FolderCard({
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                width: 40, height: 40,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: color, size: 22),
              ),
              const SizedBox(height: 6),
              Text(label,
                  style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w500),
                  textAlign: TextAlign.center,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Tools tab ─────────────────────────────────────────────────────────────────

class _ToolsTab extends StatelessWidget {
  final VoidCallback onPickFile;
  const _ToolsTab({required this.onPickFile});

  @override
  Widget build(BuildContext context) {
    final tools = [
      (icon: Icons.palette_outlined,      label: 'Color Picker',  subtitle: 'HEX, RGB, HSL',            color: Colors.pink,        screen: const ColorPickerScreen()),
      (icon: Icons.text_snippet_outlined, label: 'Outils TXT',    subtitle: 'Mots, recherche, PDF',     color: Colors.blueGrey,    screen: const TxtToolsScreen()),
      (icon: Icons.table_chart_outlined,  label: 'Outils CSV',    subtitle: 'Stats, PDF, fusion',       color: Colors.green,       screen: const CsvToolsScreen()),
      (icon: Icons.difference_outlined,   label: 'Comparer',      subtitle: 'Diff de deux fichiers',    color: Colors.indigo,      screen: const DiffScreen()),
      (icon: Icons.fingerprint,           label: 'Hash fichier',  subtitle: 'MD5, SHA-1, SHA-256…',    color: Colors.teal,        screen: const HashScreen()),
      (icon: Icons.lock_outlined,         label: 'Encodage',      subtitle: 'Base64, URL, HTML',        color: Colors.orange,      screen: const EncodeScreen()),
      (icon: Icons.auto_fix_high,         label: 'Formater',      subtitle: 'JSON, CSS, JS',            color: Colors.purple,      screen: const FormatScreen()),
      (icon: Icons.edit_outlined,         label: 'Éditeur',       subtitle: 'Modifier et sauvegarder', color: Colors.cyan,        screen: CodeEditorScreen(path: '')),
      (icon: Icons.manage_search,         label: 'Chercher',      subtitle: 'Dans le contenu des fichiers', color: Colors.deepOrange, screen: const ContentSearchScreen()),
      (icon: Icons.table_view,             label: 'Éditeur CSV',   subtitle: 'Modifier cellules, lignes, colonnes', color: Colors.green, screen: CsvEditorScreen(path: '')),
      (icon: Icons.folder_zip_outlined,    label: 'Créer ZIP',     subtitle: 'Compresser des fichiers', color: Colors.orange, screen: const ZipCreatorScreen()),
      (icon: Icons.transform,               label: 'Convertir',     subtitle: 'Images/PDF, CSV/XLSX, TXT/PDF', color: Colors.deepPurple, screen: const ConvertScreen()),
      (icon: Icons.document_scanner_outlined, label: 'OCR',         subtitle: 'Image vers texte (local)', color: Colors.blue, screen: const OcrScreen()),
      (icon: Icons.compress,                label: 'Compresser',    subtitle: 'Réduire la taille des images', color: Colors.amber, screen: const CompressScreen()),
      (icon: Icons.shield_outlined,         label: 'Coffre fort',   subtitle: 'Fichiers chiffrés AES-256', color: Colors.red, screen: const VaultScreen()),
      (icon: Icons.camera_alt_outlined,     label: 'Scanner',       subtitle: 'Document → PDF (caméra)', color: Colors.teal, screen: const ScannerScreen()),
      (icon: Icons.cleaning_services_outlined, label: 'Effacer EXIF', subtitle: 'Supprimer GPS/date des images', color: Colors.brown, screen: const ExifScreen()),
    ];

    return GridView.builder(
      padding: const EdgeInsets.all(16),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2, crossAxisSpacing: 12,
        mainAxisSpacing: 12, childAspectRatio: 1.25,
      ),
      itemCount: tools.length,
      itemBuilder: (context, i) {
        final tool = tools[i];
        return Card(
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () => Navigator.push(
              context, MaterialPageRoute(builder: (_) => tool.screen)),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(tool.icon, size: 36, color: tool.color),
                  const SizedBox(height: 8),
                  Text(tool.label, style: const TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(tool.subtitle,
                      style: const TextStyle(fontSize: 11, color: Colors.grey),
                      textAlign: TextAlign.center),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

// ── Search ────────────────────────────────────────────────────────────────────

class _FileSearchDelegate extends SearchDelegate<void> {
  final List<RecentFile> files;
  final ValueChanged<String> onOpen;
  _FileSearchDelegate(this.files, this.onOpen);

  @override
  List<Widget> buildActions(BuildContext context) =>
      [IconButton(icon: const Icon(Icons.clear), onPressed: () => query = '')];

  @override
  Widget buildLeading(BuildContext context) =>
      IconButton(icon: const Icon(Icons.arrow_back), onPressed: () => close(context, null));

  @override
  Widget buildResults(BuildContext context) => _buildList(context);

  @override
  Widget buildSuggestions(BuildContext context) => _buildList(context);

  Widget _buildList(BuildContext context) {
    final results = query.isEmpty
        ? files
        : files.where((f) => f.name.toLowerCase().contains(query.toLowerCase())).toList();
    if (results.isEmpty) return const Center(child: Text('Aucun résultat'));
    return ListView.builder(
      itemCount: results.length,
      itemBuilder: (_, i) => ListTile(
        leading: const Icon(Icons.insert_drive_file_outlined),
        title: Text(results[i].name),
        onTap: () { close(context, null); onOpen(results[i].path); },
      ),
    );
  }
}
