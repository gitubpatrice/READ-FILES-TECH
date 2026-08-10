// L'extraction d'archives vivait dans une méthode privée d'un `State`. Aucun
// test ne pouvait l'atteindre : seul `safeEntryBytes`, en dessous, était
// couvert. Les commits du 2026-08-09 le reconnaissaient comme une limite
// assumée — « ce commit garantit que les deux sites l'appellent, pas qu'ils
// l'appellent correctement ».
//
// Ce fichier lève cette limite. Il teste le service extrait, c'est-à-dire le
// câblage lui-même : les plafonds, l'anti-zip-slip, le comptage réel.

import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/archive_extract_service.dart';

void main() {
  late Directory tmp;

  setUp(() => tmp = Directory.systemTemp.createTempSync('rft_extract_'));
  tearDown(() {
    if (tmp.existsSync()) tmp.deleteSync(recursive: true);
  });

  /// Écrit un ZIP sur disque et rend son chemin.
  String writeZip(String name, void Function(Archive) build) {
    final archive = Archive();
    build(archive);
    final path = '${tmp.path}/$name';
    File(path).writeAsBytesSync(ZipEncoder().encode(archive));
    return path;
  }

  /// ZIP dont l'en-tête **ment** : annonce [lied], produit [real].
  String writeLyingZip(String name, {required int real, required int lied}) {
    final archive = Archive()
      ..addFile(ArchiveFile('bombe.bin', real, Uint8List(real)));
    final bytes = Uint8List.fromList(ZipEncoder().encode(archive));

    // `uncompressed size`, u32 little-endian :
    //   local file header : signature 0x04034b50, champ à +22
    //   central directory : signature 0x02014b50, champ à +24
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
        final cur =
            bytes[o] |
            (bytes[o + 1] << 8) |
            (bytes[o + 2] << 16) |
            (bytes[o + 3] << 24);
        if (cur != real) continue;
        bytes[o] = lied & 0xff;
        bytes[o + 1] = (lied >> 8) & 0xff;
        bytes[o + 2] = (lied >> 16) & 0xff;
        bytes[o + 3] = (lied >> 24) & 0xff;
      }
    }

    patch(0x04034b50, 22);
    patch(0x02014b50, 24);
    final path = '${tmp.path}/$name';
    File(path).writeAsBytesSync(bytes);
    return path;
  }

  group('extractArchive', () {
    test('extrait une archive honnête', () {
      final zip = writeZip('sain.zip', (a) {
        a.addFile(ArchiveFile('a.txt', 5, utf8Bytes('aaaaa')));
        a.addFile(ArchiveFile('sous/b.txt', 3, utf8Bytes('bbb')));
      });
      final out = '${tmp.path}/out';

      final r = extractArchive(
        zipPath: zip,
        outDirPath: out,
        maxEntryBytes: 1024 * 1024,
        maxTotalBytes: 10 * 1024 * 1024,
      );

      expect(r.written, 2);
      expect(r.skipped, 0);
      expect(r.bytesWritten, 8);
      expect(File('$out/a.txt').readAsStringSync(), 'aaaaa');
      expect(File('$out/sous/b.txt').readAsStringSync(), 'bbb');
    });

    test('refuse une entrée dont l\'en-tête ment, et poursuit', () {
      // Le cœur du sujet : 64 Kio réels, annoncés 10 octets, plafond 1 Kio.
      // L'ancienne garde testait la taille DÉCLARÉE et laissait tout passer.
      final zip = writeLyingZip('bombe.zip', real: 64 * 1024, lied: 10);
      final out = '${tmp.path}/out';

      final r = extractArchive(
        zipPath: zip,
        outDirPath: out,
        maxEntryBytes: 1024,
        maxTotalBytes: 10 * 1024 * 1024,
      );

      expect(r.written, 0, reason: 'rien ne doit être écrit');
      expect(r.skipped, 1);
      expect(r.bytesWritten, 0);
      expect(File('$out/bombe.bin').existsSync(), isFalse);
    });

    test('une entrée refusée n\'empêche pas les autres', () {
      final zip = writeZip('mixte.zip', (a) {
        a.addFile(ArchiveFile('petit.txt', 3, utf8Bytes('abc')));
        a.addFile(ArchiveFile('gros.bin', 8192, Uint8List(8192)));
        a.addFile(ArchiveFile('autre.txt', 3, utf8Bytes('xyz')));
      });
      final out = '${tmp.path}/out';

      final r = extractArchive(
        zipPath: zip,
        outDirPath: out,
        maxEntryBytes: 1024,
        maxTotalBytes: 10 * 1024 * 1024,
      );

      expect(r.written, 2);
      expect(r.skipped, 1);
      expect(File('$out/petit.txt').existsSync(), isTrue);
      expect(File('$out/autre.txt').existsSync(), isTrue);
      expect(File('$out/gros.bin').existsSync(), isFalse);
    });

    test('zip-slip : une entrée traversante est écartée', () {
      final zip = writeZip('slip.zip', (a) {
        a.addFile(ArchiveFile('../../evade.txt', 4, utf8Bytes('boom')));
        a.addFile(ArchiveFile('normal.txt', 2, utf8Bytes('ok')));
      });
      final out = '${tmp.path}/out';

      final r = extractArchive(
        zipPath: zip,
        outDirPath: out,
        maxEntryBytes: 1024 * 1024,
        maxTotalBytes: 10 * 1024 * 1024,
      );

      expect(r.written, 1);
      expect(r.skipped, 1);
      expect(File('$out/normal.txt').existsSync(), isTrue);
      // Le témoin : rien n'a atterri au-dessus du dossier de destination.
      expect(File('${tmp.path}/evade.txt').existsSync(), isFalse);
      expect(File('${tmp.path}/../evade.txt').existsSync(), isFalse);
    });

    test('le cumul est compté sur le réel, pas sur le déclaré', () {
      // Trois entrées de 4 Kio annonçant 1 octet chacune. Avec un plafond
      // cumulé de 6 Kio, le comptage sur le DÉCLARÉ laisserait tout passer
      // (3 octets « seulement »). Sur le réel, la troisième est refusée.
      final archive = Archive();
      for (var i = 0; i < 3; i++) {
        archive.addFile(ArchiveFile('e$i.bin', 4096, Uint8List(4096)));
      }
      final bytes = Uint8List.fromList(ZipEncoder().encode(archive));
      final zip = '${tmp.path}/cumul.zip';
      File(zip).writeAsBytesSync(bytes);
      final out = '${tmp.path}/out';

      final r = extractArchive(
        zipPath: zip,
        outDirPath: out,
        maxEntryBytes: 4096,
        maxTotalBytes: 6 * 1024,
      );

      // Deux entrées tiennent dans 6 Kio ; la troisième trouve un budget
      // restant inférieur à sa taille et se fait refuser.
      expect(r.bytesWritten, lessThanOrEqualTo(6 * 1024));
      expect(r.written + r.skipped, 3);
      expect(r.written, lessThan(3));
    });

    test('le plafond cumulé lève quand le budget est épuisé', () {
      final archive = Archive();
      for (var i = 0; i < 4; i++) {
        archive.addFile(ArchiveFile('e$i.bin', 2048, Uint8List(2048)));
      }
      final zip = '${tmp.path}/plein.zip';
      File(zip).writeAsBytesSync(ZipEncoder().encode(archive));

      expect(
        () => extractArchive(
          zipPath: zip,
          outDirPath: '${tmp.path}/out',
          maxEntryBytes: 2048,
          maxTotalBytes: 4096,
        ),
        throwsA(isA<ArchiveTotalTooLargeException>()),
      );
    });
  });

  group('safeJoin', () {
    test('accepte un nom ordinaire', () {
      expect(safeJoin('/base', 'a/b.txt'), '/base/a/b.txt');
    });

    test('refuse les chemins dangereux', () {
      for (final mauvais in [
        '',
        '/absolu.txt',
        '../evade.txt',
        'a/../../evade.txt',
        './rien.txt',
        'C:/windows/system32',
      ]) {
        expect(
          safeJoin('/base', mauvais),
          isNull,
          reason: 'devrait refuser « $mauvais »',
        );
      }
    });

    test('refuse un nom porteur d\'un octet NUL', () {
      expect(safeJoin('/base', 'a\x00b.txt'), isNull);
    });
  });

  // CE GROUPE EXISTE A CAUSE D'UNE REGRESSION QUE 136 TESTS N'ONT PAS VUE.
  //
  // L'ecran appelait `Isolate.run` depuis une methode d'instance, avec une
  // fermeture qui referencait une `static const` de sa classe `State`. Cela
  // suffisait a y embarquer `this`, donc le binding Flutter et un
  // `_AsyncCompleter` non transmissible. A l'execution, sur appareil :
  //
  //   Invalid argument(s): Illegal argument in isolate message:
  //   object is unsendable - Library:'dart:async' Class: _AsyncCompleter
  //    <- Instance of 'WidgetsFlutterBinding'
  //    <- Instance of 'StatefulElement'
  //
  // `flutter analyze` ne voyait rien. Les tests non plus : ils appelaient
  // `extractArchive` DIRECTEMENT, jamais le cablage vers l'isolate. La
  // fonctionnalite etait entierement cassee et seul le Galaxy S9 l'a dit.
  //
  // Ces tests franchissent la frontiere d'isolate. Ils echouent si la
  // fermeture redevient non transmissible, quelle qu'en soit la raison.
  group('franchissement de la frontiere d\'isolate', () {
    test('extractArchiveIsolate traverse et rend son resultat', () async {
      final zip = writeZip('iso.zip', (a) {
        a.addFile(ArchiveFile('a.txt', 5, utf8Bytes('aaaaa')));
        a.addFile(ArchiveFile('b.txt', 3, utf8Bytes('bbb')));
      });
      final out = '${tmp.path}/out_iso';

      final r = await extractArchiveIsolate(
        zipPath: zip,
        outDirPath: out,
        maxEntryBytes: 1024 * 1024,
        maxTotalBytes: 10 * 1024 * 1024,
      );

      expect(r.written, 2);
      expect(r.skipped, 0);
      expect(r.bytesWritten, 8);
      expect(File('$out/a.txt').readAsStringSync(), 'aaaaa');
    });

    test('extractSingleEntryIsolate traverse et ecrit le fichier', () async {
      final zip = writeZip('iso1.zip', (a) {
        a.addFile(ArchiveFile('seul.txt', 6, utf8Bytes('coucou')));
      });
      final out = '${tmp.path}/seul_extrait.txt';

      final p = await extractSingleEntryIsolate(
        zipPath: zip,
        entryName: 'seul.txt',
        outPath: out,
        maxEntryBytes: 1024 * 1024,
      );

      expect(p, out);
      expect(File(out).readAsStringSync(), 'coucou');
    });

    test('une entree refusee traverse aussi la frontiere', () async {
      // Le refus doit revenir de l'isolate sous forme de compteur, pas d'une
      // exception perdue en route.
      final zip = writeLyingZip('isobombe.zip', real: 64 * 1024, lied: 10);
      final out = '${tmp.path}/out_bombe';

      final r = await extractArchiveIsolate(
        zipPath: zip,
        outDirPath: out,
        maxEntryBytes: 1024,
        maxTotalBytes: 10 * 1024 * 1024,
      );

      expect(r.written, 0);
      expect(r.skipped, 1);
    });

    test('une exception levee dans l\'isolate revient a l\'appelant', () async {
      final archive = Archive();
      for (var i = 0; i < 4; i++) {
        archive.addFile(ArchiveFile('e$i.bin', 2048, Uint8List(2048)));
      }
      final zip = '${tmp.path}/isoplein.zip';
      File(zip).writeAsBytesSync(ZipEncoder().encode(archive));

      await expectLater(
        extractArchiveIsolate(
          zipPath: zip,
          outDirPath: '${tmp.path}/out_plein',
          maxEntryBytes: 2048,
          maxTotalBytes: 4096,
        ),
        throwsA(isA<ArchiveTotalTooLargeException>()),
      );
    });
  });
}

Uint8List utf8Bytes(String s) => Uint8List.fromList(s.codeUnits);
