import 'dart:io';
import 'package:image/image.dart' as img;

/// Génère l'icône Read Files Tech : même fond que PDF Tech / Pass Tech
/// (4 quadrants bleu/rouge diagonale + coins arrondis + filet blanc) avec
/// le texte "RFT" centré en blanc.
void main() {
  const size = 1024;
  const half = size ~/ 2;
  const r = 180; // rayon des coins arrondis

  final blue  = img.ColorRgb8(21, 101, 192);   // Material Blue 800
  final red   = img.ColorRgb8(198, 40, 40);    // Material Red 800
  final white = img.ColorRgb8(255, 255, 255);

  final image = img.Image(width: size, height: size);

  // ── 4 quadrants : diagonale bleu/rouge ───────────────────────────────────
  img.fillRect(image, x1: 0,    y1: 0,    x2: half, y2: half, color: blue);
  img.fillRect(image, x1: half, y1: 0,    x2: size, y2: half, color: red);
  img.fillRect(image, x1: 0,    y1: half, x2: half, y2: size, color: red);
  img.fillRect(image, x1: half, y1: half, x2: size, y2: size, color: blue);

  // ── Coins arrondis ───────────────────────────────────────────────────────
  _roundCorners(image, r, size);

  // ── Filet blanc sur les axes de division ─────────────────────────────────
  img.fillRect(image, x1: half - 3, y1: r,        x2: half + 3, y2: size - r, color: white);
  img.fillRect(image, x1: r,        y1: half - 3, x2: size - r, y2: half + 3, color: white);

  // ── Texte "RFT" centré ───────────────────────────────────────────────────
  _drawRFT(image, white, size);

  // ── Sauvegarde ───────────────────────────────────────────────────────────
  Directory('assets/icon').createSync(recursive: true);
  final bytes = img.encodePng(image);
  File('assets/icon/app_icon.png').writeAsBytesSync(bytes);
  // ignore: avoid_print
  print('✓ Icône générée : assets/icon/app_icon.png (${(bytes.length / 1024).toStringAsFixed(1)} Ko)');
}

void _roundCorners(img.Image image, int r, int size) {
  final corners = [
    (cx: r,        cy: r,        x0: 0,        y0: 0),
    (cx: size - r, cy: r,        x0: size - r, y0: 0),
    (cx: r,        cy: size - r, x0: 0,        y0: size - r),
    (cx: size - r, cy: size - r, x0: size - r, y0: size - r),
  ];
  for (final c in corners) {
    for (int dy = 0; dy < r; dy++) {
      for (int dx = 0; dx < r; dx++) {
        final px = c.x0 + dx;
        final py = c.y0 + dy;
        final ddx = px - c.cx;
        final ddy = py - c.cy;
        if (ddx * ddx + ddy * ddy > r * r) {
          final isBlue = (px < 512) == (py < 512);
          image.setPixel(
            px, py,
            isBlue ? img.ColorRgb8(21, 101, 192) : img.ColorRgb8(198, 40, 40),
          );
        }
      }
    }
  }
}

void _drawRFT(img.Image image, img.Color white, int size) {
  const h = 220;   // hauteur des lettres
  const s = 40;    // épaisseur du trait
  const wR = 135;  // largeur du R
  const wF = 115;  // largeur du F
  const wT = 150;  // largeur du T
  const gap = 22;  // espacement

  final totalW = wR + wF + wT + gap * 2;
  final x0 = size ~/ 2 - totalW ~/ 2;
  final y0 = size ~/ 2 - h ~/ 2;

  // ── R ────────────────────────────────────────────────────────────────────
  final rx = x0;
  _r(image, rx,           y0,           rx + s,       y0 + h,       white); // stem
  _r(image, rx + s,       y0,           rx + wR,      y0 + s,       white); // top bar
  _r(image, rx + wR - s,  y0,           rx + wR,      y0 + h ~/ 2, white); // right side haut
  _r(image, rx + s,       y0 + h ~/ 2 - s, rx + wR,   y0 + h ~/ 2, white); // mid bar
  // Diagonale (jambe droite du R) — escaliers de 4px
  for (int i = 0; i < (h - h ~/ 2); i++) {
    final lx = rx + s + ((wR - s) * i ~/ (h - h ~/ 2));
    _r(image, lx, y0 + h ~/ 2 + i, lx + s, y0 + h ~/ 2 + i + 1, white);
  }

  // ── F ────────────────────────────────────────────────────────────────────
  final fx = x0 + wR + gap;
  _r(image, fx,           y0,           fx + s,       y0 + h,       white); // stem
  _r(image, fx + s,       y0,           fx + wF,      y0 + s,       white); // top bar
  _r(image, fx + s,       y0 + h ~/ 2 - s ~/ 2,
           fx + wF - 20,  y0 + h ~/ 2 + s ~/ 2, white); // mid bar

  // ── T ────────────────────────────────────────────────────────────────────
  final tx = x0 + wR + wF + gap * 2;
  _r(image, tx,                   y0,           tx + wT,           y0 + s,       white); // top bar
  _r(image, tx + wT ~/ 2 - s ~/ 2, y0 + s,      tx + wT ~/ 2 + s ~/ 2, y0 + h,    white); // stem
}

void _r(img.Image image, int x1, int y1, int x2, int y2, img.Color color) {
  img.fillRect(image, x1: x1, y1: y1, x2: x2, y2: y2, color: color);
}
