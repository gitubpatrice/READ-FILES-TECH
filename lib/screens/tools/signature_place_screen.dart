import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import 'package:syncfusion_flutter_pdfviewer/pdfviewer.dart';
import '../../services/pdf_signature_service.dart';

/// Permet de poser une signature [pngBytes] sur un PDF source [pdfPath].
/// L'utilisateur navigue dans le PDF (SfPdfViewer), choisit la page courante,
/// puis ajuste la position et la taille de la signature avec deux poignées :
/// déplacement (drag) et redimensionnement (corner bottom-right).
class SignaturePlaceScreen extends StatefulWidget {
  final String pdfPath;
  final Uint8List pngBytes;
  const SignaturePlaceScreen({
    super.key,
    required this.pdfPath,
    required this.pngBytes,
  });

  @override
  State<SignaturePlaceScreen> createState() => _SignaturePlaceScreenState();
}

class _SignaturePlaceScreenState extends State<SignaturePlaceScreen> {
  final _viewerKey = GlobalKey<SfPdfViewerState>();
  final _ctrl = PdfViewerController();
  int _currentPage = 1;
  int _totalPages = 0;

  /// Position et taille de la signature en coordonnées **normalisées** par
  /// rapport au container du viewer (0..1). On utilise le viewport visible
  /// comme proxy du recto de la page courante (le viewer nous le présente
  /// plein écran). À la sauvegarde on convertira en coords PDF.
  double _x = 0.55;
  double _y = 0.85;
  double _width = 0.30;
  double _height = 0.10;

  bool _saving = false;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _save(BoxConstraints viewerBox) async {
    setState(() => _saving = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final out = await PdfSignatureService().sign(
        sourcePath: widget.pdfPath,
        pageIndex: _currentPage - 1,
        rectNorm: Rect.fromLTWH(_x, _y, _width, _height),
        signaturePng: widget.pngBytes,
      );
      if (!mounted) return;
      setState(() => _saving = false);
      messenger.showSnackBar(SnackBar(
        content: Text('Signé : ${out.path.split(RegExp(r'[/\\]')).last}'),
      ));
      await Share.shareXFiles([XFile(out.path)]);
      if (!mounted) return;
      Navigator.pop(context, out.path);
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Placer la signature'),
        actions: [
          if (_totalPages > 0)
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text('Page $_currentPage / $_totalPages',
                    style: const TextStyle(fontSize: 13)),
              ),
            ),
        ],
      ),
      body: LayoutBuilder(
        builder: (context, constraints) {
          return Stack(children: [
            // PDF underlay
            SfPdfViewer.file(
              File(widget.pdfPath),
              key: _viewerKey,
              controller: _ctrl,
              onDocumentLoaded: (d) =>
                  setState(() => _totalPages = d.document.pages.count),
              onPageChanged: (d) =>
                  setState(() => _currentPage = d.newPageNumber),
            ),
            // Signature overlay : Positioned + drag/resize
            Positioned(
              left:   _x * constraints.maxWidth,
              top:    _y * constraints.maxHeight,
              width:  _width  * constraints.maxWidth,
              height: _height * constraints.maxHeight,
              child: GestureDetector(
                onPanUpdate: (d) {
                  setState(() {
                    _x = (_x + d.delta.dx / constraints.maxWidth)
                        .clamp(0.0, 1.0 - _width);
                    _y = (_y + d.delta.dy / constraints.maxHeight)
                        .clamp(0.0, 1.0 - _height);
                  });
                },
                child: Stack(children: [
                  // PNG transparent : fond blanc semi-transparent pour visibilité
                  Container(
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.5),
                      border: Border.all(
                        color: Theme.of(context).colorScheme.primary,
                        width: 2,
                      ),
                    ),
                    child: Image.memory(widget.pngBytes, fit: BoxFit.contain),
                  ),
                  // Poignée resize (corner bottom-right)
                  Positioned(
                    right: 0, bottom: 0,
                    child: GestureDetector(
                      onPanUpdate: (d) {
                        setState(() {
                          _width = (_width + d.delta.dx / constraints.maxWidth)
                              .clamp(0.05, 1.0 - _x);
                          _height = (_height + d.delta.dy / constraints.maxHeight)
                              .clamp(0.03, 1.0 - _y);
                        });
                      },
                      child: Container(
                        width: 24, height: 24,
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.primary,
                          borderRadius: const BorderRadius.only(
                              topLeft: Radius.circular(8)),
                        ),
                        child: const Icon(Icons.open_in_full,
                            color: Colors.white, size: 14),
                      ),
                    ),
                  ),
                ]),
              ),
            ),
            if (_saving)
              Container(
                color: Colors.black54,
                child: const Center(child: CircularProgressIndicator()),
              ),
          ]);
        },
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 6, 12, 8),
          child: Row(children: [
            IconButton(
              icon: const Icon(Icons.chevron_left),
              tooltip: 'Page précédente',
              onPressed: _currentPage > 1 ? _ctrl.previousPage : null,
            ),
            IconButton(
              icon: const Icon(Icons.chevron_right),
              tooltip: 'Page suivante',
              onPressed: _currentPage < _totalPages ? _ctrl.nextPage : null,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: LayoutBuilder(
                builder: (ctx, _) => FilledButton.icon(
                  onPressed: _saving ? null : () => _save(BoxConstraints.tight(MediaQuery.of(ctx).size)),
                  icon: const Icon(Icons.save_outlined),
                  label: Text(_saving ? 'Sauvegarde…' : 'Apposer'),
                ),
              ),
            ),
          ]),
        ),
      ),
    );
  }
}
