import 'dart:typed_data';
import 'file_caps.dart';

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
    // Le seuil minimal est PAR FORMAT, et non global.
    //
    // Un `if (bytes.length < 24) return null;` unique ouvrait un trou : un GIF
    // declare ses dimensions des l'octet 10, si bien qu'un fichier de 10 octets
    // annoncant 65535x65535 — soit 4,3 gigapixels — n'etait JAMAIS inspecte et
    // passait la garde sans le moindre controle. Le seuil cense proteger
    // servait a contourner la protection.
    //
    // Signale par la relecture GPT du 2026-08-11, confirme dans le code.
    if (bytes.length < 4) return null;

    // PNG : 0x89 'P' 'N' 'G' — IHDR width @16, height @20 (big-endian uint32)
    if (bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      if (bytes.length < 24) return null;
      final w = _u32be(bytes, 16);
      final h = _u32be(bytes, 20);
      return (w, h);
    }
    // GIF : 'GIF87a' / 'GIF89a' — width@6 height@8 little-endian uint16.
    // Dix octets suffisent a les lire, et suffisent donc a les REFUSER.
    if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46) {
      if (bytes.length < 10) return null;
      final w = bytes[6] | (bytes[7] << 8);
      final h = bytes[8] | (bytes[9] << 8);
      return (w, h);
    }
    // BMP : 'BM' — largeur @18, hauteur @22, entiers 32 bits little-endian
    // signes (une hauteur negative signale un bitmap oriente vers le bas).
    //
    // Ajoute le 2026-08-11 : `img.decodeImage` sait lire le BMP, mais aucune
    // borne ne l'inspectait. Une garde qui ne couvre qu'une partie des formats
    // qu'on accepte se contourne en changeant d'extension.
    if (bytes[0] == 0x42 && bytes[1] == 0x4D) {
      if (bytes.length < 26) return null;
      final w = _i32le(bytes, 18);
      final h = _i32le(bytes, 22);
      return (w, h.abs());
    }
    // WebP : 'RIFF' .... 'WEBP'. Trois variantes de bloc, dimensions codees
    // differemment dans chacune.
    //
    // Seuil a 16 — de quoi lire l'identifiant de variante en b[12..15] — et
    // NON a 30. Chaque variante impose ensuite son propre minimum dans
    // `_probeWebp`, qui sait lire un VP8L des 25 octets.
    //
    // Le seuil a 30 etait le MEME defaut que celui corrige plus haut pour le
    // GIF, laisse en place sur cette branche : un VP8L de 25 a 29 octets
    // n'etait jamais inspecte et passait la garde sans controle. Or ses
    // dimensions tiennent sur 14 bits chacune, soit jusqu'a 16384x16384 —
    // 268 megapixels, environ un gigaoctet en RGBA — tres au-dessus du plafond
    // de 12000. Corrige le 2026-08-11 : il ne suffit pas de reparer le site
    // signale, il faut reparer TOUS ses jumeaux.
    if (bytes.length >= 16 &&
        bytes[0] == 0x52 &&
        bytes[1] == 0x49 &&
        bytes[2] == 0x46 &&
        bytes[3] == 0x46 &&
        bytes[8] == 0x57 &&
        bytes[9] == 0x45 &&
        bytes[10] == 0x42 &&
        bytes[11] == 0x50) {
      final dims = _probeWebp(bytes);
      if (dims != null) return dims;
    }
    // JPEG : 0xFF 0xD8 ... scan SOF0/SOF2 marker
    if (bytes.length >= 24 && bytes[0] == 0xFF && bytes[1] == 0xD8) {
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
    // Le controle par cote ne suffit pas : 12000x12000 franchit les deux
    // bornes sans les depasser, et represente pourtant 144 megapixels — 576 Mo
    // de tampon RGBA — pour un PNG de quelques centaines de kilo-octets. Ni le
    // cap sur la taille du fichier ni celui sur les dimensions ne le voient.
    //
    // Signale par la relecture GPT du 2026-08-11, confirme par le calcul.
    final pixels = w * h;
    if (pixels > FileCaps.imagePixels) {
      final mpx = (pixels / 1000000).round();
      const max = FileCaps.imagePixels ~/ 1000000;
      return 'Image trop lourde a decoder ($w×$h, $mpx Mpx, max $max Mpx).';
    }
    return null;
  }

  /// Dimensions d'un WebP, selon sa variante de bloc.
  static (int, int)? _probeWebp(Uint8List b) {
    // 'VP8 ' : bloc simple. Dimensions sur 14 bits a l'offset 26.
    if (b[12] == 0x56 && b[13] == 0x50 && b[14] == 0x38 && b[15] == 0x20) {
      if (b.length < 30) return null;
      final w = ((b[27] << 8) | b[26]) & 0x3FFF;
      final h = ((b[29] << 8) | b[28]) & 0x3FFF;
      return (w, h);
    }
    // 'VP8L' : sans perte. 14 bits chacune, moins un, empaquetes a l'offset 21.
    if (b[12] == 0x56 && b[13] == 0x50 && b[14] == 0x38 && b[15] == 0x4C) {
      if (b.length < 25) return null;
      final bits = b[21] | (b[22] << 8) | (b[23] << 16) | (b[24] << 24);
      return ((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
    }
    // 'VP8X' : etendu. 24 bits chacune, moins un, a l'offset 24.
    if (b[12] == 0x56 && b[13] == 0x50 && b[14] == 0x38 && b[15] == 0x58) {
      if (b.length < 30) return null;
      final w = (b[24] | (b[25] << 8) | (b[26] << 16)) + 1;
      final h = (b[27] | (b[28] << 8) | (b[29] << 16)) + 1;
      return (w, h);
    }
    return null;
  }

  static int _u32be(Uint8List b, int o) =>
      (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3];

  /// Entier 32 bits little-endian **signe** : le BMP autorise une hauteur
  /// negative, qui indique l'ordre des lignes et non une dimension absurde.
  static int _i32le(Uint8List b, int o) {
    final v = b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24);
    return v >= 0x80000000 ? v - 0x100000000 : v;
  }
}
