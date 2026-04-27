import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import '../viewers/txt_viewer_screen.dart';
import '../viewers/md_viewer_screen.dart';
import '../viewers/json_viewer_screen.dart';
import '../viewers/html_viewer_screen.dart';
import '../viewers/csv_viewer_screen.dart';
import '../viewers/xlsx_viewer_screen.dart';
import '../viewers/docx_viewer_screen.dart';
import '../viewers/pdf_viewer_screen.dart';
import '../viewers/zip_viewer_screen.dart';
import '../editors/code_editor_screen.dart';
import '../viewers/image_viewer_screen.dart';

class FileExplorerScreen extends StatefulWidget {
  final String? initialPath;
  const FileExplorerScreen({super.key, this.initialPath});

  @override
  State<FileExplorerScreen> createState() => _FileExplorerScreenState();
}

class _FileExplorerScreenState extends State<FileExplorerScreen> {
  Directory? _current;
  final List<Directory> _history = [];
  List<FileSystemEntity> _entries = [];
  bool _isLoading = true;
  bool _showHidden = false;
  String _search = '';
  String _sort = 'name';

  final Set<String> _selected = <String>{};
  bool get _selectionMode => _selected.isNotEmpty;

  void _toggleSelect(FileSystemEntity e) {
    setState(() {
      if (_selected.contains(e.path)) {
        _selected.remove(e.path);
      } else {
        _selected.add(e.path);
      }
    });
  }

  void _clearSelection() => setState(_selected.clear);

  void _selectAll() {
    setState(() {
      _selected
        ..clear()
        ..addAll(_filtered.map((e) => e.path));
    });
  }

