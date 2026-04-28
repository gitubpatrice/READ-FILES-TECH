import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:share_plus/share_plus.dart';

class HtmlViewerScreen extends StatefulWidget {
  final String path;
  const HtmlViewerScreen({super.key, required this.path});

  @override
  State<HtmlViewerScreen> createState() => _HtmlViewerScreenState();
}

class _HtmlViewerScreenState extends State<HtmlViewerScreen> {
  late WebViewController _controller;
  bool _isLoading = true;
  bool _viewSource = false;
  /// JavaScript désactivé par défaut (sécurité). L'utilisateur peut
  /// l'activer manuellement via le bouton dans l'AppBar — opt-in
  /// explicite pour rendu fidèle d'HTML interactif.
  bool _jsEnabled = false;
  String _htmlContent = '';
  List<_ColorInfo> _colors = [];

  /// Limite la taille du HTML chargé (DoS local, OOM sur fichiers énormes).
  static const _maxHtmlBytes = 20 * 1024 * 1024;

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;

  @override
  void initState() {
    super.initState();
    _initController();
    _load();
  }

  void _initController() {
    _controller = WebViewController()
      ..setJavaScriptMode(_jsEnabled
          ? JavaScriptMode.unrestricted
          : JavaScriptMode.disabled)
      ..setNavigationDelegate(NavigationDelegate(
        onPageFinished: (_) =>
            mounted ? setState(() => _isLoading = false) : null,
        onNavigationRequest: (req) {
          // Bloquer toute navigation hors du fichier local d'origine.
          // Empêche un HTML malveillant d'exfiltrer en redirigeant vers https://attacker.
          if (req.url.startsWith('file://') || req.url.startsWith('about:')) {
            return NavigationDecision.navigate;
          }
          return NavigationDecision.prevent;
        },
      ));
  }

  Future<void> _toggleJs() async {
    if (!_jsEnabled) {
      // Avertissement avant d'activer JS.
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Activer JavaScript ?'),
          content: const Text(
            'JavaScript permet un rendu fidèle des pages interactives, '
            'mais un fichier HTML malveillant peut tenter de lire d\'autres '
            'fichiers locaux ou de communiquer avec internet. '
            'Ne l\'activez que si vous faites confiance à la source.',
            style: TextStyle(fontSize: 13),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('Annuler')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('Activer')),
          ],
        ),
      );
      if (ok != true) return;
    }
    setState(() {
      _jsEnabled = !_jsEnabled;
      _isLoading = true;
      _initController();
    });
    _controller.loadFile(widget.path);
  }

  Future<void> _load() async {
    try {
      final size = await File(widget.path).length();
      if (size > _maxHtmlBytes) {
        if (mounted) {
          setState(() {
            _htmlContent = 'Fichier trop volumineux (>20 Mo)';
            _isLoading = false;
          });
        }
        return;
      }
      _htmlContent = await File(widget.path).readAsString();
      _colors = _extractColors(_htmlContent);
      _controller.loadFile(widget.path);
    } catch (_) {
      if (mounted) {
        setState(() {
          _htmlContent = 'Impossible de lire le fichier HTML';
          _isLoading = false;
        });
      }
    }
  }

  List<_ColorInfo> _extractColors(String html) {
    final results = <_ColorInfo>[];
    final seen = <String>{};
    final hexReg = RegExp(r'#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})\b');
    for (final m in hexReg.allMatches(html)) {
      final code = m.group(0)!;
      if (seen.contains(code)) continue;
      seen.add(code);
      var h = code.replaceFirst('#', '');
      if (h.length == 3) h = h.split('').map((c) => c + c).join();
      final v = int.tryParse('FF$h', radix: 16);
      if (v != null) results.add(_ColorInfo(code, Color(v)));
    }
    return results;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: _jsEnabled ? 'JS activé (clic = désactiver)' : 'JS désactivé (clic = activer)',
            icon: Icon(
              _jsEnabled ? Icons.javascript : Icons.javascript_outlined,
              color: _jsEnabled ? Theme.of(context).colorScheme.error : null,
            ),
            onPressed: _toggleJs,
          ),
          IconButton(
            tooltip: _viewSource ? 'Vue rendue' : 'Code source',
            icon: Icon(_viewSource ? Icons.web : Icons.code),
            onPressed: () => setState(() => _viewSource = !_viewSource),
          ),
          IconButton(
            tooltip: 'Partager',
            icon: const Icon(Icons.share),
            onPressed: () => Share.shareXFiles([XFile(widget.path)]),
          ),
        ],
      ),
      body: Column(
        children: [
          if (_colors.isNotEmpty) _buildColorBar(),
          Expanded(
            child: _viewSource
                ? SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: SelectableText(_htmlContent,
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                  )
                : Stack(children: [
                    WebViewWidget(controller: _controller),
                    if (_isLoading)
                      const Center(child: CircularProgressIndicator()),
                  ]),
          ),
        ],
      ),
    );
  }

  Widget _buildColorBar() {
    return Container(
      height: 48,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
          border: Border(bottom: BorderSide(color: Theme.of(context).dividerColor))),
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _colors.length,
        separatorBuilder: (_, i) => const SizedBox(width: 8),
        itemBuilder: (_, i) {
          final c = _colors[i];
          return GestureDetector(
            onTap: () {
              Clipboard.setData(ClipboardData(text: c.code));
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(content: Text('Copié : ${c.code}'),
                    duration: const Duration(seconds: 1)),
              );
            },
            child: Row(children: [
              Container(
                width: 22, height: 22,
                decoration: BoxDecoration(
                  color: c.color,
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: Colors.grey.withValues(alpha: 0.4)),
                ),
              ),
              const SizedBox(width: 4),
              Text(c.code, style: const TextStyle(fontSize: 11)),
            ]),
          );
        },
      ),
    );
  }
}

class _ColorInfo {
  final String code;
  final Color color;
  _ColorInfo(this.code, this.color);
}
