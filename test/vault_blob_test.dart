import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/vault/vault_blob.dart';
import 'package:read_files_tech/services/vault/vault_bytes.dart';

/// Le format `.enc` du coffre, et surtout ses deux protections :
///
///  - **l'AAD liée au nom de fichier** : un `.enc` renommé ne doit plus se
///    déchiffrer, sans quoi quelqu'un ayant accès en écriture au dossier
///    pourrait faire passer un fichier pour un autre ;
///  - **le refus du repli v1** sur un coffre marqué « v2 uniquement », qui
///    empêche de substituer à un blob v2 un blob v1 forgé — lequel
///    contournerait l'AAD, le format v1 n'en ayant pas.
///
/// Aucune des deux n'était couverte par un test direct avant le 2026-08-10 :
/// elles vivaient dans des méthodes privées lisant un champ statique, donc
/// inatteignables sans monter un coffre complet.
void main() {
  final key = Uint8List.fromList(List.generate(32, (i) => i));
  final nonce = Uint8List.fromList(List.generate(kVaultNonceLen, (i) => i * 3));
  final plain = utf8.encode('contenu secret du coffre');

  Uint8List blobV2(String filename) =>
      encryptV2(plain: plain, key: key, nonce: nonce, filename: filename);

  group('format v2', () {
    test('aller-retour : ce qui est chiffré se relit', () {
      final blob = blobV2('releve.pdf');
      final out = decryptAuto(
        blob: blob,
        key: key,
        filename: 'releve.pdf',
        v2Only: true,
      );
      expect(out, plain);
    });

    test('le blob porte bien le magic RFT2, puis le nonce', () {
      final blob = blobV2('x.txt');
      expect(startsWithMagic(blob, kVaultMagicV2), isTrue);
      expect(utf8.decode(blob.sublist(0, 4)), 'RFT2');
      expect(blob.sublist(4, 4 + kVaultNonceLen), nonce);
    });

    test('renommer le fichier invalide le déchiffrement', () {
      // LA protection de l'AAD. Sans elle, quelqu'un ayant accès en écriture au
      // dossier du coffre pourrait renommer un `.enc` pour le faire passer pour
      // un autre : le déchiffrement réussirait et rendrait le mauvais contenu
      // sous le bon nom.
      final blob = blobV2('releve_bancaire.pdf');
      expect(
        () => decryptAuto(
          blob: blob,
          key: key,
          filename: 'photo_vacances.jpg',
          v2Only: true,
        ),
        throwsA(anything),
      );
    });

    test('une mauvaise clé est refusée', () {
      final blob = blobV2('x.txt');
      final autre = Uint8List(32)..[0] = 0xFF;
      expect(
        () => decryptAuto(
          blob: blob,
          key: autre,
          filename: 'x.txt',
          v2Only: true,
        ),
        throwsA(anything),
      );
    });

    test('un octet modifié dans le chiffré est détecté', () {
      final blob = blobV2('x.txt');
      blob[blob.length - 5] ^= 0x01;
      expect(
        () =>
            decryptAuto(blob: blob, key: key, filename: 'x.txt', v2Only: true),
        throwsA(anything),
      );
    });
  });

  group('refus du repli v1 — protection anti-substitution', () {
    /// Blob v1 : `nonce | ciphertext+tag`, sans magic et sans AAD.
    Uint8List blobV1() {
      final ct = gcmProcess(
        forEncryption: true,
        input: Uint8List.fromList(plain),
        key: key,
        nonce: nonce,
        aad: Uint8List(0),
      );
      return (BytesBuilder()
            ..add(nonce)
            ..add(ct))
          .toBytes();
    }

    test('un coffre v2-only REFUSE un blob v1, même parfaitement valide', () {
      // Le point entier de la protection : le blob ci-dessous se déchiffre
      // sans erreur quand le repli est autorisé. C'est bien le refus qui est
      // testé, pas un blob cassé.
      expect(
        () => decryptAuto(
          blob: blobV1(),
          key: key,
          filename: 'x.txt',
          v2Only: true,
        ),
        throwsA(isA<VaultBlobException>()),
      );
    });

    test('le même blob v1 est accepté par un coffre legacy', () {
      // Sans ce contrôle, le test précédent pourrait passer parce que le blob
      // est invalide plutôt que parce qu'il est refusé.
      final out = decryptAuto(
        blob: blobV1(),
        key: key,
        filename: 'x.txt',
        v2Only: false,
      );
      expect(out, plain);
    });

    test('un blob v1 ignore le nom de fichier — d\'où le besoin du v2', () {
      // La démonstration de ce que le format v1 ne protège pas : n'importe quel
      // nom déchiffre. C'est la raison d'être de l'AAD v2, et donc du refus.
      expect(
        decryptAuto(
          blob: blobV1(),
          key: key,
          filename: 'un-tout-autre-nom.bin',
          v2Only: false,
        ),
        plain,
      );
    });
  });

  group('blobs malformés', () {
    test('un blob trop court est refusé, pas interprété', () {
      for (final n in [0, 1, 11, kVaultNonceLen + kVaultTagLen - 1]) {
        expect(
          () => decryptAuto(
            blob: Uint8List(n),
            key: key,
            filename: 'x',
            v2Only: false,
          ),
          throwsA(isA<VaultBlobException>()),
          reason: 'longueur $n',
        );
      }
    });

    test('un magic tronqué ne passe pas pour du v2', () {
      final presque = Uint8List.fromList([
        0x52, 0x46, 0x54, 0x33, // RFT3 : un octet diffère
        ...List.filled(kVaultNonceLen + kVaultTagLen, 0),
      ]);
      expect(startsWithMagic(presque, kVaultMagicV2), isFalse);
      expect(
        () => decryptAuto(blob: presque, key: key, filename: 'x', v2Only: true),
        throwsA(isA<VaultBlobException>()),
      );
    });
  });

  group('helpers big-endian', () {
    test('aller-retour sur 32 et 16 bits', () {
      for (final v in [0, 1, 255, 256, 65535, 1 << 24, 0x7FFFFFFF]) {
        expect(readU32be(u32be(v), 0), v, reason: '32 bits, $v');
      }
      for (final v in [0, 1, 255, 256, 65535]) {
        expect(readU16be(u16be(v), 0), v, reason: '16 bits, $v');
      }
    });

    test('startsWithMagic refuse un tampon plus court que le magic', () {
      expect(startsWithMagic(Uint8List(2), kVaultMagicV2), isFalse);
    });
  });
}
