import 'dart:io';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:gpt_markdown/gpt_markdown.dart';
import 'package:flutter_highlight/flutter_highlight.dart';
import 'package:flutter_highlight/themes/github.dart';
import 'package:flutter_highlight/themes/atom-one-dark.dart';
import 'package:path/path.dart' as p;
import 'package:share_plus/share_plus.dart';
import '../explorer/file_type_helpers.dart';

class MdViewerScreen extends StatefulWidget {
  final String path;
  const MdViewerScreen({super.key, required this.path});

  @override
  State<MdViewerScreen> createState() => _MdViewerScreenState();
}

class _MdViewerScreenState extends State<MdViewerScreen> {
  String _content = '';
  bool _isLoading = true;
  bool _showSource = false;
  double _fontSize = 14;

  String get _name => widget.path.basename;
  bool get _isDark => Theme.of(context).brightness == Brightness.dark;

  /// Cap dur à l'ouverture pour éviter OOM sur low-end.
  // v2.11.1 — utilise PerfThresholds.viewerMaxBytes (files_tech_core)

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// V-M4 (audit 2026-08-02) — sans `imageBuilder`, `gpt_markdown` rend toute
  /// image via `NetworkImage(url)` (`markdown_component.dart:994`, gpt_markdown 1.1.7). Un `.md`
  /// piégé contenant `![](https://serveur/pixel.png)` déclenchait donc une
  /// requête HTTPS à la simple ouverture du fichier : adresse IP, horodatage
  /// et confirmation de lecture partaient vers un domaine arbitraire, sans
  /// aucune action de l'utilisateur et sans rien afficher de suspect.
  ///
  /// Ce n'était pas théorique : l'APK release **porte** la permission
  /// `INTERNET`, injectée par `transport-backend-cct` (télémétrie ML Kit) et
  /// absente du manifeste source. Vérifié à l'`aapt dump permissions` sur
  /// l'APK v2.14.0 publié. La lecture du seul manifeste source aurait conclu,
  /// à tort, que la requête ne pouvait pas aboutir.
  ///
  /// Les images **locales** restent rendues : c'est le cas légitime (un `.md`
  /// à côté de ses captures d'écran). Le chemin est résolu relativement au
  /// dossier du document et doit y rester.
  /// Signature `gpt_markdown` **1.1.7** : `width`/`height` viennent du texte
  /// alternatif parsé en `WxH` (`![100x200](url)`). La 1.1.6 n'avait que deux
  /// paramètres — vérifier `pubspec.lock`, pas la première version trouvée
  /// dans le cache pub, avant de toucher à cette signature.
  Widget _imageBuilder(
    BuildContext context,
    String url,
    double? width,
    double? height,
  ) {
    final uri = Uri.tryParse(url);
    final isRemote =
        uri != null &&
        (uri.scheme == 'http' ||
            uri.scheme == 'https' ||
            uri.scheme == 'ftp' ||
            uri.scheme == 'data');
    if (isRemote) return _blockedImage(context, url);

    final docDir = File(widget.path).parent.path;
    final target = p.normalize(
      p.isAbsolute(url) ? url : p.join(docDir, Uri.decodeFull(url)),
    );
    // Une image ne doit pas servir de sonde de système de fichiers : on ne
    // sort pas du dossier du document.
    if (!p.equals(target, docDir) && !p.isWithin(docDir, target)) {
      return _blockedImage(context, url);
    }
    final f = File(target);
    if (!f.existsSync()) return _blockedImage(context, url);
    return Image.file(
      f,
      width: width,
      height: height,
      errorBuilder: (_, _, _) => _blockedImage(context, url),
    );
  }

  Widget _blockedImage(BuildContext context, String url) {
    final cs = Theme.of(context).colorScheme;
    return Tooltip(
      message: 'Image externe bloquée : $url',
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          border: Border.all(color: cs.outlineVariant),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.image_not_supported_outlined,
              size: 14,
              color: cs.outline,
            ),
            const SizedBox(width: 4),
            Text(
              'image externe bloquée',
              style: TextStyle(fontSize: 11, color: cs.outline),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _load() async {
    try {
      final size = await File(widget.path).length();
      if (size > PerfThresholds.viewerMaxBytes) {
        if (!mounted) return;
        setState(() {
          _content = 'Fichier trop volumineux (>50 Mo)';
          _isLoading = false;
        });
        return;
      }
      final content = await File(widget.path).readAsString();
      if (!mounted) return;
      setState(() {
        _content = content;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _content = 'Erreur : $e';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: _showSource ? 'Aperçu rendu' : 'Source Markdown',
            icon: Icon(_showSource ? Icons.preview : Icons.code),
            onPressed: () => setState(() => _showSource = !_showSource),
          ),
          IconButton(
            icon: const Icon(Icons.share),
            onPressed: () => Share.shareXFiles([XFile(widget.path)]),
          ),
          if (_showSource)
            PopupMenuButton<double>(
              icon: const Icon(Icons.text_fields),
              onSelected: (v) => setState(() => _fontSize = v),
              itemBuilder: (_) => [10, 12, 13, 14, 16, 18, 20]
                  .map(
                    (s) => PopupMenuItem(
                      value: s.toDouble(),
                      child: Text(
                        '$s pt',
                        style: TextStyle(
                          fontWeight: _fontSize == s ? FontWeight.bold : null,
                        ),
                      ),
                    ),
                  )
                  .toList(),
            ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _showSource
          ? SingleChildScrollView(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: HighlightView(
                  _content,
                  language: 'markdown',
                  theme: _isDark ? atomOneDarkTheme : githubTheme,
                  padding: const EdgeInsets.all(16),
                  textStyle: TextStyle(
                    fontSize: _fontSize,
                    fontFamily: 'monospace',
                  ),
                ),
              ),
            )
          : SingleChildScrollView(
              padding: const EdgeInsets.all(20),
              child: GptMarkdown(
                _content,
                style: Theme.of(
                  context,
                ).textTheme.bodyMedium?.copyWith(height: 1.7),
                imageBuilder: _imageBuilder,
              ),
            ),
    );
  }
}
