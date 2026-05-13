import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/utils/file_caps.dart';

void main() {
  group('FileCaps constants', () {
    test('caps positifs et exprimés en multiples de Mo', () {
      expect(FileCaps.textViewer, greaterThan(0));
      expect(FileCaps.docZipped, greaterThan(0));
      expect(FileCaps.spreadsheet, greaterThan(0));
      expect(FileCaps.epubFile, greaterThan(FileCaps.epubChapter));
      expect(FileCaps.zipEntryDecompressed, greaterThan(FileCaps.docZipped));
      expect(FileCaps.imagesToPdfTotal, greaterThan(FileCaps.imageFile));
      expect(FileCaps.vaultBackup, greaterThan(0));
    });
  });

  group('checkFileCap', () {
    late Directory tmp;
    setUp(() async {
      tmp = await Directory.systemTemp.createTemp('rft_caps_test_');
    });
    tearDown(() async {
      if (await tmp.exists()) await tmp.delete(recursive: true);
    });

    test('null si fichier sous le cap', () async {
      final f = File('${tmp.path}/small.bin');
      await f.writeAsBytes(List.filled(1024, 0));
      expect(await checkFileCap(f, 10 * 1024), isNull);
    });

    test('message d\'erreur si fichier au-dessus du cap', () async {
      final f = File('${tmp.path}/big.bin');
      await f.writeAsBytes(List.filled(2048, 0));
      final err = await checkFileCap(f, 1024);
      expect(err, isNotNull);
      expect(err, contains('volumineux'));
    });
  });
}
