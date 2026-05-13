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
}
