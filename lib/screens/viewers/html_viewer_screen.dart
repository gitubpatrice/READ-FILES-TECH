import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';
import 'package:share_plus/share_plus.dart';
import '../../utils/color_extract.dart';
import '../../utils/file_caps.dart';
import 'reader_viewer_screen.dart';
import '../explorer/file_type_helpers.dart';

/// Politique appliquée au document rendu dans la WebView.
///
/// `default-src 'none'` par défaut, puis on ré-autorise strictement ce dont un
/// document local a besoin. `connect-src 'none'` coupe `fetch`, XHR et
/// WebSocket ; aucune directive n'autorise `http:` ni `https:`.
const String kHtmlViewerCsp =
    "default-src 'none'; "
    'img-src file: data: blob:; '
    'media-src file: data: blob:; '
    "style-src file: data: 'unsafe-inline'; "
    'font-src file: data:; '
    "script-src file: data: 'unsafe-inline' 'unsafe-eval'; "
    'frame-src file:; '
    "form-action 'none'; "
    "connect-src 'none'; "
    "base-uri 'none'";

/// Insère la balise CSP en tête de [html] et renvoie le document à charger.
///
/// La CSP doit précéder toute balise capable de charger une ressource.
///
/// La première version cherchait `<head` dans le texte brut et insérait après
/// le `>` suivant. Contournable en une ligne : un document commençant par
/// `<!-- <head> -->` faisait insérer la balise DANS le commentaire, donc
/// inerte, et l'`<img src=https://…>` du vrai `<head>` partait quand même.
/// Chercher un tag par sous-chaîne, c'est parser du HTML à la main — et le
/// HTML gagne toujours. (Signalé par une relecture externe, 2026-08-09.)
///
/// On n'en cherche donc plus aucun. La balise est placée en TÊTE du document,
/// où rien ne peut la précéder ; le parseur HTML la remonte lui-même dans le
/// `<head>` implicite (« before html » → « before head » → insertion dans
/// head), quel que soit le contenu qui suit.
///
/// Seule exception : un `<!DOCTYPE>` doit rester le tout premier nœud, faute
/// de quoi la page bascule en quirks mode et se réaffiche différemment. On
/// insère donc juste après lui quand il ouvre le document. Un doctype précédé
/// d'autre chose est de toute façon déjà ignoré par le parseur : le cas ne se
/// dégrade pas.
///
/// Fonction pure et publique pour être testable sans WebView (voir
/// `test/html_csp_injection_test.dart`).
String injectCsp(String html) {
  const tag =
      '<meta http-equiv="Content-Security-Policy" content="$kHtmlViewerCsp">';
  final lead = html.trimLeft();
  final skipped = html.length - lead.length;
  if (lead.toLowerCase().startsWith('<!doctype')) {
    final close = html.indexOf('>', skipped);
    if (close >= 0) return html.replaceRange(close + 1, close + 1, tag);
  }
  return '$tag$html';
}

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
  List<ColorMatch> _colors = [];

  /// v2.13.2 (#5) — délégué à FileCaps.htmlViewer (était inline).
  static const _maxHtmlBytes = FileCaps.htmlViewer;

  String get _name => widget.path.basename;

  @override
  void initState() {
    super.initState();
    _initController();
    _load();
  }

  /// Dossier parent du fichier d'origine — sert à restreindre les navigations
  /// `file://` à ce sous-arbre. Empêche un HTML malveillant de `<a href>` vers
  /// d'autres fichiers du téléphone via le viewer.
  late final String _allowedParentDir = File(widget.path).parent.path;

  /// Extensions autorisées pour navigation `file://` depuis un HTML rendu.
  /// G8 v2.12.1 — sans whitelist, un HTML piégé pouvait `<iframe src=`
  /// `"file:///.../db.sqlite">` pour faire afficher du contenu sensible.
  /// Le scope (`_allowedParentDir`) restreint déjà au dossier du HTML
  /// d'origine, donc on autorise largement les formats document/média
  /// usuels qu'un HTML peut légitimement référencer ; on n'exclut que
  /// les blobs binaires opaques (db, bak, log, etc.) où l'affichage n'a
  /// pas de sens et indiquerait probablement une tentative de fuite.
  static const _allowedFileExts = {
    // Web assets
    '.html', '.htm', '.xhtml',
    '.css', '.js', '.mjs',
    // Images
    '.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.ico', '.bmp', '.avif',
    // Fonts
    '.woff', '.woff2', '.ttf', '.otf', '.eot',
    // Documents (texte / structuré)
    '.txt', '.md', '.json', '.xml', '.yaml', '.yml', '.csv', '.tsv', '.log',
    '.pdf', '.epub',
    // Médias (lecture inline)
    '.mp3', '.ogg', '.wav', '.m4a', '.flac',
    '.mp4', '.webm', '.mov',
  };

  /// True si l'URL `file://...` cible un fichier strictement dans le dossier
  /// parent du HTML d'origine ET dans la whitelist d'extensions. Utilise
  /// `p.isWithin` (package:path) pour gérer correctement les séparateurs
  /// Windows/POSIX et les `..` éventuels.
  bool _isFileUrlAllowed(String url) {
    if (!url.startsWith('file://')) return false;
    try {
      final target = Uri.parse(url).toFilePath();
      final inScope =
          p.equals(target, _allowedParentDir) ||
          p.isWithin(_allowedParentDir, target);
      if (!inScope) return false;
      // Cas particulier : navigation vers le répertoire racine lui-même
      // (listing). Conserve le comportement antérieur.
      if (p.equals(target, _allowedParentDir)) return true;
      final ext = p.extension(target).toLowerCase();
      return _allowedFileExts.contains(ext);
    } catch (_) {
      return false;
    }
  }

  void _initController() {
    _controller = WebViewController()
      ..setJavaScriptMode(
        _jsEnabled ? JavaScriptMode.unrestricted : JavaScriptMode.disabled,
      )
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (_) =>
              mounted ? setState(() => _isLoading = false) : null,
          onNavigationRequest: (req) {
            // about: → toujours autorisé (about:blank, about:srcdoc).
            if (req.url.startsWith('about:')) {
              return NavigationDecision.navigate;
            }
            // file:// → restreint au dossier parent du fichier ouvert.
            // Empêche un HTML local malveillant de naviguer vers
            // /sdcard/Android/data/... ou autres zones sensibles.
            if (_isFileUrlAllowed(req.url)) {
              return NavigationDecision.navigate;
            }
            return NavigationDecision.prevent;
          },
        ),
      );
    // S-M2 (audit 2026-08-02) — le NavigationDelegate ne voit QUE les
    // navigations. Les **sous-ressources** (`<img src=https://…>`, `<link
    // rel=stylesheet>`, `<script src>`, `fetch`) ne passent pas par
    // `onNavigationRequest` : ouvrir un HTML piégé suffisait à émettre une
    // requête sortante — adresse IP et confirmation de lecture — même avec
    // JavaScript désactivé.
    //
    // L'atténuation supposée (« pas de permission INTERNET en release »)
    // n'existe pas : `aapt dump permissions` sur l'APK v2.14.0 publié montre
    // INTERNET, injectée par `transport-backend-cct` via ML Kit.
    //
    // `webview_flutter` n'expose PAS `shouldInterceptRequest` (vérifié dans
    // `webview_flutter_android` 4.13.0 : ni intercepteur de ressources, ni
    // `setBlockNetworkLoads`). La contre-mesure disponible et normative est
    // celle que l'audit suggérait aussi : une **CSP injectée**, appliquée par
    // le moteur Chromium de la WebView. Voir `_render`.
    final platform = _controller.platform;
    if (platform is AndroidWebViewController) {
      // `loadHtmlString` passe par `loadDataWithBaseUrl`, qui n'active pas
      // l'accès fichier — contrairement à `loadFile`. Sans ça, un HTML
      // référençant sa feuille de style voisine s'afficherait nu.
      platform.setAllowFileAccess(true);
    }
  }

  /// Charge le document dans la WebView avec une **CSP** en tête de `<head>`.
  ///
  /// Remplace `loadFile`. La politique n'autorise que `file:` et `data:`, et
  /// coupe `connect-src` : aucune image, feuille de style, police, iframe ni
  /// `fetch`/XHR ne peut viser le réseau, JavaScript activé ou non.
  ///
  /// Un `<iframe src="file:///…">` n'était pas non plus couvert par la
  /// whitelist d'extensions : `shouldOverrideUrlLoading` renvoie `false` pour
  /// les sous-frames (`WebViewClientProxyApi.java:78`,
  /// `request.isForMainFrame()`), donc `onNavigationRequest` ne pouvait rien
  /// bloquer là — contrairement à ce qu'affirme le commentaire G8 v2.12.1
  /// plus haut. `frame-src file:` les ramène au moins dans le périmètre du
  /// document, et `_isFileUrlAllowed` garde la navigation principale.
  ///
  /// `baseUrl` pointe sur le dossier du document pour que les chemins
  /// relatifs (`./style.css`, `img/photo.png`) continuent de résoudre.
  Future<void> _render() async {
    final doc = injectCsp(_htmlContent);
    final dir = File(widget.path).parent.path.replaceAll('\\', '/');
    await _controller.loadHtmlString(doc, baseUrl: 'file://$dir/');
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
              child: const Text('Annuler'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Activer'),
            ),
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
    _render();
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
      // G16 v2.12.1 — utilise le helper partagé `extractColors` (txt_viewer
      // l'utilisait déjà) : supporte hex + rgb()/rgba(), élimine la
      // duplication de `_ColorInfo` + regex inline.
      _colors = _htmlContent.length > 1024 * 1024
          ? const []
          : extractColors(_htmlContent);
      await _render();
    } catch (_) {
      if (mounted) {
        setState(() {
          _htmlContent = 'Impossible de lire le fichier HTML';
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: _jsEnabled
                ? 'JS activé (clic = désactiver)'
                : 'JS désactivé (clic = activer)',
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
            tooltip: 'Mode lecture (texte désencombré)',
            icon: const Icon(Icons.menu_book_outlined),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) =>
                    ReaderViewerScreen(path: widget.path, isEpub: false),
              ),
            ),
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
                    child: SelectableText(
                      _htmlContent,
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 12,
                      ),
                    ),
                  )
                : Stack(
                    children: [
                      WebViewWidget(controller: _controller),
                      if (_isLoading)
                        const Center(child: CircularProgressIndicator()),
                    ],
                  ),
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
        border: Border(
          bottom: BorderSide(color: Theme.of(context).dividerColor),
        ),
      ),
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _colors.length,
        separatorBuilder: (_, i) => const SizedBox(width: 8),
        itemBuilder: (_, i) {
          final c = _colors[i];
          return GestureDetector(
            onTap: () {
              HapticFeedback.selectionClick(); // v2.13.2 (U4)
              Clipboard.setData(ClipboardData(text: c.code));
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text('Copié : ${c.code}'),
                  duration: const Duration(seconds: 1),
                ),
              );
            },
            child: Row(
              children: [
                Container(
                  width: 22,
                  height: 22,
                  decoration: BoxDecoration(
                    color: c.color,
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(
                      color: Colors.grey.withValues(alpha: 0.4),
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                Text(c.code, style: const TextStyle(fontSize: 11)),
              ],
            ),
          );
        },
      ),
    );
  }
}
