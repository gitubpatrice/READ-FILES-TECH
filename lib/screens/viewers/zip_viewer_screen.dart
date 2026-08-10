import 'dart:io';
import 'package:archive/archive.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import '../../services/archive_extract_service.dart';
import '../../utils/file_caps.dart';
import '../../utils/snack_utils.dart';
import '../../widgets/error_panel.dart';
import '../explorer/file_type_helpers.dart';

class ZipViewerScreen extends StatefulWidget {
  final String path;
  const ZipViewerScreen({super.key, required this.path});

  @override
  State<ZipViewerScreen> createState() => _ZipViewerScreenState();
}

class _ZipViewerScreenState extends State<ZipViewerScreen> {
  List<ArchiveFile> _files = [];
  bool _isLoading = true;
  String? _error;
  String _search = '';

  /// Nullable, et non `late`. Quand `_load` échoue, il ne l'affecte pas :
  /// avec `late`, le premier accès levait un `LateInitializationError`, et le
  /// bouton « Extraire tout » restait cliquable puisqu'il ne dépendait que de
  /// `_isLoading`. Ouvrir une archive corrompue puis toucher ce bouton faisait
  /// donc planter l'application. Trouvé par la relecture GPT du 2026-08-09.
  Archive? _archive;

  String get _name => widget.path.basename;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// v2.13.2 (#5) — délégué à FileCaps.zipViewer (était inline).
  /// Chargement complet en RAM par archive package — DoS au-delà.
  /// 500 Mo couvre les usages légitimes (archives système, logs zippés).
  static const _maxZipBytes = FileCaps.zipViewer;

  Future<void> _load() async {
    try {
      final fileSize = await File(widget.path).length();
      if (fileSize > _maxZipBytes) {
        throw Exception(
          'Archive trop volumineuse (>${_maxZipBytes ~/ (1024 * 1024)} Mo)',
        );
      }
      final bytes = await File(widget.path).readAsBytes();
      // `decodeBytes` ne décompresse RIEN ici : il lit le catalogue et garde
      // les entrées sous leur forme compressée (`ArchiveFile.rawContent`).
      // C'est l'accès à `.content` qui inflate, et sans borne — d'où
      // `safeEntryBytes` sur chaque chemin d'extraction. Les gardes
      // n'arrivent donc pas après coup : à cet instant, rien n'a encore été
      // développé.
      final archive = ZipDecoder().decodeBytes(bytes);
      _archive = archive;
      // On garde TOUS les fichiers (y compris ceux à 0 octet — .gitkeep,
      // __init__.py vide, etc.) et tous les dossiers.
      final files = archive.files.toList();
      files.sort((a, b) => a.name.compareTo(b.name));
      if (!mounted) return;
      setState(() {
        _files = files;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        // La cause était jetée : « Impossible de lire l'archive » ne distingue
        // pas un fichier tronqué d'une archive chiffrée ou d'un dépassement de
        // cap, et ne laisse rien à rapporter.
        _error = 'Impossible de lire l\'archive : $e';
        _isLoading = false;
      });
    }
  }

  /// C-C9 — dernier formateur de taille écrit à la main de l'application.
  /// Il ne connaissait pas le gigaoctet : une entrée de 2 Go s'affichait
  /// « 2048.00 MB », et deux décimales là où tout le reste de l'app en met
  /// zéro au-delà du mégaoctet. Même liste d'unités partout, désormais.
  String _formatSize(int bytes) => FormatUtils.bytesStorage(bytes);

