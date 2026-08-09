import 'dart:io';
import 'dart:typed_data';
import 'package:archive/archive.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import '../../utils/archive_safe.dart';
import '../../utils/file_caps.dart';
import '../../utils/snack_utils.dart';
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

  /// Limites anti-DoS, centralisées dans `FileCaps` (C-C9) : elles étaient
  /// codées en dur ici alors que la même valeur de 200 Mo existait déjà sous
  /// `FileCaps.zipEntryDecompressed`.
  static const _maxEntryBytes = FileCaps.zipEntryDecompressed;
  static const _maxTotalExtractBytes = FileCaps.zipExtractTotal;

  /// Vérifie **textuellement** qu'un nom d'entrée d'archive ne sort pas de
  /// [baseDir] : pas de chemin absolu, pas de `..`, pas de lettre de lecteur,
  /// pas d'octet NUL. Retourne le chemin reconstruit, ou `null` si rejeté.
  ///
  /// **Ce contrôle ne résout PAS les liens symboliques**, contrairement à ce
  /// que promettait le commentaire de fin de méthode — signalé par la
  /// relecture GPT du 2026-08-09. Il ne peut pas : les dossiers n'existent
  /// pas encore à cet instant. Un lien symbolique **déjà présent** dans le
  /// dossier de destination laisserait donc passer un chemin textuellement
  /// correct mais résolvant hors de [baseDir].
  ///
  /// La résolution réelle a lieu dans `_extractAll`, après création du dossier
  /// parent et juste avant l'écriture. Les deux contrôles sont nécessaires :
  /// celui-ci écarte l'entrée avant toute décompression, l'autre couvre ce que
  /// le système de fichiers peut avoir sous les pieds.
  String? _safeJoin(String baseDir, String entryName) {
    // Normalize séparateurs (zip Windows : \, Unix : /)
    final normalized = entryName.replaceAll('\\', '/');
    // Reject paths absolus + entrées vides + traversal
    if (normalized.isEmpty || normalized.startsWith('/')) return null;
    final segments = normalized.split('/');
    if (segments.any((s) => s == '..' || s == '.')) return null;
    // Reject Windows-style drive letters / device paths
    if (RegExp(r'^[a-zA-Z]:').hasMatch(normalized)) return null;
    // L'octet NUL est ecrit sous forme echappee : ecrit brut, il faisait
    // traiter TOUT ce fichier comme binaire par git, et ses diffs
    // devenaient invisibles en revue.
    if (normalized.contains('\x00')) return null;
    // Reconstruction : baseDir/segments, puis comparaison de préfixe.
    final candidate = '$baseDir/$normalized';
    final resolvedBase = File(baseDir).absolute.path;
    final resolvedFile = File(candidate).absolute.path;
    if (!resolvedFile.startsWith(resolvedBase + Platform.pathSeparator) &&
        resolvedFile != resolvedBase) {
      return null;
    }
    return candidate;
  }

  Future<void> _extractAndShare(ArchiveFile file) async {
    final snack = SnackTarget.of(context);
    try {
      // `file.size` vient de l'en-tête du ZIP : c'est l'attaquant qui l'écrit.
      // Le test seul laissait passer une entrée annonçant 1 Ko et produisant
      // 80 Mo, et `file.content` décompressait ensuite sans aucune borne.
      // `safeEntryBytes` compte les octets réellement produits et interrompt
      // l'inflation au plafond. Même correctif que les quatre autres sites
      // (v2.15) — ces deux-ci avaient été oubliés parce qu'ils sont sur le
      // chemin d'extraction et non sur celui de l'aperçu.
      final bytes = safeEntryBytes(file, file.name, _maxEntryBytes);
      final dir = await getTemporaryDirectory();
      // On ne réutilise PAS file.name (peut contenir des sous-dossiers
      // malicieux) — on prend juste le basename, sanitisé.
      final raw = PathUtils.fileName(file.name).split('\\').last;
      final fileName = raw.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
      final safe = fileName.isEmpty ? 'file' : fileName;
      final outPath = '${dir.path}/$safe';
      await File(outPath).writeAsBytes(bytes);
      // Même raison que pour l'OCR : un bandeau qui arrive après coup informe,
      // une feuille de partage qui surgit alors que l'utilisateur a navigué
      // ailleurs interrompt et ressemble à une action fantôme.
      if (!mounted) return;
      await Share.shareXFiles([XFile(outPath)]);
    } catch (e) {
      snack.error('Erreur : $e');
    }
  }

  Future<void> _extractAll() async {
    final archive = _archive;
    if (archive == null) return;
    final snack = SnackTarget.of(context);
    setState(() => _isLoading = true);
    try {
      final dir = await getApplicationDocumentsDirectory();
      final base = _name.replaceAll(RegExp(r'\.\w+$'), '');
      final outDir = Directory('${dir.path}/${base}_extracted');
      await outDir.create(recursive: true);
      // Résolu une fois : c'est la racine réelle à laquelle chaque parent créé
      // sera comparé, liens symboliques suivis.
      final resolvedBase = await outDir.resolveSymbolicLinks();

      int totalExtracted = 0;
      int skipped = 0;
      for (final file in archive.files) {
        if (!file.isFile) continue;
        // Anti zip-slip : valider le path AVANT de dépenser le moindre octet
        // à décompresser une entrée qu'on refusera de toute façon d'écrire.
        final target = _safeJoin(outDir.path, file.name);
        if (target == null) {
          skipped++;
          continue;
        }

        // Le budget restant borne l'entrée courante. Sans cela, la dernière
        // entrée pouvait à elle seule ajouter 200 Mo au-delà du plafond
        // cumulé, celui-ci n'étant vérifié qu'AVANT de l'écrire.
        final remaining = _maxTotalExtractBytes - totalExtracted;
        if (remaining <= 0) {
          throw Exception(
            'Archive trop volumineuse '
            '(>${_maxTotalExtractBytes ~/ (1024 * 1024 * 1024)} Go décompressé)',
          );
        }
        final cap = remaining < _maxEntryBytes ? remaining : _maxEntryBytes;

        // `file.size` et `file.content` étaient tous deux à la merci de
        // l'en-tête : le premier annonçait ce qu'il voulait, le second
        // décompressait sans borne. Le cumul, lui, s'incrémentait de la
        // taille DÉCLARÉE — donc une archive annonçant 1 Ko par entrée
        // pouvait écrire des gigaoctets sans jamais approcher le plafond.
        final Uint8List bytes;
        try {
          bytes = safeEntryBytes(file, file.name, cap);
        } on ArchiveTooLargeException {
          skipped++;
          continue;
        }

        final outFile = File(target);
        await outFile.parent.create(recursive: true);

        // `_safeJoin` ne juge que le texte du nom d'entrée. Si le dossier de
        // destination contient déjà un lien symbolique pointant ailleurs —
        // posé par une extraction antérieure ou par une autre application —
        // un chemin textuellement correct peut résoudre hors de `outDir`.
        // La vérification a lieu ici, après création du parent et avant
        // l'écriture, seul moment où le système de fichiers peut répondre.
        final resolvedParent = await outFile.parent.resolveSymbolicLinks();
        if (resolvedParent != resolvedBase &&
            !resolvedParent.startsWith(resolvedBase + Platform.pathSeparator)) {
          skipped++;
          continue;
        }

        // Résoudre le parent ne suffit pas : si la CIBLE elle-même existe déjà
        // sous forme de lien symbolique pointant ailleurs, son parent reste
        // dans `outDir` et `writeAsBytes` suivrait le lien. Le cas est très
        // improbable ici — la destination est le dossier privé de
        // l'application — mais la garde ne coûte rien et l'improbable n'est pas
        // l'impossible. Signalé par la relecture Gemini du 2026-08-09.
        if (await FileSystemEntity.isLink(target)) {
          skipped++;
          continue;
        }

        await outFile.writeAsBytes(bytes);
        // Compté sur ce qui a réellement été produit.
        totalExtracted += bytes.length;
      }
      if (mounted) setState(() => _isLoading = false);
      final msg = skipped > 0
          ? 'Extrait dans : ${PathUtils.fileName(outDir.path)} ($skipped entrée(s) ignorée(s))'
          : 'Extrait dans : ${PathUtils.fileName(outDir.path)}';
      if (skipped > 0) {
        snack.error(msg);
      } else {
        snack.info(msg);
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
          ? Center(child: Text('Erreur : $_error'))
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
