import 'dart:io';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
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
            final mq = MediaQuery.of(context);
            final maxPx = (mq.size.shortestSide * mq.devicePixelRatio * 2)
                .toInt();
            return InteractiveViewer(
              minScale: 0.5,
              maxScale: 6.0,
              child: Center(
                child: Image.file(
                  File(_images[i]),
                  fit: BoxFit.contain,
                  cacheWidth: maxPx,
                  filterQuality: FilterQuality.medium,
                  errorBuilder: (_, e, _) => Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(
                          Icons.broken_image_outlined,
                          color: Colors.grey,
                          size: 64,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          'Impossible d\'afficher cette image',
                          style: const TextStyle(color: Colors.grey),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
