import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/native_open_service.dart';

/// Widget affichant l'icône réelle d'une APK extraite via PackageManager
/// (méthode native `getApkIcon`). Cache process-wide en RAM pour éviter
/// de relire l'APK à chaque scroll. Tombe sur un placeholder Android
/// teal pendant la résolution et en cas d'échec.
///
/// Le cache est borné à [_maxCache] entrées (LRU naïf : la 1re vidée si
/// dépassement) pour ne pas grossir indéfiniment dans un dossier contenant
/// des centaines d'APKs.
class ApkIcon extends StatefulWidget {
  final String path;
  final double size;
  final Widget placeholder;

  const ApkIcon({
    super.key,
    required this.path,
    required this.size,
    required this.placeholder,
  });

  @override
  State<ApkIcon> createState() => _ApkIconState();

  /// Vide le cache (utilisé par tests, ou après suppression de masse).
  static void clearCache() => _cache.clear();
}

const int _maxCache = 128;
final Map<String, Uint8List?> _cache = <String, Uint8List?>{};
final NativeOpenService _opener = NativeOpenService();

class _ApkIconState extends State<ApkIcon> {
  Uint8List? _bytes;
  bool _resolved = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant ApkIcon old) {
    super.didUpdateWidget(old);
    if (old.path != widget.path) {
      _resolved = false;
      _bytes = null;
      _load();
    }
  }

  Future<void> _load() async {
    // Hit cache : `null` valide = échec connu (évite de retenter).
    if (_cache.containsKey(widget.path)) {
      if (mounted) {
        setState(() {
          _bytes = _cache[widget.path];
          _resolved = true;
        });
      }
      return;
    }
    final bytes = await _opener.getApkIcon(widget.path, size: 96);
    // Trim LRU naïf.
    if (_cache.length >= _maxCache) {
      _cache.remove(_cache.keys.first);
    }
    _cache[widget.path] = bytes;
    if (mounted) {
      setState(() {
        _bytes = bytes;
        _resolved = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_resolved || _bytes == null) return widget.placeholder;
    return Image.memory(
      _bytes!,
      width: widget.size,
      height: widget.size,
      fit: BoxFit.contain,
      gaplessPlayback: true,
      filterQuality: FilterQuality.medium,
      errorBuilder: (_, _, _) => widget.placeholder,
    );
  }
}
