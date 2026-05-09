import 'dart:typed_data';

/// Garde-fou anti "image-bomb" : décodeur PNG/JPEG/GIF qui inspecte UNIQUEMENT
/// les en-têtes pour rejeter des dimensions absurdes (PNG IHDR affirmant
/// 50000×50000 → 10 Go heap au decode).
///
/// N'effectue aucun décodage pixel. Retourne `null` si dimensions raisonnables
/// ou si format non reconnu (laisse passer — d'autres caps en aval).
abstract final class ImageBounds {
  ImageBounds._();

  /// Bornes maximum considérées comme légitimes (~144 Mpx) ; au-delà =
  /// rejet. Couvre largement les capteurs > 100 Mpx.
  static const int maxWidth = 12000;
  static const int maxHeight = 12000;

  /// Inspecte les premiers octets pour extraire (width, height) si format
  /// reconnu. Retourne `null` sinon.
  static (int, int)? probeDimensions(Uint8List bytes) {
    if (bytes.length < 24) return null;
    // PNG : 0x89 'P' 'N' 'G' 0x0D 0x0A 0x1A 0x0A
    if (bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      // IHDR width @ offset 16, height @ offset 20 (big-endian uint32)
      final w = _u32be(bytes, 16);
      final h = _u32be(bytes, 20);
      return (w, h);
    }
    // GIF : 'GIF87a' / 'GIF89a' — width@6 height@8 little-endian uint16
    if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46) {
      final w = bytes[6] | (bytes[7] << 8);
      final h = bytes[8] | (bytes[9] << 8);
      return (w, h);
    }
    // JPEG : 0xFF 0xD8 ... scan SOF0/SOF2 marker
    if (bytes[0] == 0xFF && bytes[1] == 0xD8) {
      var i = 2;
      while (i + 8 < bytes.length) {
        if (bytes[i] != 0xFF) {
          i++;
          continue;
        }
        final marker = bytes[i + 1];
        // SOF markers (excluding DHT/DAC) : 0xC0-0xCF except 0xC4/0xC8/0xCC
        if (marker >= 0xC0 &&
            marker <= 0xCF &&
            marker != 0xC4 &&
            marker != 0xC8 &&
            marker != 0xCC) {
          // [marker FF Cx][len 2][precision 1][height 2][width 2]
          if (i + 9 >= bytes.length) return null;
          final h = (bytes[i + 5] << 8) | bytes[i + 6];
          final w = (bytes[i + 7] << 8) | bytes[i + 8];
          return (w, h);
        }
        // skip segment : len at i+2..i+3
        if (marker == 0xD8 || marker == 0xD9 || marker == 0x01) {
          i += 2;
          continue;
        }
        if (i + 3 >= bytes.length) return null;
        final segLen = (bytes[i + 2] << 8) | bytes[i + 3];
        if (segLen < 2) return null;
        i += 2 + segLen;
      }
      return null;
    }
    return null;
  }

  /// Renvoie `null` si dimensions OK ou inconnues, sinon message d'erreur.
  static String? assertSafeBounds(Uint8List bytes) {
    final dims = probeDimensions(bytes);
    if (dims == null) return null;
    final (w, h) = dims;
    if (w <= 0 || h <= 0 || w > maxWidth || h > maxHeight) {
      return 'Dimensions image suspectes ($w×$h, max $maxWidth×$maxHeight).';
    }
    return null;
  }

  static int _u32be(Uint8List b, int o) =>
      (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3];
}
