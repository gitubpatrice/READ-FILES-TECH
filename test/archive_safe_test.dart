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
  verifieSignature();

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
    final bytes = Uint8List.fromList(ZipEncoder().encode(archive));

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
    final zip = Uint8List.fromList(ZipEncoder().encode(archive));
    final entry = ZipDecoder().decodeBytes(zip).findFile('ok.txt')!;

    expect(safeEntryBytes(entry, 'ok.txt', 100000), [1, 2, 3, 4, 5]);
  });

  test('une entrée honnête au-dessus du plafond est refusée sans '
      'décompression', () {
    final archive = Archive()
      ..addFile(ArchiveFile('gros.bin', 50000, Uint8List(50000)));
    final zip = Uint8List.fromList(ZipEncoder().encode(archive));
    final entry = ZipDecoder().decodeBytes(zip).findFile('gros.bin')!;

    expect(
      () => safeEntryBytes(entry, 'gros.bin', 1000),
      throwsA(isA<ArchiveTooLargeException>()),
    );
  });

  test('le plafond est exact : une entrée pile à la limite passe', () {
    final archive = Archive()
      ..addFile(ArchiveFile('pile.bin', 1000, Uint8List(1000)));
    final zip = Uint8List.fromList(ZipEncoder().encode(archive));
    final entry = ZipDecoder().decodeBytes(zip).findFile('pile.bin')!;

    expect(safeEntryBytes(entry, 'pile.bin', 1000).length, 1000);
  });

  test('une entrée vide est lue sans erreur', () {
    final archive = Archive()..addFile(ArchiveFile('vide.txt', 0, <int>[]));
    final zip = Uint8List.fromList(ZipEncoder().encode(archive));
    final entry = ZipDecoder().decodeBytes(zip).findFile('vide.txt')!;

    expect(safeEntryBytes(entry, 'vide.txt', 1000), isEmpty);
  });

  // Entrees STORE (non compressees). Elles empruntent l'autre branche de
  // `safeEntryBytes`, celle qui ne passe pas par `Inflate`. La relecture GPT
  // du 2026-08-09 a montre que cette branche copiait TOUT avant que le
  // plafond ne s'exprime : `_CappedOutput` ne verifie qu'apres chaque
  // ecriture, et il n'y en avait qu'une. Elle copie desormais par tranches.
  group('entrees STORE', () {
    Uint8List storeZip({required String name, required int size}) {
      final archive = Archive()
        ..addFile(ArchiveFile.noCompress(name, size, Uint8List(size)));
      return Uint8List.fromList(ZipEncoder().encode(archive));
    }

    test('une entree STORE honnete est lue integralement', () {
      final zip = storeZip(name: 'brut.bin', size: 200 * 1024);
      final entry = ZipDecoder().decodeBytes(zip).findFile('brut.bin')!;
      expect(entry.compression, isNot(CompressionType.deflate));

      final out = safeEntryBytes(entry, 'brut.bin', 1024 * 1024);
      expect(out.length, 200 * 1024);
    });

    test('une entree STORE au-dela du plafond est refusee', () {
      final zip = storeZip(name: 'gros.bin', size: 300 * 1024);
      final entry = ZipDecoder().decodeBytes(zip).findFile('gros.bin')!;
      expect(
        () => safeEntryBytes(entry, 'gros.bin', 100 * 1024),
        throwsA(isA<ArchiveTooLargeException>()),
      );
    });

    test('une entree STORE pile a la limite passe', () {
      final zip = storeZip(name: 'pile.bin', size: 128 * 1024);
      final entry = ZipDecoder().decodeBytes(zip).findFile('pile.bin')!;
      final out = safeEntryBytes(entry, 'pile.bin', 128 * 1024);
      expect(out.length, 128 * 1024);
    });
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Le trou révélé par la migration archive 3 → 4, le 2026-08-10.
  //
  // Les neuf tests ci-dessus vérifient que l'entrée est **refusée**. Aucun ne
  // vérifiait que la mémoire reste **bornée**. La différence n'est pas
  // académique : c'est exactement l'ANR du Galaxy S9 du 2026-08-09, où le
  // refus fonctionnait parfaitement — après avoir alloué 200 Mo.
  //
  // Preuve que le trou était réel : en remplaçant `Inflate.stream` par
  // `rawContent.decompress(out)`, les neuf tests restaient verts.
  //
  // L'observable ne peut pas être la taille de notre propre tampon :
  // l'accumulation a lieu DANS le sink de zlib, et notre tampon s'arrête au
  // plafond dans les deux cas. C'est donc le temps qui tranche — même patron
  // que les budgets temps de `text_extraction_service_test.dart`.
  group('la borne tient, pas seulement le refus', () {
    test('une bombe de 256 Mo est refusée sans être décompressée', () {
      const bombeOctets = 256 << 20;
      // En-tête menteur : sans cela le contrôle bon marché de l'étape 1
      // refuserait l'entrée sur sa taille déclarée, et le chemin réellement
      // testé ici — l'inflate borné — ne serait jamais atteint.
      final zip = forgeZip(
        name: 'bombe.bin',
        realSize: bombeOctets,
        liedSize: 1024,
      );
      final entry = ZipDecoder().decodeBytes(zip).findFile('bombe.bin')!;

      // Le seuil est **auto-étalonné**, et il faut expliquer pourquoi.
      //
      // Un seuil absolu serait fragile : zlib natif décompresse 256 Mo de
      // zéros en ~110 ms ici, contre 0 ms pour la version bornée. L'écart est
      // net sur cette machine, mais 110 ms n'est pas une constante physique.
      //
      // La mémoire aurait été l'observable idéal — c'est la propriété même —
      // mais `ProcessInfo.currentRss` ne reflète pas le tas Dart sous Windows :
      // mesuré à -1 Mo pendant que 256 Mo étaient décompressés.
      //
      // On mesure donc d'abord, sur CETTE machine, ce que coûte une
      // décompression complète ; puis on exige que le refus en coûte une
      // fraction. Plus aucune constante de temps n'est codée en dur.
      final etalon = Stopwatch()..start();
      safeEntryBytes(
        ZipDecoder().decodeBytes(zip).findFile('bombe.bin')!,
        'bombe.bin',
        bombeOctets * 2, // plafond assez haut pour laisser passer
      );
      etalon.stop();

      final borne = Stopwatch()..start();
      expect(
        () => safeEntryBytes(entry, 'bombe.bin', 1 << 20),
        throwsA(isA<ArchiveTooLargeException>()),
      );
      borne.stop();

      expect(
        borne.elapsedMicroseconds * 4,
        lessThan(etalon.elapsedMicroseconds),
        reason:
            'Refuser 1 Mo a pris ${borne.elapsedMicroseconds} µs, alors que '
            'décompresser les ${bombeOctets >> 20} Mo entiers en prend '
            '${etalon.elapsedMicroseconds}. La garde a donc laissé la bombe '
            'se décompresser avant de parler — refus correct, allocation non '
            'bornée : c est l ANR du S9.',
      );
    });
  });
}

// ────────────────────────────────────────────────────────────────────────────
// Constaté le 2026-08-10 en migrant vers `archive` 4.
//
// Jusqu'à la 3.x, `ZipDecoder().decodeBytes` levait sur des octets qui
// n'étaient pas une archive, et les QUATRE sites qui décodent s'appuyaient tous
// sur ce `throw` pour dire « fichier illisible ». La 4.x ne lève plus : elle
// rend une archive VIDE.
//
// Conséquence avant correctif : un `.zip` corrompu s'affichait comme une
// archive parfaitement valide ne contenant rien — l'utilisateur concluait que
// son fichier était vide alors qu'il était illisible. Un `.docx` tronqué
// disait « document.xml introuvable », qui accuse le contenu au lieu du format.
void verifieSignature() {
  group('signature ZIP — archive 4 ne leve plus', () {
    test('archive 4 rend bien une archive VIDE au lieu de lever', () {
      // Le fait qui justifie `looksLikeZip`. S'il cessait d'etre vrai — retour
      // a une exception — ce test le dirait, et la garde deviendrait inutile.
      final a = ZipDecoder().decodeBytes(
        Uint8List.fromList('ceci n est pas une archive'.codeUnits),
      );
      expect(a.files, isEmpty);
    });

    test('du texte brut n est pas reconnu comme ZIP', () {
      expect(looksLikeZip('ceci n est pas une archive'.codeUnits), isFalse);
    });

    test('un tampon vide ou trop court n est pas reconnu', () {
      expect(looksLikeZip(const <int>[]), isFalse);
      expect(looksLikeZip(const [0x50, 0x4B, 0x03]), isFalse);
    });

    test('une signature PK plausible mais fausse est refusee', () {
      expect(looksLikeZip(const [0x50, 0x4B, 0x01, 0x02]), isFalse);
    });

    test('un vrai ZIP est reconnu', () {
      final archive = Archive()
        ..addFile(ArchiveFile('a.txt', 3, Uint8List.fromList([1, 2, 3])));
      expect(looksLikeZip(ZipEncoder().encode(archive)), isTrue);
    });

    test('une archive legitimement VIDE reste reconnue', () {
      // `PK` : fin de repertoire central sans aucune entree. Un ZIP
      // vide est valide, et le confondre avec un fichier corrompu ferait
      // refuser un fichier parfaitement correct.
      final vide = Uint8List(22)
        ..[0] = 0x50
        ..[1] = 0x4B
        ..[2] = 0x05
        ..[3] = 0x06;
      expect(looksLikeZip(vide), isTrue);
      expect(ZipDecoder().decodeBytes(vide).files, isEmpty);
    });
  });
}
