import 'dart:io';
import 'dart:typed_data';

import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import '../../utils/image_bounds.dart';
import '../../widgets/error_panel.dart';
import '../explorer/file_type_helpers.dart';

class ImageViewerScreen extends StatefulWidget {
  final String path;
  final List<String> siblings; // autres images du même dossier

  const ImageViewerScreen({
    super.key,
    required this.path,
    this.siblings = const [],
  });

  @override
  State<ImageViewerScreen> createState() => _ImageViewerScreenState();
}

class _ImageViewerScreenState extends State<ImageViewerScreen> {
  late PageController _pageCtrl;
  late int _currentIndex;
  bool _showBars = true;

  List<String> get _images =>
      widget.siblings.isNotEmpty ? widget.siblings : [widget.path];

  String get _currentPath => _images[_currentIndex];
  String get _currentName => _currentPath.basename;

  @override
  void initState() {
    super.initState();
    _currentIndex = widget.siblings.isNotEmpty
        ? widget.siblings.indexOf(widget.path)
        : 0;
    if (_currentIndex < 0) _currentIndex = 0;
    _pageCtrl = PageController(initialPage: _currentIndex);
  }

  @override
  void dispose() {
    _pageCtrl.dispose();
    super.dispose();
  }

  void _toggleBars() => setState(() => _showBars = !_showBars);

  FileStat? _stat(String path) {
    try {
      return File(path).statSync();
    } catch (_) {
      return null;
    }
  }

  String _formatSize(int bytes) => FormatUtils.bytesStorage(bytes);

  void _showInfo() {
    final stat = _stat(_currentPath);
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _currentName,
              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
            ),
            const SizedBox(height: 16),
            if (stat != null) ...[
              _infoRow('Taille', _formatSize(stat.size)),
              _infoRow('Modifié', stat.modified.toString().substring(0, 19)),
            ],
            _infoRow('Chemin', _currentPath),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 72,
            child: Text(
              label,
              style: const TextStyle(
                fontSize: 12,
                color: Colors.grey,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(fontSize: 12),
              overflow: TextOverflow.ellipsis,
              maxLines: 2,
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: _showBars
          ? AppBar(
              backgroundColor: Colors.black.withValues(alpha: 0.7),
              foregroundColor: Colors.white,
              title: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _currentName,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 14),
                  ),
                  if (_images.length > 1)
                    Text(
                      '${_currentIndex + 1} / ${_images.length}',
                      style: const TextStyle(fontSize: 11, color: Colors.grey),
                    ),
                ],
              ),
              actions: [
                IconButton(
                  icon: const Icon(Icons.info_outline),
                  onPressed: _showInfo,
                ),
                IconButton(
                  icon: const Icon(Icons.share),
                  onPressed: () => Share.shareXFiles([XFile(_currentPath)]),
                ),
              ],
            )
          : null,
      body: GestureDetector(
        onTap: _toggleBars,
        child: PageView.builder(
          controller: _pageCtrl,
          itemCount: _images.length,
          onPageChanged: (i) => setState(() => _currentIndex = i),
          itemBuilder: (_, i) {
            // Resize-decode au max écran ×2 → décodage rapide + pas d'OOM
            // sur photos 12 MP (4000×3000 = 48 Mo bitmap décompressé sinon).
            // ×2 pour permettre un peu de zoom InteractiveViewer.
            // P2.4 v2.13.0 — `sizeOf` + `devicePixelRatioOf` ne rebuild pas
            // sur changement d'autres props MediaQuery (keyboard inset).
            final shortestSide = MediaQuery.sizeOf(context).shortestSide;
            final dpr = MediaQuery.devicePixelRatioOf(context);
            final maxPx = (shortestSide * dpr * 2).toInt();
            return InteractiveViewer(
              minScale: 0.5,
              maxScale: 6.0,
              child: Center(
                child: Image.file(
                  File(_images[i]),
                  fit: BoxFit.contain,
                  cacheWidth: maxPx,
                  filterQuality: FilterQuality.medium,
                  errorBuilder: (_, e, _) => _ImageErrorPanel(path: _images[i]),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Ce qui s'affiche quand une image refuse de se decoder.
///
/// **Pourquoi ce n'est pas qu'une question d'esthetique.** L'ecran montrait une
/// icone grise et « Impossible d'afficher cette image » sur fond noir. Le
/// message etait le meme pour un fichier tronque, un format non supporte et une
/// image piegee annoncant 60000x60000 : rien ne permettait de distinguer un
/// fichier abime d'une tentative deliberee.
///
/// Or le diagnostic precis EXISTE deja — `ImageBounds.assertSafeBounds` rend
/// « Dimensions image suspectes (LxH, max ...) » — mais il n'etait cable que
/// dans la compression, la conversion et l'EXIF. Jamais dans la visionneuse,
/// c'est-a-dire a l'endroit ou l'utilisateur ouvre le fichier. Constate sur
/// appareil le 2026-08-11.
class _ImageErrorPanel extends StatefulWidget {
  final String path;
  const _ImageErrorPanel({required this.path});

  @override
  State<_ImageErrorPanel> createState() => _ImageErrorPanelState();
}

class _ImageErrorPanelState extends State<_ImageErrorPanel> {
  /// Message affiche. Part d'un generique honnete, puis se precise si l'en-tete
  /// du fichier permet d'en dire plus.
  String _message = 'Impossible d\'afficher cette image.';

  @override
  void initState() {
    super.initState();
    _diagnostiquer();
  }

  Future<void> _diagnostiquer() async {
    try {
      // Seuls les tout premiers octets portent les dimensions. On ne relit
      // donc PAS le fichier entier : il vient d'echouer au decodage, rien ne
      // dit qu'il soit d'une taille raisonnable.
      final handle = await File(widget.path).open();
      Uint8List entete;
      try {
        entete = await handle.read(64 * 1024);
      } finally {
        await handle.close();
      }

      if (entete.isEmpty) {
        _poser('Ce fichier est vide.');
        return;
      }
      final refus = ImageBounds.assertSafeBounds(entete);
      if (refus != null) {
        _poser('$refus\n\nL\'image n\'a pas ete decodee.');
        return;
      }
      _poser(
        'Impossible d\'afficher cette image : le fichier est incomplet ou '
        'son format n\'est pas reconnu.',
      );
    } catch (_) {
      // Le diagnostic est un CONFORT : s'il echoue, le message generique
      // reste. Ne jamais laisser l'ecran vide pour autant.
      _poser('Impossible de lire ce fichier.');
    }
  }

  void _poser(String message) {
    if (!mounted) return;
    setState(() => _message = message);
  }

  @override
  Widget build(BuildContext context) => ErrorPanel(message: _message);
}
