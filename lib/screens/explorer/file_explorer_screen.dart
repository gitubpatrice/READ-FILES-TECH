import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import '../editors/code_editor_screen.dart';
import '../tools/exif_screen.dart';
import '../tools/bulk_rename_screen.dart';
import '../tools/trash_screen.dart';
import '../../services/storage_access.dart';
import '../../services/trash_service.dart';
import '../../utils/snack_utils.dart';
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

  /// Debounce de la recherche (P1 perf — évite 5-10 rebuilds/s pendant
  /// frappe rapide sur dossier de 2k entrées).
  Timer? _searchDebounce;
  static const _searchDebounceDelay = Duration(milliseconds: 150);

  final SortService _sortSvc = SortService();
  final SelectionController _selection = SelectionController();
  final BatchOpsService _batch = BatchOpsService();
  final NativeOpenService _opener = NativeOpenService();
  final TrashService _trash = TrashService();

  /// Cache des métadonnées (taille, mtime) renseigné par [_listDirNative].
  /// Évite des syscalls statSync()/lengthSync() dans le tri et l'itemBuilder.
  final Map<String, ({int size, int modified})> _statCache = {};

  static const _listDirChannel = MethodChannel('com.readfilestech/list_dir');
  static const _lifecycleChannel = MethodChannel('com.readfilestech/lifecycle');

  static const _pdfTechPackage = 'com.pdftech.pdf_tech';

  /// Texte du bandeau de permission, résolu une fois selon la version
  /// d'Android (cf. `StorageAccess.bannerMessage`).
  String _permBannerMessage =
      'Accès aux fichiers limité — autorisez le stockage.';

  bool _kDriveInstalled = false;
  bool _protonInstalled = false;

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
    _probeCloudApps();
    _resolveBannerMessage();
  }

  Future<void> _resolveBannerMessage() async {
    final msg = await StorageAccess.bannerMessage();
    if (!mounted || msg == _permBannerMessage) return;
    setState(() => _permBannerMessage = msg);
  }

  /// Sonde une seule fois la présence des applications cloud. Le menu
  /// contextuel de chaque fichier proposait « Envoyer vers kDrive » et
  /// « Envoyer vers Proton Drive » sans jamais vérifier qu'elles existaient :
  /// sur un appareil sans elles, deux entrées permanentes qui échouaient
  /// toujours. Sondé ici plutôt qu'à chaque ouverture de menu — le résultat
  /// ne change pas pendant la vie de l'écran.
  Future<void> _probeCloudApps() async {
    final kdrive = await _opener.isPackageInstalled(_kDrivePackage);
    final proton = await _opener.isPackageInstalled(_protonDrivePackage);
    if (!mounted) return;
    setState(() {
      _kDriveInstalled = kdrive;
      _protonInstalled = proton;
    });
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
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

  /// Accès effectif au stockage partagé, quelle que soit la version
  /// d'Android. Testait `manageExternalStorage`, qui n'existe qu'à partir
  /// d'Android 11 : sur Android ≤ 10, la réponse était `false` pour toujours.
  Future<bool> _hasManageStorage() => StorageAccess.isGranted();

  bool _requiresManageStorage(String path) {
    if (!Platform.isAndroid) return false;
    return path.startsWith('/storage/emulated/0') || path.startsWith('/sdcard');
  }

  /// Bouton « Réglages » du bandeau de permission.
  ///
  /// Android ≤ 10 n'a pas d'écran « Accès à tous les fichiers » : y envoyer
  /// l'utilisateur ouvrait la fiche de l'application, où il n'y a rien de ce
  /// nom à activer. Là-bas, une demande runtime classique suffit — et elle
  /// affiche une vraie boîte de dialogue.
  Future<void> _requestAllFilesAccess() async {
    if (!await StorageAccess.usesAllFilesAccess()) {
      final ok = await StorageAccess.request();
      if (!mounted) return;
      if (ok) {
        _refresh();
      } else {
        // Refus (éventuellement « ne plus demander ») : la fiche de l'app est
        // alors le bon endroit, la permission « Stockage » s'y trouve.
        await StorageAccess.openSettings();
      }
      return;
    }
    try {
      await StorageAccess.openSettings();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            "Activez « Autoriser l'accès à tous les fichiers » puis "
            "revenez à l'app",
            style: TextStyle(fontSize: 13),
          ),
          duration: Duration(seconds: 6),
        ),
      );
    } catch (_) {
      await StorageAccess.request();
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
    // Le garde est ici plutôt que chez chaque appelant : `_refresh` est
    // appelé après des opérations longues (copie, déplacement, annulation
    // d'une mise à la corbeille) que l'utilisateur peut avoir quittées. Le
    // `mounted` du milieu ne protégeait que le SECOND `setState`.
    if (!mounted) return;
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
      showErrorSnack(context, '$e');
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
    final snack = SnackTarget.of(context);
    try {
      await _opener.openFile(path, ext, chooser: chooser);
    } on PlatformException catch (e) {
      final msg = e.code == 'INSTALL_PERMISSION_REQUIRED'
          ? (e.message ?? 'Autorisation requise — page Réglages ouverte.')
          : 'Aucune application trouvée pour ouvrir ce fichier';
      snack.error(msg, duration: kSnackLong);
    } catch (e) {
      // Le repli annonçait « Aucune application trouvée » quelle que soit la
      // panne. Or ce `catch` ne rattrape PAS les `PlatformException` — elles
      // sont traitées juste au-dessus : il ne voit donc que des erreurs d'une
      // tout autre nature, et affirmait à leur sujet quelque chose de faux.
      snack.error('Impossible d\'ouvrir ce fichier : $e');
    }
  }

  /// Installation d'une APK depuis l'explorateur. Vérifie la permission
  /// "Apps installant des applis inconnues" (Android 8+) et propose de
  /// l'accorder via Réglages si manquante. Le PackageInstaller système
  /// prend le relais : l'utilisateur garde la décision finale d'installation.
  Future<void> _installApk(String path) async {
    final snack = SnackTarget.of(context);
    try {
      final allowed = await _opener.canInstallApks();
      if (!allowed) {
        if (!mounted) return;
        final go = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('Autorisation requise'),
            content: const Text(
              'Pour installer une APK depuis Read Files Tech, vous devez '
              'd\'abord activer "Autoriser depuis cette source" dans les '
              'Réglages Android.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('Annuler'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('Ouvrir Réglages'),
              ),
            ],
          ),
        );
        if (go == true) {
          await _opener.openInstallPermissionSettings();
        }
        return;
      }
      await _opener.installApk(path);
    } on PlatformException catch (e) {
      snack.error(e.message ?? 'Installation impossible.');
    } catch (_) {
      snack.error('Installation impossible.');
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
    final snack = SnackTarget.of(context);
    try {
      await _opener.openWithPackage(path, _pdfTechPackage, 'application/pdf');
    } on PlatformException catch (e) {
      snack.error(
        e.code == 'NOT_INSTALLED'
            ? 'PDF Tech n\'est pas installé sur cet appareil.'
            : 'Impossible d\'ouvrir avec PDF Tech.',
      );
    }
  }

  Future<void> _sendToCloud(String path, String pkg, String label) async {
    final snack = SnackTarget.of(context);
    try {
      await _opener.sendToPackage(path, pkg);
    } on PlatformException catch (e) {
      snack.error(
        e.code == 'NOT_INSTALLED'
            ? '$label n\'est pas installé sur cet appareil.'
            : 'Erreur : impossible d\'envoyer vers $label.',
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
      showErrorSnack(context, 'Nom invalide (caractères / \\ .. interdits)');
      return;
    }
    final newPath = '${e.parent.path}/$newName';
    if (await FileSystemEntity.type(newPath) != FileSystemEntityType.notFound) {
      if (!mounted) return;
      showErrorSnack(context, '"$newName" existe déjà dans ce dossier');
      return;
    }
    try {
      await e.rename(newPath);
      _refresh();
    } catch (ex) {
      if (!mounted) return;
      // `catch (_)` masquait la cause : un renommage refusé par le système de
      // fichiers (volume en lecture seule, nom trop long, verrou) ressemblait
      // à un bug de l'application.
      showErrorSnack(context, 'Impossible de renommer ce fichier : $ex');
    }
  }

  /// Suppression douce : déplace dans la corbeille, avec annulation immédiate
  /// via la SnackBar. Pas de dialogue — l'action est réversible.
  Future<void> _moveToTrash(FileSystemEntity e) async {
    final name = e.path.basename;
    final snack = SnackTarget.of(context);
    try {
      final entry = await _trash.moveToTrash(e);
      await HapticFeedback.lightImpact();
      if (!mounted) return;
      _refresh();
      _showTrashedSnack('"$name" mis à la corbeille', [entry]);
    } catch (ex) {
      snack.error('Mise à la corbeille impossible : $ex');
    }
  }

  /// SnackBar d'annulation, unique action possible sur un SnackBar Material :
  /// « Annuler » est périssable (quelques secondes), alors que la corbeille
  /// reste accessible en permanence via l'icône dédiée de la barre d'outils.
  ///
  /// `persist: false` est OBLIGATOIRE ici : `SnackBar.persist` vaut par défaut
  /// `action != null` (snack_bar.dart), donc tout SnackBar porteur d'une action
  /// reste affiché indéfiniment — le timer de `duration` se déclenche puis sort
  /// sans rien faire (scaffold.dart). Sans ce flag, le bandeau ne disparaît
  /// jamais tout seul.
  void _showTrashedSnack(String message, List<TrashEntry> entries) {
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(message, style: const TextStyle(color: Colors.white)),
        backgroundColor: kBrandBlue,
        duration: kSnackLong,
        persist: false,
        showCloseIcon: true,
        closeIconColor: Colors.white,
        action: SnackBarAction(
          label: 'Annuler',
          backgroundColor: Colors.white,
          textColor: Colors.black,
          onPressed: () => _undoTrash(entries),
        ),
      ),
    );
  }

  /// Restaure les entrées d'une mise à la corbeille annulée.
  Future<void> _undoTrash(List<TrashEntry> entries) async {
    final snack = SnackTarget.of(context);
    int fail = 0;
    for (final entry in entries) {
      try {
        await _trash.restore(entry);
      } catch (_) {
        fail++;
      }
    }
    if (mounted) _refresh();
    if (fail == 0) return;
    snack.error('$fail élément(s) n\'ont pas pu être restaurés');
  }

  Future<void> _openTrash() async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const TrashScreen()),
    );
    if (!mounted) return;
    // Une restauration depuis la corbeille peut avoir recréé un élément ici.
    _refresh();
  }

  Future<void> _delete(FileSystemEntity e) async {
    final name = e.path.basename;
    final confirm = await confirmDelete(
      context,
      title: 'Supprimer définitivement',
      message:
          'Supprimer définitivement "$name" ?'
          '${e is Directory ? '\nLe dossier et tout son contenu seront supprimés.' : ''}'
          '\nCette action est irréversible : le fichier ne passera pas par la corbeille.',
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
      showErrorSnack(context, 'Erreur : $ex');
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
    } catch (e) {
      if (!mounted) return;
      // Volume en lecture seule, permission refusée, nom trop long : trois
      // causes, un seul message qui ne permettait de trancher aucune.
      showErrorSnack(context, 'Impossible de créer ce dossier : $e');
    }
  }

  // V-M1 — copie/déplacement unitaires : mêmes gardes que le lot
  // (`BatchOpsService.copyAll`). Ces deux méthodes étaient le jumeau oublié :
  // l'audit signalait `batch_ops_service.dart:47-48`, et le même
  // `copy('$destDir/$name')` sans vérification vivait ici, à deux endroits.
  Future<void> _copyFile(String sourcePath) async {
    // Copier un gros fichier prend du temps, et l'utilisateur n'attend pas
    // devant l'écran. Le bandeau passe par le messager capturé, qui appartient
    // au Scaffold englobant : `if (!mounted) return` avant l'affichage faisait
    // taire l'échec précisément quand la copie avait été longue.
    final snack = SnackTarget.of(context);
    final destDir = await FilePicker.getDirectoryPath();
    if (destDir == null) return;
    try {
      final dest = uniqueDestination(destDir, sourcePath.basename);
      final src = File(sourcePath);
      final srcLen = await src.length();
      await src.copy(dest);
      if (await File(dest).length() != srcLen) {
        throw FileSystemException('Copie incomplète', dest);
      }
      snack.info('Copié : ${dest.basename}');
    } catch (ex) {
      snack.error('Erreur : $ex');
    }
  }

  Future<void> _moveFile(String sourcePath) async {
    // Même raison que `_copyFile` : le résultat doit survivre au départ de
    // l'écran, et un déplacement raté encore plus qu'une copie ratée.
    final snack = SnackTarget.of(context);
    final destDir = await FilePicker.getDirectoryPath();
    if (destDir == null) return;
    try {
      final dest = uniqueDestination(destDir, sourcePath.basename);
      final src = File(sourcePath);
      final srcLen = await src.length();
      await src.copy(dest);
      // La suppression de l'original n'a lieu qu'après vérification de la
      // taille : une copie tronquée suivie d'un delete() détruisait le seul
      // exemplaire intact du fichier.
      if (await File(dest).length() != srcLen) {
        throw FileSystemException('Copie incomplète — original conservé', dest);
      }
      await src.delete();
      if (mounted) _refresh();
      snack.info('Déplacé : ${dest.basename}');
    } catch (ex) {
      snack.error('Erreur : $ex');
    }
  }

  // ---------- Batch actions ----------

  Future<void> _trashSelected() async {
    final paths = _selection.snapshot();
    if (paths.isEmpty) return;
    final entries = <TrashEntry>[];
    int fail = 0;
    for (final p in paths) {
      final type = FileSystemEntity.typeSync(p, followLinks: false);
      if (type == FileSystemEntityType.notFound) {
        fail++;
        continue;
      }
      try {
        entries.add(
          await _trash.moveToTrash(
            type == FileSystemEntityType.directory ? Directory(p) : File(p),
          ),
        );
      } catch (_) {
        fail++;
      }
    }
    _selection.clear();
    _refresh();
    if (!mounted) return;
    _showTrashedSnack(
      '${entries.length} mis à la corbeille'
      '${fail > 0 ? ' · $fail erreur(s)' : ''}',
      entries,
    );
  }

  Future<void> _deleteSelected() async {
    final count = _selection.count;
    if (count == 0) return;
    final confirm = await confirmDelete(
      context,
      title: 'Supprimer définitivement',
      message:
          'Supprimer définitivement $count élément${count > 1 ? 's' : ''} ?\n'
          'Les dossiers et leur contenu seront supprimés.\n'
          'Cette action est irréversible : rien ne passera par la corbeille.',
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
    final snack = SnackTarget.of(context);
    final destDir = await FilePicker.getDirectoryPath();
    if (destDir == null) return;
    final r = await _batch.copyAll(_selection.snapshot(), destDir, move: move);
    _selection.clear();
    if (move && mounted) _refresh();
    final message =
        '${r.ok} ${move ? 'déplacé' : 'copié'}${r.ok > 1 ? 's' : ''}'
        '${r.fail > 0 ? ' · ${r.fail} erreur(s)' : ''}';
    // Un lot partiellement échoué s'annonçait du même ton qu'un lot réussi.
    if (r.fail > 0) {
      snack.error(message);
    } else {
      snack.info(message);
    }
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
                onChanged: (v) {
                  // P1 perf — debounce 150ms pour éviter rebuild O(n)
                  // à chaque keystroke sur dossiers de 2k+ entrées.
                  _searchDebounce?.cancel();
                  _searchDebounce = Timer(_searchDebounceDelay, () {
                    if (!mounted) return;
                    setState(() {
                      _search = v;
                      _recomputeFiltered();
                    });
                  });
                },
              ),
            ),
            if (_permissionDenied)
              PermissionBanner(
                onOpenSettings: _requestAllFilesAccess,
                message: _permBannerMessage,
              ),
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
          onTrash: _trashSelected,
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
          onOpenTrash: _openTrash,
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
        kDriveInstalled: _kDriveInstalled,
        protonInstalled: _protonInstalled,
        onSendKDrive: (p) => _sendToCloud(p, _kDrivePackage, 'kDrive'),
        onSendProton: (p) =>
            _sendToCloud(p, _protonDrivePackage, 'Proton Drive'),
        onRename: _rename,
        onCopy: _copyFile,
        onMove: _moveFile,
        onInfo: (e2) => showFileInfoDialog(context, e2),
        onTrash: _moveToTrash,
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
        // APK : route vers PackageInstaller système avec garde de
        // permission, plutôt qu'un ACTION_VIEW générique qui serait
        // silencieusement bloqué si la perm n'est pas accordée.
        if (ext == 'apk') {
          _installApk(e.path);
        } else if (FileViewerRouter.canViewInternally(e.path)) {
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
