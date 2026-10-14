import 'package:flutter/material.dart';

/// Extraction des couleurs hex / rgb() / rgba() d'un texte (CSS, code, MD).
/// Factorisé pour `txt_viewer` + `html_viewer`.
class ColorMatch {
  final String code;
  final Color color;
  const ColorMatch(this.code, this.color);
}

/// Parse hex `#RGB` ou `#RRGGBB`. `null` si invalide.
Color? parseHexColor(String hex) {
  var h = hex.replaceFirst('#', '');
  if (h.length == 3) h = h.split('').map((c) => c + c).join();
  final v = int.tryParse('FF$h', radix: 16);
  return v != null ? Color(v) : null;
}

/// Extrait les couleurs uniques d'un texte. Inclut hex + rgb()/rgba().
List<ColorMatch> extractColors(String text, {bool includeRgb = true}) {
  final results = <ColorMatch>[];
  final seen = <String>{};

  final hexReg = RegExp(r'#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})\b');
  for (final m in hexReg.allMatches(text)) {
    final code = m.group(0)!;
    if (seen.contains(code)) continue;
    seen.add(code);
    final c = parseHexColor(code);
    if (c != null) results.add(ColorMatch(code, c));
  }

  if (includeRgb) {
    final rgbReg = RegExp(r'rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)');
    for (final m in rgbReg.allMatches(text)) {
      final code = '${m.group(0)!})';
      if (seen.contains(code)) continue;
      seen.add(code);
      final r = int.tryParse(m.group(1)!) ?? 0;
      final g = int.tryParse(m.group(2)!) ?? 0;
      final b = int.tryParse(m.group(3)!) ?? 0;
      results.add(ColorMatch(code, Color.fromRGBO(r, g, b, 1)));
    }
  }
  return results;
}
