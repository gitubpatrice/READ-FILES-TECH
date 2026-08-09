import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/utils/archive_safe.dart';

/// La garde anti-zip-bomb du projet reposait depuis v2.12.0 sur `entry.size`.
/// Or cette valeur est lue telle quelle dans l'en-tête du ZIP
/// (`zip_file_header.dart:34`, `input.readUint32()`) : elle est choisie par
/// celui qui fabrique le fichier, pas mesurée.
///
/// Ces tests **forgent** une archive dont l'en-tête ment, puis vérifient que la
/// lecture est refusée quand même. Un test qui se contenterait d'une archive
/// honnête resterait vert avec l'ancienne garde — c'est exactement ce qui s'est
/// passé pendant trois versions.
void main() {
  /// Construit un ZIP contenant une entrée de [realSize] octets compressibles,
  /// puis **réécrit** la taille décompressée annoncée dans les deux en-têtes
  /// (local file header et central directory) pour qu'elle vaille [liedSize].
  Uint8List forgeZip({
    required String name,
    required int realSize,
    required int liedSize,
  }) {
    final archive = Archive()
      ..addFile(
        ArchiveFile(name, realSize, Uint8List(realSize)), // que des zéros
      );
    final bytes = Uint8List.fromList(ZipEncoder().encode(archive)!);

    // Le champ `uncompressed size` est un u32 little-endian :
    //  - local file header  : signature 0x04034b50, offset +22
    //  - central directory  : signature 0x02014b50, offset +24
    void patch(int signature, int fieldOffset) {
      for (var i = 0; i + 4 <= bytes.length; i++) {
        final sig =
            bytes[i] |
            (bytes[i + 1] << 8) |
            (bytes[i + 2] << 16) |
            (bytes[i + 3] << 24);
        if (sig != signature) continue;
        final o = i + fieldOffset;
        if (o + 4 > bytes.length) continue;
        // Ne réécrire que si la valeur en place est bien la vraie taille :
        // évite de patcher un octet qui ressemblerait à une signature.
        final cur =
            bytes[o] |
            (bytes[o + 1] << 8) |
            (bytes[o + 2] << 16) |
            (bytes[o + 3] << 24);
        if (cur != realSize) continue;
        bytes[o] = liedSize & 0xff;
        bytes[o + 1] = (liedSize >> 8) & 0xff;
        bytes[o + 2] = (liedSize >> 16) & 0xff;
        bytes[o + 3] = (liedSize >> 24) & 0xff;
      }
    }

    patch(0x04034b50, 22);
    patch(0x02014b50, 24);
    return bytes;
  }

  test('le ZIP forgé ment bien : entry.size ne vaut plus la taille réelle', () {
    // Sans ce contrôle, les tests suivants pourraient passer pour une raison
    // qui n'a rien à voir avec la garde.
    final zip = forgeZip(name: 'gros.bin', realSize: 300000, liedSize: 0);
    final entry = ZipDecoder().decodeBytes(zip).findFile('gros.bin')!;
    expect(
      entry.size,
      0,
      reason: 'l\'en-tête doit annoncer 0 alors que le contenu fait 300 000 o',
    );
    expect(
      (entry.content as List<int>).length,
      300000,
      reason: 'et le contenu réel doit bien dépasser ce qui est annoncé',
    );
  });

  test('une entrée dont l\'en-tête ment est refusée', () {
    final zip = forgeZip(name: 'bombe.xml', realSize: 300000, liedSize: 0);
    final entry = ZipDecoder().decodeBytes(zip).findFile('bombe.xml')!;

    // L'ancienne garde — `if (entry.size > max)` — laissait passer, puisque
    // `0 > 100000` est faux. La nouvelle compte les octets produits.
    expect(
      () => safeEntryBytes(entry, 'bombe.xml', 100000),
      throwsA(isA<ArchiveTooLargeException>()),
    );
  });

  test('une entrée honnête sous le plafond est lue normalement', () {
    final archive = Archive()
      ..addFile(ArchiveFile('ok.txt', 5, Uint8List.fromList([1, 2, 3, 4, 5])));
    final zip = Uint8List.fromList(ZipEncoder().encode(archive)!);
    final entry = ZipDecoder().decodeBytes(zip).findFile('ok.txt')!;

    expect(safeEntryBytes(entry, 'ok.txt', 100000), [1, 2, 3, 4, 5]);
  });

  test('une entrée honnête au-dessus du plafond est refusée sans '
      'décompression', () {
    final archive = Archive()
      ..addFile(ArchiveFile('gros.bin', 50000, Uint8List(50000)));
    final zip = Uint8List.fromList(ZipEncoder().encode(archive)!);
    final entry = ZipDecoder().decodeBytes(zip).findFile('gros.bin')!;

    expect(
      () => safeEntryBytes(entry, 'gros.bin', 1000),
      throwsA(isA<ArchiveTooLargeException>()),
    );
  });

  test('le plafond est exact : une entrée pile à la limite passe', () {
    final archive = Archive()
      ..addFile(ArchiveFile('pile.bin', 1000, Uint8List(1000)));
    final zip = Uint8List.fromList(ZipEncoder().encode(archive)!);
    final entry = ZipDecoder().decodeBytes(zip).findFile('pile.bin')!;

    expect(safeEntryBytes(entry, 'pile.bin', 1000).length, 1000);
  });

  test('une entrée vide est lue sans erreur', () {
    final archive = Archive()..addFile(ArchiveFile('vide.txt', 0, <int>[]));
    final zip = Uint8List.fromList(ZipEncoder().encode(archive)!);
    final entry = ZipDecoder().decodeBytes(zip).findFile('vide.txt')!;

    expect(safeEntryBytes(entry, 'vide.txt', 1000), isEmpty);
  });
}
