/// Lecture et écriture d'entiers big-endian, pour les formats binaires du
/// coffre (blob `.enc` et sauvegarde `.rftvault`).
///
/// **Pourquoi ce fichier existe.** `vault_service.dart` portait cinq helpers
/// d'octets, dont **deux strictement identiques** : `_readU32be` et
/// `_readInt32be` avaient le même corps, à la ligne près. Rien ne le signalait,
/// et rien n'empêchait qu'un correctif appliqué à l'un manque à l'autre — le
/// motif exact du « correctif asymétrique entre jumeaux ».
///
/// Une seule implémentation, testable, et le doublon disparaît par
/// construction.
library;

import 'dart:typed_data';

/// Lit un entier 32 bits non signé big-endian à l'offset [off].
int readU32be(Uint8List b, int off) =>
    (b[off] << 24) | (b[off + 1] << 16) | (b[off + 2] << 8) | b[off + 3];

/// Lit un entier 16 bits non signé big-endian à l'offset [off].
int readU16be(Uint8List b, int off) => (b[off] << 8) | b[off + 1];

/// Écrit [v] sur 4 octets big-endian.
Uint8List u32be(int v) => Uint8List(4)
  ..[0] = (v >> 24) & 0xff
  ..[1] = (v >> 16) & 0xff
  ..[2] = (v >> 8) & 0xff
  ..[3] = v & 0xff;

/// Écrit [v] sur 2 octets big-endian.
Uint8List u16be(int v) => Uint8List(2)
  ..[0] = (v >> 8) & 0xff
  ..[1] = v & 0xff;

/// `true` si [bytes] commence par [magic].
///
/// Écrit une fois ici plutôt que recopié à chaque format : la comparaison
/// manuelle octet par octet est exactement le genre de code où un index oublié
/// passe inaperçu, et où l'erreur consiste à accepter un blob qu'il fallait
/// refuser.
bool startsWithMagic(Uint8List bytes, List<int> magic) {
  if (bytes.length < magic.length) return false;
  for (var i = 0; i < magic.length; i++) {
    if (bytes[i] != magic[i]) return false;
  }
  return true;
}