  Future<void> _deleteSelected() async {
    final count = _selected.length;
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Supprimer'),
        content: Text('Supprimer $count élément${count > 1 ? 's' : ''} ?\nLes dossiers et leur contenu seront supprimés.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Supprimer'),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    int ok = 0, fail = 0;
    for (final p in _selected.toList()) {
      try {
        final type = FileSystemEntity.typeSync(p);
        if (type == FileSystemEntityType.directory) {
          await Directory(p).delete(recursive: true);
        } else {
          await File(p).delete();
        }
        ok++;
      } catch (_) {
        fail++;
      }
    }
    _clearSelection();
    _refresh();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('$ok supprimé${ok > 1 ? 's' : ''}${fail > 0 ? ' · $fail erreur(s)' : ''}')),
    );
  }

  Future<void> _shareSelected() async {
    final files = _selected
        .where((p) => FileSystemEntity.typeSync(p) == FileSystemEntityType.file)
        .map((p) => XFile(p, mimeType: _mime(_ext(p))))
        .toList();
    if (files.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Aucun fichier à partager (dossiers ignorés)')),
      );
      return;
    }
    await Share.shareXFiles(files);
  }

  Future<void> _copySelected({required bool move}) async {
    final messenger = ScaffoldMessenger.of(context);
    final destDir = await FilePicker.platform.getDirectoryPath();
    if (destDir == null) return;
    int ok = 0, fail = 0;
    for (final p in _selected.toList()) {
      try {
        final type = FileSystemEntity.typeSync(p);
        if (type != FileSystemEntityType.file) { fail++; continue; }
        final name = p.split('/').last;
        await File(p).copy('$destDir/$name');
        if (move) await File(p).delete();
        ok++;
      } catch (_) { fail++; }
    }
    _clearSelection();
    if (move) _refresh();
    if (!mounted) return;
    messenger.showSnackBar(SnackBar(content: Text(
        '$ok ${move ? 'déplacé' : 'copié'}${ok > 1 ? 's' : ''}${fail > 0 ? ' · $fail erreur(s)' : ''}')));
  }

  static const _editableExts   = {'txt','md','csv','xml','json','html','css','js','php','dart'};
  static const _viewableExts   = {'docx','doc','odt','xlsx','xls','ods','odp','pdf','zip'};
  static const _imageExts      = {'jpg','jpeg','png','gif','webp'};

  @override
  void initState() {
    super.initState();
    _initRoot();
  }

  Future<void> _initRoot() async {
    if (widget.initialPath != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _navigate(Directory(widget.initialPath!));
      });
      return;
    }
    Directory? root;
    if (Platform.isAndroid) {
      root = Directory('/storage/emulated/0');
      if (!await root.exists()) root = await getExternalStorageDirectory();
    }
    root ??= await getApplicationDocumentsDirectory();
    _navigate(root);
  }

  Future<void> _navigate(Directory dir) async {
    setState(() => _isLoading = true);
    try {
      final entries = await dir.list().toList();
      entries.sort((a, b) {
        final aDir = a is Directory;
        final bDir = b is Directory;
        if (aDir != bDir) return aDir ? -1 : 1;
        switch (_sort) {
          case 'size':
            final aSize = a is File ? (a).lengthSync() : 0;
            final bSize = b is File ? (b).lengthSync() : 0;
            return bSize.compareTo(aSize);
          case 'date':
            return b.statSync().modified.compareTo(a.statSync().modified);
          default:
            return a.path.split('/').last.toLowerCase()
                .compareTo(b.path.split('/').last.toLowerCase());
        }
      });
      setState(() {
        if (_current != null) { _history.add(_current!); }
        _current = dir;
        _entries = entries;
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Accès refusé : $e')),
        );
      }
    }
  }

  void _goBack() {
    if (_history.isNotEmpty) {
      final prev = _history.removeLast();
      setState(() => _current = null);
      _navigate(prev);
    }
  }

  bool _canGoBack() => _history.isNotEmpty;

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  static final _openFileChannel = MethodChannel('com.readfilestech/open_file');

  Future<void> _openWithSystem(String path, String ext, {bool chooser = false}) async {
    final mime = _mime(ext) ?? '*/*';
    try {
      await _openFileChannel.invokeMethod('openFile', {
        'path': path,
        'mime': mime,
        'chooser': chooser,
      });
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Aucune application trouvée pour ouvrir ce fichier')),
        );
      }
    }
  }

  String _ext(String path) => path.contains('.') ? path.split('.').last.toLowerCase() : '';

  String? _mime(String ext) {
    switch (ext) {
      case 'jpg': case 'jpeg': return 'image/jpeg';
      case 'png':  return 'image/png';
      case 'gif':  return 'image/gif';
      case 'webp': return 'image/webp';
      case 'mp4':  return 'video/mp4';
      case 'mp3':  return 'audio/mpeg';
      case 'pdf':  return 'application/pdf';
      case 'txt':  return 'text/plain';
      case 'html': case 'htm': return 'text/html';
      case 'csv':  return 'text/csv';
      case 'zip':  return 'application/zip';
      case 'apk':  return 'application/vnd.android.package-archive';
      case 'json': return 'application/json';
      case 'xml':  return 'application/xml';
      case 'doc':  return 'application/msword';
      case 'docx': return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
      case 'xls':  return 'application/vnd.ms-excel';
      case 'xlsx': return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
      case 'ppt':  return 'application/vnd.ms-powerpoint';
      case 'pptx': return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
      default:     return null;
    }
  }

  IconData _icon(FileSystemEntity e) {
    if (e is Directory) return Icons.folder_outlined;
    final ext = _ext(e.path);
    switch (ext) {
      case 'pdf':             return Icons.picture_as_pdf_outlined;
      case 'jpg': case 'jpeg':
      case 'png': case 'gif': case 'webp': return Icons.image_outlined;
      case 'mp4': case 'avi': case 'mov':  return Icons.videocam_outlined;
      case 'mp3': case 'wav': case 'flac': return Icons.audiotrack_outlined;
      case 'zip': case 'rar': case '7z':   return Icons.folder_zip_outlined;
      case 'docx': case 'doc': case 'odt': return Icons.article_outlined;
      case 'xlsx': case 'xls': case 'csv': return Icons.table_chart_outlined;
      case 'html': case 'htm':             return Icons.html_outlined;
      case 'js': case 'ts':               return Icons.javascript_outlined;
      case 'css':                          return Icons.css_outlined;
      case 'json':                         return Icons.data_object;
      case 'md':                           return Icons.text_snippet_outlined;
      default:                             return Icons.insert_drive_file_outlined;
    }
  }

  Color _color(FileSystemEntity e) {
    if (e is Directory) return Colors.amber;
    switch (_ext(e.path)) {
      case 'pdf':             return Colors.red;
      case 'jpg': case 'jpeg':
      case 'png': case 'gif': return Colors.purple;
      case 'js': case 'ts':  return Colors.yellow.shade700;
      case 'html': case 'htm': return Colors.orange;
      case 'css':             return Colors.blue;
      case 'json':            return Colors.deepPurple;
      case 'docx': case 'doc': return Colors.blue.shade700;
      case 'xlsx': case 'csv': return Colors.green;
      default:                return Colors.grey;
    }
  }

  Widget? _screenFor(String path) {
    final ext = _ext(path);
    if (_editableExts.contains(ext)) {
      switch (ext) {
        case 'md':   return MdViewerScreen(path: path);
        case 'json': return JsonViewerScreen(path: path);
        case 'html': case 'htm': return HtmlViewerScreen(path: path);
        case 'csv':  return CsvViewerScreen(path: path);
        default:     return TxtViewerScreen(path: path,
            highlightLanguage: ['css','js','php','xml'].contains(ext) ? ext : null);
      }
    }
    switch (ext) {
      case 'docx': case 'doc': case 'odt': case 'odp': return DocxViewerScreen(path: path);
      case 'xlsx': case 'xls': case 'ods': return XlsxViewerScreen(path: path);
      case 'pdf':  return PdfViewerScreen(path: path);
      case 'zip':  return ZipViewerScreen(path: path);
      default:     return null;
    }
  }

  static const _previewExts = {
    'txt','md','json','xml','html','htm','css','js','php','dart',
    'csv','yaml','yml','ini','conf','log',
  };

  Future<void> _showPreview(String path) async {
    final ext = _ext(path);
    final name = path.split('/').last;
    String preview = '';
    String type = 'text';

    try {
      if (_previewExts.contains(ext)) {
        final lines = await File(path).readAsLines();
        final shown = lines.take(40).join('\n');
        preview = shown;
        if (ext == 'json') { type = 'json'; }
        else if (ext == 'csv') { type = 'csv'; }
      } else {
        preview = 'Aperçu non disponible pour ce format.';
        type = 'none';
      }
    } catch (e) {
      preview = 'Impossible de lire le fichier.';
      type = 'none';
    }

    if (!mounted) return;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.55,
        minChildSize: 0.3,
        maxChildSize: 0.92,
        builder: (ctx, scrollCtrl) => Column(
          children: [
            // Handle
            Container(
              margin: const EdgeInsets.symmetric(vertical: 8),
              width: 40, height: 4,
              decoration: BoxDecoration(
                color: Colors.grey.withValues(alpha: 0.4),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            // Header
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 8, 8),
              child: Row(children: [
                Icon(_icon(File(path)), color: _color(File(path)), size: 20),
                const SizedBox(width: 8),
                Expanded(child: Text(name,
                    style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
                    overflow: TextOverflow.ellipsis)),
                TextButton(
                  onPressed: () { Navigator.pop(ctx); _openFile(path); },
                  child: const Text('Ouvrir'),
                ),
              ]),
            ),
            const Divider(height: 1),
            // Content
            Expanded(
              child: SingleChildScrollView(
                controller: scrollCtrl,
                padding: const EdgeInsets.all(14),
                child: type == 'csv'
                    ? _previewCsv(preview)
                    : SelectableText(
                        preview,
                        style: TextStyle(
                          fontFamily: type == 'none' ? null : 'monospace',
                          fontSize: 12,
                          color: type == 'none' ? Colors.grey : null,
                          height: 1.5,
                        ),
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _previewCsv(String raw) {
    final rows = raw.split('\n').take(10).map((l) => l.split(',')).toList();
    if (rows.isEmpty) return const Text('Fichier vide');
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Table(
        border: TableBorder.all(color: Colors.grey.withValues(alpha: 0.3)),
        defaultColumnWidth: const IntrinsicColumnWidth(),
        children: rows.asMap().entries.map((entry) {
          final isHeader = entry.key == 0;
          return TableRow(
            decoration: isHeader
                ? BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest)
                : null,
            children: entry.value.map((cell) => Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              child: Text(cell.trim(),
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: isHeader ? FontWeight.w700 : FontWeight.normal,
                  )),
            )).toList(),
          );
        }).toList(),
      ),
    );
  }

  void _openFile(String path) {
    final ext = _ext(path);
    if (_imageExts.contains(ext)) {
      final siblings = _filtered
          .whereType<File>()
          .where((f) => _imageExts.contains(_ext(f.path)))
          .map((f) => f.path)
          .toList();
      Navigator.push(context, MaterialPageRoute(
          builder: (_) => ImageViewerScreen(path: path, siblings: siblings)));
      return;
    }
    final screen = _screenFor(path);
    if (screen != null) {
      Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Format .${_ext(path)} non supporté')),
      );
    }
  }

  void _editFile(String path) {
    Navigator.push(context, MaterialPageRoute(
        builder: (_) => CodeEditorScreen(path: path)));
  }

  Future<void> _refresh() async {
    if (_current == null) return;
    setState(() => _isLoading = true);
    try {
      final entries = await _current!.list().toList();
      entries.sort((a, b) {
        final aDir = a is Directory;
        final bDir = b is Directory;
        if (aDir != bDir) return aDir ? -1 : 1;
        switch (_sort) {
          case 'size':
            final aSize = a is File ? a.lengthSync() : 0;
            final bSize = b is File ? b.lengthSync() : 0;
            return bSize.compareTo(aSize);
          case 'date':
            return b.statSync().modified.compareTo(a.statSync().modified);
          default:
            return a.path.split('/').last.toLowerCase()
                .compareTo(b.path.split('/').last.toLowerCase());
        }
      });
      setState(() { _entries = entries; _isLoading = false; });
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      }
    }
  }

  Future<void> _rename(FileSystemEntity e) async {
    final name = e.path.split('/').last;
    final ctrl = TextEditingController(text: name);
    final newName = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Renommer'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(border: OutlineInputBorder()),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Annuler')),
          FilledButton(
              onPressed: () => Navigator.pop(context, ctrl.text.trim()),
              child: const Text('Renommer')),
        ],
      ),
    );
    if (newName == null || newName.isEmpty || newName == name) return;
    try {
      await e.rename('${e.parent.path}/$newName');
      _refresh();
    } catch (ex) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erreur : $ex')));
    }
  }

  Future<void> _delete(FileSystemEntity e) async {
    final name = e.path.split('/').last;
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Supprimer'),
        content: Text('Supprimer "$name" ?${e is Directory ? '\nLe dossier et tout son contenu seront supprimés.' : ''}'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Supprimer'),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    try {
      if (e is Directory) {
        await e.delete(recursive: true);
      } else {
        await e.delete();
      }
      _refresh();
    } catch (ex) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erreur : $ex')));
    }
  }

  Future<void> _createFolder() async {
    final ctrl = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Nouveau dossier'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(
              hintText: 'Nom du dossier', border: OutlineInputBorder()),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Annuler')),
          FilledButton(
              onPressed: () => Navigator.pop(context, ctrl.text.trim()),
              child: const Text('Créer')),
        ],
      ),
    );
    if (name == null || name.isEmpty || _current == null) return;
    try {
      await Directory('${_current!.path}/$name').create();
      _refresh();
    } catch (ex) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erreur : $ex')));
    }
  }

  Future<void> _copyFile(String sourcePath) async {
    final messenger = ScaffoldMessenger.of(context);
    final destDir = await FilePicker.platform.getDirectoryPath();
    if (destDir == null) return;
    try {
      final name = sourcePath.split('/').last;
      await File(sourcePath).copy('$destDir/$name');
      if (!mounted) return;
      messenger.showSnackBar(const SnackBar(content: Text('Fichier copié')));
    } catch (ex) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $ex')));
    }
  }

  Future<void> _moveFile(String sourcePath) async {
    final messenger = ScaffoldMessenger.of(context);
    final destDir = await FilePicker.platform.getDirectoryPath();
    if (destDir == null) return;
    try {
      final name = sourcePath.split('/').last;
      await File(sourcePath).copy('$destDir/$name');
      await File(sourcePath).delete();
      if (!mounted) return;
      _refresh();
      messenger.showSnackBar(const SnackBar(content: Text('Fichier déplacé')));
    } catch (ex) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $ex')));
    }
  }

  List<FileSystemEntity> get _filtered {
    return _entries.where((e) {
      final name = e.path.split('/').last;
      if (!_showHidden && name.startsWith('.')) return false;
      if (_search.isNotEmpty && !name.toLowerCase().contains(_search.toLowerCase())) return false;
      return true;
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final path = _current?.path ?? '';
    final parts = path.split('/').where((p) => p.isNotEmpty).toList();

    return PopScope(
      canPop: !_selectionMode,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && _selectionMode) _clearSelection();
      },
      child: Scaffold(
      appBar: _selectionMode
          ? AppBar(
              leading: IconButton(
                  icon: const Icon(Icons.close),
                  tooltip: 'Annuler la sélection',
                  onPressed: _clearSelection),
              title: Text('${_selected.length} sélectionné${_selected.length > 1 ? 's' : ''}',
                  style: const TextStyle(fontSize: 16)),
              actions: [
                IconButton(
                  icon: const Icon(Icons.select_all),
                  tooltip: 'Tout sélectionner',
                  onPressed: _selectAll,
                ),
                IconButton(
                  icon: const Icon(Icons.share),
                  tooltip: 'Partager',
                  onPressed: _shareSelected,
                ),
                IconButton(
                  icon: const Icon(Icons.copy_outlined),
                  tooltip: 'Copier vers…',
                  onPressed: () => _copySelected(move: false),
                ),
                IconButton(
                  icon: const Icon(Icons.drive_file_move_outlined),
                  tooltip: 'Déplacer vers…',
                  onPressed: () => _copySelected(move: true),
                ),
                IconButton(
                  icon: const Icon(Icons.delete_outline, color: Colors.red),
                  tooltip: 'Supprimer',
                  onPressed: _deleteSelected,
                ),
              ],
            )
          : AppBar(
              leading: _canGoBack()
                  ? IconButton(icon: const Icon(Icons.arrow_back), onPressed: _goBack)
                  : null,
              title: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Explorateur', style: TextStyle(fontSize: 16)),
                  Text(parts.isNotEmpty ? parts.last : '/',
                      style: const TextStyle(fontSize: 11, color: Colors.grey),
                      overflow: TextOverflow.ellipsis),
                ],
              ),
              actions: [
                IconButton(
                  icon: const Icon(Icons.refresh),
                  tooltip: 'Actualiser',
                  onPressed: _refresh,
                ),
                IconButton(
                  icon: Icon(_showHidden ? Icons.visibility_off : Icons.visibility),
                  tooltip: _showHidden ? 'Masquer fichiers cachés' : 'Afficher fichiers cachés',
                  onPressed: () => setState(() => _showHidden = !_showHidden),
                ),
                PopupMenuButton<String>(
                  icon: const Icon(Icons.sort),
                  onSelected: (v) => setState(() { _sort = v; _navigate(_current!); }),
                  itemBuilder: (_) => const [
                    PopupMenuItem(value: 'name', child: ListTile(leading: Icon(Icons.sort_by_alpha), title: Text('Nom'))),
                    PopupMenuItem(value: 'date', child: ListTile(leading: Icon(Icons.access_time), title: Text('Date'))),
                    PopupMenuItem(value: 'size', child: ListTile(leading: Icon(Icons.data_usage), title: Text('Taille'))),
                  ],
                ),
              ],
            ),
      body: Column(
        children: [
          // Breadcrumb
          if (parts.length > 1)
            SizedBox(
              height: 36,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                itemCount: parts.length,
                separatorBuilder: (_, i) => const Icon(Icons.chevron_right, size: 16, color: Colors.grey),
                itemBuilder: (_, i) {
                  final targetPath = '/${parts.sublist(0, i + 1).join('/')}';
                  return GestureDetector(
                    onTap: () => _navigate(Directory(targetPath)),
                    child: Center(
                      child: Text(parts[i],
                          style: TextStyle(
                            fontSize: 12,
                            color: i == parts.length - 1
                                ? Theme.of(context).colorScheme.primary
                                : Colors.grey,
                          )),
                    ),
                  );
                },
              ),
            ),

          // Search
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 4, 12, 4),
            child: TextField(
              decoration: InputDecoration(
                hintText: 'Rechercher…',
                prefixIcon: const Icon(Icons.search, size: 18),
                isDense: true,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                contentPadding: const EdgeInsets.symmetric(vertical: 8),
              ),
              onChanged: (v) => setState(() => _search = v),
            ),
          ),

          // Stats
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text('${_filtered.length} élément${_filtered.length > 1 ? 's' : ''}',
                  style: const TextStyle(color: Colors.grey, fontSize: 11)),
            ),
          ),

          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : RefreshIndicator(
                    onRefresh: _refresh,
                    child: _filtered.isEmpty
                    ? ListView(
                        physics: const AlwaysScrollableScrollPhysics(),
                        children: const [
                          SizedBox(height: 200),
                          Center(child: Text('Dossier vide',
                              style: TextStyle(color: Colors.grey))),
                        ],
                      )
                    : ListView.builder(
                        physics: const AlwaysScrollableScrollPhysics(),
                        itemCount: _filtered.length,
                        itemBuilder: (_, i) {
                          final e = _filtered[i];
                          final name = e.path.split('/').last;
                          final isDir = e is Directory;
                          final ext = isDir ? '' : _ext(e.path);
                          final canEdit = _editableExts.contains(ext);
                          final canView = canEdit || _viewableExts.contains(ext) || _imageExts.contains(ext);

                          int? size;
                          DateTime? modified;
                          try {
                            final stat = e.statSync();
                            if (!isDir) size = stat.size;
                            modified = stat.modified;
                          } catch (_) {}

                          final isSelected = _selected.contains(e.path);

                          return ListTile(
                            dense: true,
                            selected: isSelected,
                            selectedTileColor: Theme.of(context)
                                .colorScheme
                                .primary
                                .withValues(alpha: 0.12),
                            leading: ClipRRect(
                              borderRadius: BorderRadius.circular(8),
                              child: isSelected
                                  ? Container(
                                      width: 36, height: 36,
                                      color: Theme.of(context).colorScheme.primary,
                                      child: const Icon(Icons.check, color: Colors.white, size: 20),
                                    )
                                  : (_imageExts.contains(ext)
                                      ? Image.file(
                                          File(e.path),
                                          width: 36, height: 36,
                                          fit: BoxFit.cover,
                                          cacheWidth: 72,
                                          errorBuilder: (_, e2, st) => Container(
                                            width: 36, height: 36,
                                            color: _color(e).withValues(alpha: 0.12),
                                            child: Icon(_icon(e), color: _color(e), size: 20),
                                          ),
                                        )
                                      : Container(
                                          width: 36, height: 36,
                                          color: _color(e).withValues(alpha: 0.12),
                                          child: Icon(_icon(e), color: _color(e), size: 20),
                                        )),
                            ),
                            title: Text(name,
                                style: const TextStyle(fontSize: 13),
                                overflow: TextOverflow.ellipsis),
                            subtitle: Row(children: [
                              if (size != null) Text(_formatSize(size),
                                  style: const TextStyle(fontSize: 11)),
                              if (size != null && modified != null)
                                const Text(' · ', style: TextStyle(fontSize: 11, color: Colors.grey)),
                              if (modified != null)
                                Text(
                                  '${modified.day.toString().padLeft(2, '0')}/'
                                  '${modified.month.toString().padLeft(2, '0')}/'
                                  '${modified.year}',
                                  style: const TextStyle(fontSize: 11, color: Colors.grey),
                                ),
                            ]),
                            trailing: _selectionMode
                                ? null
                                : isDir
                                ? PopupMenuButton<String>(
                                    onSelected: (v) {
                                      if (v == 'rename') _rename(e);
                                      if (v == 'delete') _delete(e);
                                    },
                                    itemBuilder: (_) => const [
                                      PopupMenuItem(value: 'rename', child: ListTile(
                                          leading: Icon(Icons.drive_file_rename_outline), title: Text('Renommer'))),
                                      PopupMenuItem(value: 'delete', child: ListTile(
                                          leading: Icon(Icons.delete_outline, color: Colors.red), title: Text('Supprimer'))),
                                    ],
                                  )
                                : PopupMenuButton<String>(
                                    onSelected: (v) {
                                      if (v == 'open')         _openFile(e.path);
                                      if (v == 'open_system')  _openWithSystem(e.path, ext);
                                      if (v == 'open_chooser') _openWithSystem(e.path, ext, chooser: true);
                                      if (v == 'preview')      _showPreview(e.path);
                                      if (v == 'edit')         _editFile(e.path);
                                      if (v == 'share')        Share.shareXFiles([XFile(e.path, mimeType: _mime(ext))]);
                                      if (v == 'rename')       _rename(e);
                                      if (v == 'copy')         _copyFile(e.path);
                                      if (v == 'move')         _moveFile(e.path);
                                      if (v == 'delete')       _delete(e);
                                    },
                                    itemBuilder: (_) => [
                                      if (canView)
                                        const PopupMenuItem(value: 'open', child: ListTile(
                                            leading: Icon(Icons.open_in_new), title: Text('Ouvrir'))),
                                      if (!canView)
                                        const PopupMenuItem(value: 'open_system', child: ListTile(
                                            leading: Icon(Icons.open_in_new), title: Text('Ouvrir'))),
                                      const PopupMenuItem(value: 'open_chooser', child: ListTile(
                                            leading: Icon(Icons.apps_outlined), title: Text('Ouvrir avec…'))),
                                      const PopupMenuItem(value: 'preview', child: ListTile(
                                          leading: Icon(Icons.visibility_outlined), title: Text('Aperçu'))),
                                      if (canEdit)
                                        const PopupMenuItem(value: 'edit', child: ListTile(
                                            leading: Icon(Icons.edit_outlined), title: Text('Éditer'))),
                                      const PopupMenuItem(value: 'share', child: ListTile(
                                          leading: Icon(Icons.share), title: Text('Partager'))),
                                      const PopupMenuDivider(),
                                      const PopupMenuItem(value: 'rename', child: ListTile(
                                          leading: Icon(Icons.drive_file_rename_outline), title: Text('Renommer'))),
                                      const PopupMenuItem(value: 'copy', child: ListTile(
                                          leading: Icon(Icons.copy_outlined), title: Text('Copier vers…'))),
                                      const PopupMenuItem(value: 'move', child: ListTile(
                                          leading: Icon(Icons.drive_file_move_outlined), title: Text('Déplacer vers…'))),
                                      const PopupMenuItem(value: 'delete', child: ListTile(
                                          leading: Icon(Icons.delete_outline, color: Colors.red), title: Text('Supprimer'))),
                                    ],
                                  ),
                            onTap: () {
                              if (_selectionMode) {
                                _toggleSelect(e);
                                return;
                              }
                              if (isDir) {
                                _navigate(Directory(e.path));
                              } else if (canView) {
                                _openFile(e.path);
                              } else {
                                _openWithSystem(e.path, ext);
                              }
                            },
                            onLongPress: () {
                              HapticFeedback.mediumImpact();
                              _toggleSelect(e);
                            },
                          );
                        },
                      ),
                  ),
          ),
        ],
      ),
      floatingActionButton: _selectionMode
          ? null
          : FloatingActionButton(
              onPressed: _createFolder,
              tooltip: 'Nouveau dossier',
              child: const Icon(Icons.create_new_folder_outlined),
            ),
      ),
    );
  }
}