  IconData _iconForExt(String name) {
    final ext = PathUtils.fileExt(name).toLowerCase();
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
      case 'txt':
      case 'md':
        return Icons.text_snippet_outlined;
      case 'js':
      case 'ts':
      case 'dart':
      case 'php':
      case 'py':
      case 'java':
        return Icons.code;
      case 'html':
      case 'css':
      case 'xml':
        return Icons.html_outlined;
      case 'docx':
      case 'doc':
      case 'odt':
        return Icons.article_outlined;
      case 'xlsx':
      case 'xls':
      case 'csv':
        return Icons.table_chart_outlined;
      default:
        return Icons.insert_drive_file_outlined;
    }
  }

  Color _colorForExt(String name) {
    final ext = PathUtils.fileExt(name).toLowerCase();
    switch (ext) {
      case 'pdf':
        return Colors.red;
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
        return Colors.purple;
      case 'js':
      case 'ts':
        return Colors.yellow.shade700;
      case 'html':
        return Colors.orange;
      case 'css':
        return Colors.blue;
      case 'php':
        return Colors.indigo;
      case 'docx':
      case 'doc':
        return Colors.blue.shade700;
      case 'xlsx':
      case 'csv':
        return Colors.green;
      default:
        return Colors.grey;
    }
  }

  // Les plafonds ne sont plus recopiés dans une constante de cette classe :
  // les appels ci-dessous passent directement par `FileCaps`. Ce n'est pas
  // qu'une question de style — référencer une `static const` de ce `State`
  // depuis la fermeture d'un `Isolate.run` suffisait à y embarquer `this`, et
  // donc le binding Flutter, non transmissible. Voir `archive_extract_service`.

  /// Extraction d'une entrée unique, puis partage.
  ///
  /// Le travail part dans un isolate : décompresser jusqu'au plafond de
  /// 200 Mo sur le thread de l'interface figeait l'application — ANR constaté
  /// sur Galaxy S9 le 2026-08-09 avec une bombe de 306 Ko produisant 300 Mo.
  Future<void> _extractAndShare(ArchiveFile file) async {
    final snack = SnackTarget.of(context);
    try {
      final dir = await getTemporaryDirectory();
      // On ne réutilise PAS `file.name` (peut contenir des sous-dossiers
      // malicieux) — juste le basename, sanitisé.
      final raw = PathUtils.fileName(file.name).split('\\').last;
      final fileName = raw.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
      final safe = fileName.isEmpty ? 'file' : fileName;
      final outPath = '${dir.path}/$safe';

      await extractSingleEntryIsolate(
        zipPath: widget.path,
        entryName: file.name,
        outPath: outPath,
        maxEntryBytes: FileCaps.zipEntryDecompressed,
      );

      // Même raison que pour l'OCR : un bandeau qui arrive après coup informe,
      // une feuille de partage qui surgit alors que l'utilisateur a navigué
      // ailleurs interrompt et ressemble à une action fantôme.
      if (!mounted) return;
      await Share.shareXFiles([XFile(outPath)]);
    } catch (e) {
      snack.error('Erreur : $e');
    }
  }

  /// Extraction complète, dans un isolate.
  ///
  /// Toute la logique — anti zip-slip, plafonds, liens symboliques — vit dans
  /// `archive_extract_service.dart`, qui est testable. Cet écran ne fait plus
  /// que présenter le résultat.
  Future<void> _extractAll() async {
    if (_archive == null) return;
    final snack = SnackTarget.of(context);
    setState(() => _isLoading = true);
    try {
      final dir = await getApplicationDocumentsDirectory();
      final base = _name.replaceAll(RegExp(r'\.\w+$'), '');
      final outDirPath = '${dir.path}/${base}_extracted';
      final zipPath = widget.path;

      final r = await extractArchiveIsolate(
        zipPath: zipPath,
        outDirPath: outDirPath,
        maxEntryBytes: FileCaps.zipEntryDecompressed,
        maxTotalBytes: FileCaps.zipExtractTotal,
      );

      if (mounted) setState(() => _isLoading = false);
      final where = PathUtils.fileName(r.outDirPath);
      if (r.skipped > 0) {
        snack.error(
          'Extrait dans : $where — ${r.skipped} entrée(s) refusée(s)',
        );
      } else {
        snack.info('Extrait dans : $where (${r.written} fichier(s))');
      }
    } catch (e) {
      if (mounted) setState(() => _isLoading = false);
      snack.error('Erreur : $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _search.isEmpty
        ? _files
        : _files
              .where(
                (f) => f.name.toLowerCase().contains(_search.toLowerCase()),
              )
              .toList();

    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: 'Extraire tout',
            icon: const Icon(Icons.unarchive_outlined),
            // Désactivé tant qu'aucune archive n'a été lue — pas seulement
            // pendant le chargement.
            onPressed: (_isLoading || _archive == null) ? null : _extractAll,
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
          ? ErrorPanel(message: _error!)
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          decoration: InputDecoration(
                            hintText: 'Rechercher dans l\'archive…',
                            prefixIcon: const Icon(Icons.search, size: 18),
                            isDense: true,
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(8),
                            ),
                            contentPadding: const EdgeInsets.symmetric(
                              vertical: 8,
                            ),
                          ),
                          onChanged: (v) => setState(() => _search = v),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        '${filtered.length} fichier${filtered.length > 1 ? 's' : ''}',
                        style: const TextStyle(
                          color: Colors.grey,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: ListView.builder(
                    itemCount: filtered.length,
                    itemBuilder: (_, i) {
                      final file = filtered[i];
                      final isDir = !file.isFile;
                      final color = isDir
                          ? Colors.amber
                          : _colorForExt(file.name);
                      return ListTile(
                        dense: true,
                        leading: Icon(
                          isDir
                              ? Icons.folder_outlined
                              : _iconForExt(file.name),
                          color: color,
                          size: 22,
                        ),
                        title: Text(
                          PathUtils.fileName(file.name),
                          style: const TextStyle(fontSize: 13),
                        ),
                        subtitle: Text(
                          file.name.contains('/')
                              ? file.name.substring(
                                  0,
                                  file.name.lastIndexOf('/'),
                                )
                              : '',
                          style: const TextStyle(fontSize: 11),
                          overflow: TextOverflow.ellipsis,
                        ),
                        trailing: isDir
                            ? null
                            : Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text(
                                    _formatSize(file.size),
                                    style: const TextStyle(
                                      fontSize: 11,
                                      color: Colors.grey,
                                    ),
                                  ),
                                  const SizedBox(width: 4),
                                  IconButton(
                                    padding: EdgeInsets.zero,
                                    constraints: const BoxConstraints(),
                                    icon: const Icon(Icons.share, size: 18),
                                    onPressed: () => _extractAndShare(file),
                                  ),
                                ],
                              ),
                      );
                    },
                  ),
                ),
              ],
            ),
    );
  }
}
