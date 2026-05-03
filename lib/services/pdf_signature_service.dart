import 'dart:io';
import 'dart:typed_data';
import 'dart:ui';
import 'package:path_provider/path_provider.dart';
import 'package:syncfusion_flutter_pdf/pdf.dart';

/// Insère une image (signature PNG transparente) dans un PDF à une position
/// donnée, exprimée en coordonnées **normalisées** (0..1) de la page cible.
///
/// Pourquoi normalisées : la pose se fait sur une rasterisation de la page
/// (preview Flutter à une résolution arbitraire). En sortant en 0..1 on évite
/// toute erreur de DPI / scale lors de la conversion en points PDF.
class PdfSignatureService {
  /// Ouvre [sourcePath], colle [signaturePng] sur [pageIndex] (0-based) à la
  /// zone normalisée [rectNorm] (Rect sur 0..1), sauve dans un nouveau PDF
  /// suffixé "_signe.pdf" dans le dossier temporaire et retourne son chemin.
  Future<File> sign({
    required String sourcePath,
    required int pageIndex,
    required Rect rectNorm,
    required Uint8List signaturePng,
  }) async {
    if (rectNorm.width <= 0 || rectNorm.height <= 0) {
      throw ArgumentError('Zone de signature invalide');
    }
    // Clamp dans [0, 1] : évite que Syncfusion reçoive des coords négatives
    // ou hors page si le caller a un bug dans son code de drag/resize.
    if (rectNorm.left < 0 ||
        rectNorm.top < 0 ||
        rectNorm.right > 1 + 1e-6 ||
        rectNorm.bottom > 1 + 1e-6) {
      throw ArgumentError('Zone de signature hors page (doit être dans [0,1])');
    }
    final bytes = await File(sourcePath).readAsBytes();
    final doc = PdfDocument(inputBytes: bytes);
    try {
      if (pageIndex < 0 || pageIndex >= doc.pages.count) {
        throw RangeError('Page inexistante : $pageIndex / ${doc.pages.count}');
      }
      final page = doc.pages[pageIndex];
      final pageSize = page.getClientSize();
      // Conversion normalisé → points PDF
      final dx = rectNorm.left * pageSize.width;
      final dy = rectNorm.top * pageSize.height;
      final dw = rectNorm.width * pageSize.width;
      final dh = rectNorm.height * pageSize.height;
      final bitmap = PdfBitmap(signaturePng);
      page.graphics.drawImage(bitmap, Rect.fromLTWH(dx, dy, dw, dh));

      final tmp = await getTemporaryDirectory();
      final base = sourcePath
          .split(RegExp(r'[/\\]'))
          .last
          .replaceAll(RegExp(r'\.pdf$', caseSensitive: false), '');
      final out = File('${tmp.path}/${base}_signe.pdf');
      await out.writeAsBytes(await doc.save());
      return out;
    } finally {
      doc.dispose();
    }
  }
}
