/// Format binaire d'un fichier chiffré du coffre (`.enc`).
///
/// **v2, depuis v2.5.5** — `magic(4) | nonce(12) | ciphertext+tag`, avec
/// `AAD = "rft-vault-v2|" + nom du fichier`. Le magic vaut `RFT2`.
///
/// **v1, jusqu'à v2.5.4** — `nonce(12) | ciphertext+tag`, AAD vide. Lu en repli
/// pour rétrocompatibilité, et réécrit en v2 au prochain import.
///
/// **Ce que l'AAD protège.** Elle lie le chiffré à son nom de fichier. Sans
/// elle, quelqu'un ayant accès en écriture au dossier du coffre pourrait
/// renommer un `.enc` pour le faire passer pour un autre : le déchiffrement
/// réussirait et rendrait le mauvais contenu sous le bon nom.
///
/// **Pourquoi ce fichier existe.** Ce format vivait dans `vault_service.dart`,
/// dans des méthodes d'instance qui lisaient `_v2OnlyCache` — un champ statique.
/// Le refus du repli v1 sur un coffre marqué « v2 uniquement » est une
/// **protection contre la substitution**, et il n'était couvert par aucun test
/// direct : il fallait un coffre complet pour l'atteindre. Ici, `v2Only` est un
/// paramètre, et la protection devient vérifiable en trois lignes.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:pointycastle/export.dart';

import 'vault_bytes.dart';

/// Magic du format v2 : ASCII `RFT2`.
const kVaultMagicV2 = [0x52, 0x46, 0x54, 0x32];

/// Préfixe de l'AAD v2, suivi du nom de fichier.
const kVaultAadPrefix = 'rft-vault-v2|';

/// Longueur du nonce GCM, en octets.
const kVaultNonceLen = 12;

/// Longueur du tag GCM, en octets.
const kVaultTagLen = 16;

/// Levée quand un blob ne peut pas être interprété — trop court, ou repli v1
/// refusé sur un coffre « v2 uniquement ».
class VaultBlobException implements Exception {
  final String message;
  const VaultBlobException(this.message);

  @override
  String toString() => message;
}

/// AES-GCM brut, sans en-tête. Le cœur commun aux deux formats.
Uint8List gcmProcess({
  required bool forEncryption,
  required Uint8List input,
  required Uint8List key,
  required Uint8List nonce,
  required Uint8List aad,
}) {
  final cipher = GCMBlockCipher(AESEngine())
    ..init(
      forEncryption,
      AEADParameters(KeyParameter(key), kVaultTagLen * 8, nonce, aad),
    );
  return cipher.process(input);
}

/// AAD v2 d'un fichier donné.
Uint8List vaultAad(String filename) =>
    Uint8List.fromList(utf8.encode('$kVaultAadPrefix$filename'));

/// Chiffre [plain] au format v2.
Uint8List encryptV2({
  required List<int> plain,
  required Uint8List key,
  required Uint8List nonce,
  required String filename,
}) {
  final ct = gcmProcess(
    forEncryption: true,
    input: Uint8List.fromList(plain),
    key: key,
    nonce: nonce,
    aad: vaultAad(filename),
  );
  return (BytesBuilder()
        ..add(kVaultMagicV2)
        ..add(nonce)
        ..add(ct))
      .toBytes();
}

/// Déchiffre [blob] en détectant son format.
///
/// Quand [v2Only] est vrai, le repli v1 est **refusé** : c'est la protection
/// contre une substitution d'un `.enc` v2 par un blob v1 forgé, qui
/// contournerait la liaison de l'AAD au nom de fichier. Elle est active sur
/// tout coffre créé en v2.12.0 ou après.
Uint8List decryptAuto({
  required Uint8List blob,
  required Uint8List key,
  required String filename,
  required bool v2Only,
}) {
  const minV2 = 4 + kVaultNonceLen + kVaultTagLen;
  if (blob.length >= minV2 && startsWithMagic(blob, kVaultMagicV2)) {
    // `sublistView` et non `sublist` : vue sans copie. Sur un blob de 5 Mo,
    // cela évite un doublement transitoire de l'empreinte mémoire.
    return gcmProcess(
      forEncryption: false,
      input: Uint8List.sublistView(blob, 4 + kVaultNonceLen),
      key: key,
      nonce: Uint8List.sublistView(blob, 4, 4 + kVaultNonceLen),
      aad: vaultAad(filename),
    );
  }

  if (v2Only) {
    throw const VaultBlobException(
      'Format invalide (coffre v2-only) — possible tampering.',
    );
  }

  if (blob.length < kVaultNonceLen + kVaultTagLen) {
    throw const VaultBlobException('Bloc invalide');
  }
  return gcmProcess(
    forEncryption: false,
    input: Uint8List.sublistView(blob, kVaultNonceLen),
    key: key,
    nonce: Uint8List.sublistView(blob, 0, kVaultNonceLen),
    aad: Uint8List(0),
  );
}
