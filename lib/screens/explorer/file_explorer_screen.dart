import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:share_plus/share_plus.dart';
import '../editors/code_editor_screen.dart';
import '../tools/exif_screen.dart';
import '../tools/bulk_rename_screen.dart';
import '../../widgets/file_viewer_router.dart';
import 'file_type_helpers.dart';
import 'services/batch_ops_service.dart';
import 'services/native_open_service.dart';
import 'services/selection_controller.dart';
import 'services/sort_service.dart';
import 'widgets/breadcrumb_bar.dart';
import 'widgets/empty_state.dart';
import 'widgets/explorer_dialogs.dart';
import 'widgets/file_info_dialog.dart';
import 'widgets/file_preview_sheet.dart';
import 'widgets/file_row.dart';
import 'widgets/permission_banner.dart';
import 'widgets/toolbar_actions.dart';

class FileExplorerScreen extends StatefulWidget {
  final String? initialPath;
  final Set<String>? extensionFilter;
  final String? title;
  final bool pickMode;

  const FileExplorerScreen({
    super.key,
    this.initialPath,
    this.extensionFilter,
    this.title,
    this.pickMode = false,
  });

  @override
  State<FileExplorerScreen> createState() => _FileExplorerScreenState();
}

class _FileExplorerScreenState extends State<FileExplorerScreen>
    with WidgetsBindingObserver {
  Directory? _current;
  final List<Directory> _history = [];
  List<FileSystemEntity> _entries = [];

  /// Vue filtrée mémoïsée — recalculée uniquement quand [_entries], [_search],
  /// [_showHidden] ou [widget.extensionFilter] changent. Avant : getter
  /// `_filtered` recompilait la liste à chaque rebuild (jusqu'à 10× / sec
  /// pendant scroll), introduisant un O(n) inutile sur dossiers larges.
  List<FileSystemEntity> _filtered = const [];
  bool _isLoading = true;
  bool _showHidden = false;
  String _search = '';
  bool _permissionDenied = false;

  final SortService _sortSvc = SortService();
  final SelectionController _selection = SelectionController();
  final BatchOpsService _batch = BatchOpsService();
  final NativeOpenService _opener = NativeOpenService();

  /// Cache des métadonnées (taille, mtime) renseigné par [_listDirNative].
  /// Évite des syscalls statSync()/lengthSync() dans le tri et l'itemBuilder.
  final Map<String, ({int size, int modified})> _statCache = {};

  static const _listDirChannel = MethodChannel('com.readfilestech/list_dir');
  static const _lifecycleChannel = MethodChannel('com.readfilestech/lifecycle');

  static const _pdfTechPackage = 'com.pdftech.pdf_tech';
  static const _kDrivePackage = 'com.infomaniak.drive';
  static const _protonDrivePackage = 'me.proton.android.drive';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _selection.addListener(() {
      if (mounted) setState(() {});
    });
    _initRoot();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _selection.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) return;
    if (!_permissionDenied || _current == null) return;
    // Flutter recommande de ne pas marquer ce callback async ; on dispatch
    // le travail asynchrone via unawaited(...) pour rester non-bloquant.
    unawaited(() async {
      final permOk = await _hasManageStorage();
      if (!permOk) return;
      try {
        await _lifecycleChannel.invokeMethod('recreateActivity');
      } catch (_) {
        if (mounted) _refresh();
      }
    }());
  }

  Future<bool> _hasManageStorage() async {
    if (!Platform.isAndroid) return true;
    return Permission.manageExternalStorage.isGranted;
  }

  bool _requiresManageStorage(String path) {
    if (!Platform.isAndroid) return false;
    return path.startsWith('/storage/emulated/0') || path.startsWith('/sdcard');
  }

  Future<void> _requestAllFilesAccess() async {
    try {
      await _lifecycleChannel.invokeMethod('openAllFilesAccess');
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Activez "Autoriser l\'accès à tous les fichiers" puis revenez à l\'app',
            style: TextStyle(fontSize: 13),
          ),
          duration: Duration(seconds: 6),
        ),
      );
    } catch (_) {
      await Permission.manageExternalStorage.request();
      if (mounted) _refresh();
    }
  }

  Future<List<FileSystemEntity>> _listDirNative(Directory dir) async {
    if (!Platform.isAndroid) return dir.list().toList();
    try {
      final raw = await _listDirChannel.invokeMethod<List<dynamic>>('listDir', {
        'path': dir.path,
      });
      if (raw == null) return dir.list().toList();
      final out = <FileSystemEntity>[];
      for (final item in raw) {
        final m = Map<String, dynamic>.from(item as Map);
        final p = m['path'] as String;
        final size = (m['size'] as num?)?.toInt() ?? 0;
        final modified = (m['modified'] as num?)?.toInt() ?? 0;
        _statCache[p] = (size: size, modified: modified);
        out.add((m['isDir'] as bool) ? Directory(p) : File(p));
      }
      return out;
    } catch (_) {
      return dir.list().toList();
    }
  }

  int _cachedSize(FileSystemEntity e) {
    final c = _statCache[e.path];
    if (c != null) return c.size;
    if (e is File) {
      try {
        return e.lengthSync();
      } catch (_) {
        return 0;
      }
    }
    return 0;
  }

  int _cachedModified(FileSystemEntity e) {
    final c = _statCache[e.path];
    if (c != null) return c.modified;
    try {
      return e.statSync().modified.millisecondsSinceEpoch;
    } catch (_) {
      return 0;
    }
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
    if (!mounted) return;
    _navigate(root);
  }

  Future<void> _navigate(Directory dir) async {
    setState(() => _isLoading = true);
    try {
      _statCache.clear();
      final entries = await _listDirNative(dir);
      final permOk = await _hasManageStorage();
      _permissionDenied =
          entries.isEmpty && _requiresManageStorage(dir.path) && !permOk;
      _sortSvc.sort(entries, sizeOf: _cachedSize, modifiedOf: _cachedModified);
      if (!mounted) return;
      setState(() {
        if (_current != null) _history.add(_current!);
        _current = dir;
        _entries = entries;
        _recomputeFiltered();
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Accès refusé : $e')));
    }
  }

  Future<void> _refresh() async {
    if (_current == null) return;
    setState(() => _isLoading = true);
    try {
      _statCache.clear();
      final entries = await _listDirNative(_current!);
      _sortSvc.sort(entries, sizeOf: _cachedSize, modifiedOf: _cachedModified);
      if (!mounted) return;
      setState(() {
        _entries = entries;
        _recomputeFiltered();
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
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

  // ---------- Single-file actions ----------

  Future<void> _openWithSystem(
    String path,
    String ext, {
    bool chooser = false,
  }) async {
    try {
      await _opener.openFile(path, ext, chooser: chooser);
    } on PlatformException catch (e) {
      if (!mounted) return;
      final msg = e.code == 'INSTALL_PERMISSION_REQUIRED'
          ? (e.message ?? 'Autorisation requise — page Réglages ouverte.')
          : 'Aucune application trouvée pour ouvrir ce fichier';
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(msg), duration: const Duration(seconds: 5)),
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Aucune application trouvée pour ouvrir ce fichier'),
          ),
        );
      }
    }
  }

  void _openFile(String path) {
    final imageSiblings = imageExts.contains(fileExt(path))
        ? _filtered
              .whereType<File>()
              .where((f) => imageExts.contains(fileExt(f.path)))
              .map((f) => f.path)
              .toList()
        : const <String>[];
    FileViewerRouter.open(context, path, imageSiblings: imageSiblings);
  }

  void _editFile(String path) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => CodeEditorScreen(path: path)),
    );
  }

  Future<void> _editInPdfTech(String path) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await _opener.openWithPackage(path, _pdfTechPackage, 'application/pdf');
    } on PlatformException catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            e.code == 'NOT_INSTALLED'
                ? 'PDF Tech n\'est pas installé sur cet appareil.'
                : 'Impossible d\'ouvrir avec PDF Tech.',
          ),
        ),
      );
    }
  }

  Future<void> _sendToCloud(String path, String pkg, String label) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await _opener.sendToPackage(path, pkg);
    } on PlatformException catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            e.code == 'NOT_INSTALLED'
                ? '$label n\'est pas installé sur cet appareil.'
                : 'Erreur : impossible d\'envoyer vers $label.',
          ),
        ),
      );
    }
  }

  void _stripExif(String path) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => ExifScreen(initialPath: path)),
    );
  }

  Future<void> _rename(FileSystemEntity e) async {
    final name = e.path.basename;
    final newName = await promptName(
      context,
      title: 'Renommer',
      confirmLabel: 'Renommer',
      initial: name,
    );
    if (newName == null || newName == name) return;
    if (!isValidFileName(newName)) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Nom invalide (caractères / \\ .. interdits)'),
        ),
      );
      return;
    }
    final newPath = '${e.parent.path}/$newName';
    if (await FileSystemEntity.type(newPath) != FileSystemEntityType.notFound) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('"$newName" existe déjà dans ce dossier')),
      );
      return;
    }
    try {
      await e.rename(newPath);
      _refresh();
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Impossible de renommer ce fichier')),
      );
    }
  }

  Future<void> _delete(FileSystemEntity e) async {
    final name = e.path.basename;
    final confirm = await confirmDelete(
      context,
      title: 'Supprimer',
      message:
          'Supprimer "$name" ?${e is Directory ? '\nLe dossier et tout son contenu seront supprimés.' : ''}',
    );
    if (!confirm) return;
    try {
      if (e is Directory) {
        await e.delete(recursive: true);
      } else {
        await e.delete();
      }
      _refresh();
    } catch (ex) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Erreur : $ex')));
    }
  }

  Future<void> _createFolder() async {
    final name = await promptName(
      context,
      title: 'Nouveau dossier',
      confirmLabel: 'Créer',
      hint: 'Nom du dossier',
    );
    if (name == null || _current == null) return;
    if (!isValidFileName(name)) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Nom invalide (caractères / \\ .. interdits)'),
        ),
      );
      return;
    }
    try {
      await Directory('${_current!.path}/$name').create();
      _refresh();
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Impossible de créer ce dossier')),
      );
    }
  }

  Future<void> _copyFile(String sourcePath) async {
    final messenger = ScaffoldMessenger.of(context);
    final destDir = await FilePicker.getDirectoryPath();
    if (destDir == null) return;
    try {
      final name = sourcePath.basename;
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
    final destDir = await FilePicker.getDirectoryPath();
    if (destDir == null) return;
    try {
      final name = sourcePath.basename;
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

  // ---------- Batch actions ----------

  Future<void> _deleteSelected() async {
    final count = _selection.count;
    final confirm = await confirmDelete(
      context,
      title: 'Supprimer',
      message:
          'Supprimer $count élément${count > 1 ? 's' : ''} ?\nLes dossiers et leur contenu seront supprimés.',
    );
    if (!confirm) return;
    final r = await _batch.deleteAll(_selection.snapshot());
    _selection.clear();
    _refresh();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          '${r.ok} supprimé${r.ok > 1 ? 's' : ''}'
          '${r.fail > 0 ? ' · ${r.fail} erreur(s)' : ''}',
        ),
      ),
    );
  }

  Future<void> _shareSelected() async {
    final files = _selection
        .snapshot()
        .where((p) => FileSystemEntity.typeSync(p) == FileSystemEntityType.file)
        .map((p) => XFile(p, mimeType: mimeOf(fileExt(p))))
        .toList();
    if (files.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Aucun fichier à partager (dossiers ignorés)'),
        ),
      );
      return;
    }
    await Share.shareXFiles(files);
  }

  Future<void> _bulkRenameSelected() async {
    final paths = _selection.snapshot();
    if (paths.isEmpty) return;
    final renamed = await Navigator.push<int>(
      context,
      MaterialPageRoute(builder: (_) => BulkRenameScreen(paths: paths)),
    );
    if (!mounted) return;
    if (renamed != null && renamed > 0) {
      _selection.clear();
      _refresh();
    }
  }

  Future<void> _copySelected({required bool move}) async {
    final messenger = ScaffoldMessenger.of(context);
    final destDir = await FilePicker.getDirectoryPath();
    if (destDir == null) return;
    final r = await _batch.copyAll(_selection.snapshot(), destDir, move: move);
    _selection.clear();
    if (move) _refresh();
    if (!mounted) return;
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          '${r.ok} ${move ? 'déplacé' : 'copié'}${r.ok > 1 ? 's' : ''}'
          '${r.fail > 0 ? ' · ${r.fail} erreur(s)' : ''}',
        ),
      ),
    );
  }

  // ---------- Filtering ----------

  /// Recalcule [_filtered] à partir de [_entries] + filtres courants.
  /// À appeler dans tout `setState` qui modifie [_entries], [_search] ou
  /// [_showHidden] (lui-même appelé dans le même `setState` callback).
  void _recomputeFiltered() {
    final extFilter = widget.extensionFilter;
    final query = _search.toLowerCase();
    _filtered = _entries.where((e) {
      final name = e.path.basename;
      if (!_showHidden && name.startsWith('.')) return false;
      if (extFilter != null &&
          e is File &&
          !extFilter.contains(fileExt(e.path))) {
        return false;
      }
      if (query.isNotEmpty && !name.toLowerCase().contains(query)) {
        return false;
      }
      return true;
    }).toList();
  }

  // ---------- Build ----------

  @override
  Widget build(BuildContext context) {
    final path = _current?.path ?? '';
    final selectionMode = _selection.hasSelection;

    return PopScope(
      canPop: !selectionMode,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && selectionMode) _selection.clear();
      },
      child: Scaffold(
        appBar: selectionMode ? _selectionAppBar() : _browseAppBar(path),
        body: Column(
          children: [
            BreadcrumbBar(path: path, onTap: _navigate),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 4, 12, 4),
              child: TextField(
                decoration: InputDecoration(
                  hintText: 'Rechercher…',
                  prefixIcon: const Icon(Icons.search, size: 18),
                  isDense: true,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                  contentPadding: const EdgeInsets.symmetric(vertical: 8),
                ),
                onChanged: (v) => setState(() {
                  _search = v;
                  _recomputeFiltered();
                }),
              ),
            ),
            if (_permissionDenied)
              PermissionBanner(onOpenSettings: _requestAllFilesAccess),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  '${_filtered.length} élément${_filtered.length > 1 ? 's' : ''}',
                  style: const TextStyle(color: Colors.grey, fontSize: 11),
                ),
              ),
            ),
            Expanded(child: _body()),
          ],
        ),
        floatingActionButton: selectionMode
            ? null
            : FloatingActionButton(
                onPressed: _createFolder,
                tooltip: 'Nouveau dossier',
                child: const Icon(Icons.create_new_folder_outlined),
              ),
      ),
    );
  }

  PreferredSizeWidget _selectionAppBar() {
    return AppBar(
      leading: IconButton(
        icon: const Icon(Icons.close),
        tooltip: 'Annuler la sélection',
        onPressed: _selection.clear,
      ),
      title: Text(
        '${_selection.count} sélectionné${_selection.count > 1 ? 's' : ''}',
        style: const TextStyle(fontSize: 16),
      ),
      actions: [
        SelectionToolbarActions(
          onSelectAll: () => _selection.selectAll(_filtered.map((e) => e.path)),
          onShare: _shareSelected,
          onCopy: () => _copySelected(move: false),
          onMove: () => _copySelected(move: true),
          onBulkRename: _bulkRenameSelected,
          onDelete: _deleteSelected,
        ),
      ],
    );
  }

  PreferredSizeWidget _browseAppBar(String path) {
    final parts = path.split('/').where((p) => p.isNotEmpty).toList();
    return AppBar(
      leading: _canGoBack()
          ? IconButton(icon: const Icon(Icons.arrow_back), onPressed: _goBack)
          : null,
      title: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            widget.title ?? 'Explorateur',
            style: const TextStyle(fontSize: 16),
          ),
          Text(
            parts.isNotEmpty ? parts.last : '/',
            style: const TextStyle(fontSize: 11, color: Colors.grey),
            overflow: TextOverflow.ellipsis,
          ),
        ],
      ),
      actions: [
        BrowseToolbarActions(
          showHidden: _showHidden,
          sortKey: SortService.toKey(_sortSvc.mode),
          onRefresh: _refresh,
          onToggleHidden: () => setState(() {
            _showHidden = !_showHidden;
            _recomputeFiltered();
          }),
          onSortSelected: (v) => setState(() {
            _sortSvc.mode = SortService.fromString(v);
            if (_current != null) _navigate(_current!);
          }),
        ),
      ],
    );
  }

  Widget _body() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    return RefreshIndicator(
      onRefresh: _refresh,
      child: _filtered.isEmpty
          ? ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              children: [
                const SizedBox(height: 80),
                ExplorerEmptyState(
                  permissionDenied: _permissionDenied,
                  hasExtensionFilter: widget.extensionFilter != null,
                  extensionFilter: widget.extensionFilter,
                  totalEntries: _entries.length,
                  onRequestAllFiles: _requestAllFilesAccess,
                ),
              ],
            )
          : ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              itemCount: _filtered.length,
              itemBuilder: (_, i) => _buildRow(_filtered[i]),
            ),
    );
  }

  Widget _buildRow(FileSystemEntity e) {
    final isDir = e is Directory;
    final cached = _statCache[e.path];
    final int? size = isDir ? null : (cached?.size ?? _cachedSize(e));
    final DateTime? modified = cached != null
        ? DateTime.fromMillisecondsSinceEpoch(cached.modified)
        : null;
    final selectionMode = _selection.hasSelection;
    final isSelected = _selection.isSelected(e.path);

    return FileRow(
      entity: e,
      isSelected: isSelected,
      selectionMode: selectionMode,
      size: size,
      modified: modified,
      actions: FileRowActions(
        onOpen: _openFile,
        onOpenSystem: (p, ext) => _openWithSystem(p, ext),
        onOpenChooser: (p, ext) => _openWithSystem(p, ext, chooser: true),
        onPreview: (p) =>
            showFilePreviewSheet(context, p, onOpen: () => _openFile(p)),
        onEdit: _editFile,
        onEditPdfTech: _editInPdfTech,
        onStripExif: _stripExif,
        onShare: (p, ext) =>
            Share.shareXFiles([XFile(p, mimeType: mimeOf(ext))]),
        onSendKDrive: (p) => _sendToCloud(p, _kDrivePackage, 'kDrive'),
        onSendProton: (p) =>
            _sendToCloud(p, _protonDrivePackage, 'Proton Drive'),
        onRename: _rename,
        onCopy: _copyFile,
        onMove: _moveFile,
        onInfo: (e2) => showFileInfoDialog(context, e2),
        onDelete: _delete,
      ),
      onTap: () {
        if (selectionMode) {
          _selection.toggle(e.path);
          return;
        }
        if (isDir) {
          _navigate(Directory(e.path));
          return;
        }
        if (widget.pickMode) {
          Navigator.pop(context, e.path);
          return;
        }
        final ext = fileExt(e.path);
        if (FileViewerRouter.canViewInternally(e.path)) {
          _openFile(e.path);
        } else {
          _openWithSystem(e.path, ext);
        }
      },
      onLongPress: () {
        HapticFeedback.mediumImpact();
        _selection.toggle(e.path);
      },
    );
  }
}
