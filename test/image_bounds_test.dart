import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/utils/image_bounds.dart';

/// Tests ImageBounds (F5 v2.12.0) — anti image-bomb : refuse dimensions
/// absurdes annoncées par les headers PNG/JPEG/GIF avant `decodeImage`.
void main() {
  group('ImageBounds.probeDimensions', () {
    test('PNG IHDR — extrait width/height big-endian', () {
      // PNG signature + IHDR avec width=1024 height=768 (en big-endian).
      final bytes = Uint8List(28);
      // PNG magic
      bytes.setAll(0, [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
      // IHDR chunk length (13) + tag "IHDR" — bytes 8..15
      bytes.setAll(8, [0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52]);
      // width @ 16..19 = 1024 (0x00000400)
      bytes.setAll(16, [0x00, 0x00, 0x04, 0x00]);
      // height @ 20..23 = 768 (0x00000300)
      bytes.setAll(20, [0x00, 0x00, 0x03, 0x00]);
      final dims = ImageBounds.probeDimensions(bytes);
      expect(dims, isNotNull);
      expect(dims!.$1, 1024);
      expect(dims.$2, 768);
    });

    test('GIF LSD — extrait width/height little-endian', () {
      final bytes = Uint8List(24);
      bytes.setAll(0, [0x47, 0x49, 0x46, 0x38, 0x39, 0x61]); // GIF89a
      bytes.setAll(6, [0x00, 0x04, 0x00, 0x03]); // 1024×768 LE
      final dims = ImageBounds.probeDimensions(bytes);
      expect(dims, isNotNull);
      expect(dims!.$1, 1024);
      expect(dims.$2, 768);
    });

    test('format inconnu retourne null', () {
      expect(ImageBounds.probeDimensions(Uint8List(100)), isNull);
    });

    test('bytes trop courts retournent null', () {
      expect(ImageBounds.probeDimensions(Uint8List(10)), isNull);
    });
  });

  group('ImageBounds.assertSafeBounds', () {
    test('PNG 50000x50000 (image-bomb) rejeté', () {
      final bytes = Uint8List(28);
      bytes.setAll(0, [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
      // width 50000 = 0xC350
      bytes.setAll(16, [0x00, 0x00, 0xC3, 0x50]);
      bytes.setAll(20, [0x00, 0x00, 0xC3, 0x50]);
      final err = ImageBounds.assertSafeBounds(bytes);
      expect(err, isNotNull);
      expect(err, contains('Dimensions image suspectes'));
    });

    test('PNG 1024x768 (bénin) accepté', () {
      final bytes = Uint8List(28);
      bytes.setAll(0, [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
      bytes.setAll(16, [0x00, 0x00, 0x04, 0x00]);
      bytes.setAll(20, [0x00, 0x00, 0x03, 0x00]);
      expect(ImageBounds.assertSafeBounds(bytes), isNull);
    });

    test('format inconnu = laisse passer (autres caps en aval)', () {
      expect(ImageBounds.assertSafeBounds(Uint8List(100)), isNull);
    });
  });
  group('trous de couverture combles le 2026-08-11', () {
    Uint8List gif(int w, int h, {int taille = 10}) {
      final b = Uint8List(10);
      b[0] = 0x47; // G
      b[1] = 0x49; // I
      b[2] = 0x46; // F
      b[3] = 0x38;
      b[4] = 0x39;
      b[5] = 0x61; // 9a
      b[6] = w & 0xFF;
      b[7] = (w >> 8) & 0xFF;
      b[8] = h & 0xFF;
      b[9] = (h >> 8) & 0xFF;
      // `taille` permet de tronquer APRES ecriture, pour simuler un fichier
      // incomplet sans sortir des bornes du tampon en le construisant.
      return taille >= 10 ? b : Uint8List.sublistView(b, 0, taille);
    }

    test('un GIF de 10 octets annoncant 65535x65535 est REFUSE', () {
      // LE trou. Le seuil `bytes.length < 24` etait global, alors qu un GIF
      // declare ses dimensions des l octet 10 : un fichier de 10 octets
      // annoncant 4,3 gigapixels n etait jamais inspecte. Le seuil cense
      // proteger servait a contourner la protection.
      expect(ImageBounds.probeDimensions(gif(65535, 65535)), (65535, 65535));
      expect(ImageBounds.assertSafeBounds(gif(65535, 65535)), isNotNull);
    });

    test('un GIF de 10 octets aux dimensions normales passe', () {
      expect(ImageBounds.assertSafeBounds(gif(800, 600)), isNull);
    });

    test('un GIF tronque a 9 octets ne peut pas etre lu, donc pas juge', () {
      // En dessous de 10 octets les dimensions ne sont pas encore ecrites :
      // les inventer serait pire que de laisser passer.
      expect(ImageBounds.probeDimensions(gif(1, 1, taille: 9)), isNull);
    });

    Uint8List bmp(int w, int h) {
      final b = Uint8List(26);
      b[0] = 0x42; // B
      b[1] = 0x4D; // M
      void i32(int off, int v) {
        b[off] = v & 0xFF;
        b[off + 1] = (v >> 8) & 0xFF;
        b[off + 2] = (v >> 16) & 0xFF;
        b[off + 3] = (v >> 24) & 0xFF;
      }

      i32(18, w);
      i32(22, h);
      return b;
    }

    test('un BMP aux dimensions absurdes est refuse', () {
      // `img.decodeImage` sait lire le BMP, mais aucune borne ne l inspectait :
      // la garde se contournait en changeant de format.
      expect(ImageBounds.assertSafeBounds(bmp(50000, 50000)), isNotNull);
    });

    test('un BMP normal passe, hauteur negative comprise', () {
      expect(ImageBounds.assertSafeBounds(bmp(1024, 768)), isNull);
      // Une hauteur negative indique l ordre des lignes, pas une anomalie.
      expect(ImageBounds.assertSafeBounds(bmp(1024, -768)), isNull);
    });

    Uint8List webpVp8(int w, int h) {
      final b = Uint8List(30);
      const entete = [0x52, 0x49, 0x46, 0x46];
      for (var i = 0; i < 4; i++) {
        b[i] = entete[i];
      }
      const webp = [0x57, 0x45, 0x42, 0x50];
      for (var i = 0; i < 4; i++) {
        b[8 + i] = webp[i];
      }
      const vp8 = [0x56, 0x50, 0x38, 0x20];
      for (var i = 0; i < 4; i++) {
        b[12 + i] = vp8[i];
      }
      b[26] = w & 0xFF;
      b[27] = (w >> 8) & 0x3F;
      b[28] = h & 0xFF;
      b[29] = (h >> 8) & 0x3F;
      return b;
    }

    test('un WebP aux dimensions absurdes est refuse', () {
      // 16383 est le maximum codable sur 14 bits ; 16383x16383 = 268 Mpx,
      // au-dela du plafond de 144 Mpx.
      expect(ImageBounds.assertSafeBounds(webpVp8(16383, 16383)), isNotNull);
    });

    test('un WebP normal passe', () {
      expect(ImageBounds.assertSafeBounds(webpVp8(1920, 1080)), isNull);
    });
  });
  group('WebP tronque — le seuil qui desarmait la garde', () {
    /// WebP VP8L de [taille] octets declarant [w] x [h].
    ///
    /// VP8L empaquette les deux dimensions sur 14 bits chacune, moins un, a
    /// l offset 21. Vingt-cinq octets suffisent donc a les LIRE, et suffisent
    /// donc a les REFUSER.
    Uint8List webpVp8l({required int taille, required int w, required int h}) {
      final b = Uint8List(taille);
      b.setAll(0, 'RIFF'.codeUnits);
      b.setAll(8, 'WEBP'.codeUnits);
      b.setAll(12, 'VP8L'.codeUnits);
      final bits = ((w - 1) & 0x3FFF) | (((h - 1) & 0x3FFF) << 14);
      if (taille > 21) b[21] = bits & 0xFF;
      if (taille > 22) b[22] = (bits >> 8) & 0xFF;
      if (taille > 23) b[23] = (bits >> 16) & 0xFF;
      if (taille > 24) b[24] = (bits >> 24) & 0xFF;
      return b;
    }

    test('un VP8L de 25 octets aux dimensions maximales est REFUSE', () {
      // Le defaut : la branche WebP exigeait `bytes.length >= 30` alors que le
      // lecteur VP8L n a besoin que de 25. Un fichier de 25 a 29 octets n etait
      // donc JAMAIS inspecte et passait la garde sans le moindre controle.
      //
      // 16384 x 16384 est le maximum encodable en VP8L : 268 megapixels, soit
      // environ un gigaoctet une fois decode en RGBA.
      final piege = webpVp8l(taille: 25, w: 16384, h: 16384);
      expect(
        ImageBounds.probeDimensions(piege),
        isNotNull,
        reason: 'un fichier de 25 octets doit etre inspecte, pas ignore',
      );
      expect(
        ImageBounds.assertSafeBounds(piege),
        isNotNull,
        reason: '16384x16384 depasse le plafond et doit etre refuse',
      );
    });

    test('toutes les tailles de 25 a 30 octets sont couvertes', () {
      // Le trou etait une PLAGE, pas une valeur isolee. Le balayer evite qu un
      // seuil corrige de travers laisse un octet de marge.
      for (var taille = 25; taille <= 30; taille++) {
        final piege = webpVp8l(taille: taille, w: 16384, h: 16384);
        expect(
          ImageBounds.assertSafeBounds(piege),
          isNotNull,
          reason: 'taille $taille : dimensions absurdes non refusees',
        );
      }
    });

    test('un VP8L legitime de petite taille reste accepte', () {
      // Le controle inverse : si la garde refusait TOUT WebP court, le test
      // precedent passerait pour la mauvaise raison.
      final sain = webpVp8l(taille: 25, w: 800, h: 600);
      expect(ImageBounds.probeDimensions(sain), (800, 600));
      expect(ImageBounds.assertSafeBounds(sain), isNull);
    });

    test('un WebP trop court pour porter sa variante est ignore', () {
      // En dessous de 16 octets, l identifiant de variante n est pas lisible :
      // rendre `null` est correct, il n y a rien a affirmer.
      for (var taille = 4; taille < 16; taille++) {
        final court = Uint8List(taille)
          ..setAll(0, 'RIFF'.codeUnits.take(taille));
        expect(ImageBounds.probeDimensions(court), isNull, reason: '$taille');
      }
    });
  });
  group('plafond en PIXELS — le controle par cote ne suffit pas', () {
    Uint8List png(int w, int h) {
      final b = Uint8List(24);
      b.setAll(0, [0x89, 0x50, 0x4E, 0x47]);
      void u32(int off, int v) {
        b[off] = (v >> 24) & 0xFF;
        b[off + 1] = (v >> 16) & 0xFF;
        b[off + 2] = (v >> 8) & 0xFF;
        b[off + 3] = v & 0xFF;
      }

      u32(16, w);
      u32(20, h);
      return b;
    }

    test('12000x12000 franchit les deux bornes de cote et doit etre REFUSE', () {
      // Le defaut : chaque cote valait exactement le maximum autorise, donc
      // aucune borne n etait depassee. Cela fait pourtant 144 megapixels, soit
      // 576 Mo de tampon RGBA — pour un PNG compressible de quelques centaines
      // de kilo-octets, que le cap sur la TAILLE DU FICHIER ne voit pas non
      // plus.
      final piege = png(12000, 12000);
      expect(ImageBounds.probeDimensions(piege), (12000, 12000));
      final refus = ImageBounds.assertSafeBounds(piege);
      expect(refus, isNotNull);
      expect(refus, contains('Mpx'));
    });

    test('une photo 48 Mpx reste acceptee', () {
      // Le controle inverse, sans lequel le test precedent passerait aussi si
      // le plafond etait absurdement bas et rejetait toute vraie photo.
      expect(ImageBounds.assertSafeBounds(png(8000, 6000)), isNull);
    });

    test('la limite est franche, des deux cotes', () {
      const max = 64 * 1000 * 1000;
      expect(ImageBounds.assertSafeBounds(png(8000, max ~/ 8000)), isNull);
      expect(
        ImageBounds.assertSafeBounds(png(8000, (max ~/ 8000) + 1)),
        isNotNull,
      );
    });
  });
}
